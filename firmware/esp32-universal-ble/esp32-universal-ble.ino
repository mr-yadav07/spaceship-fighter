/*
 * ESPControl Universal BLE Firmware - Protocol v2
 * Arduino-ESP32 (NimBLE-Arduino 2.x)
 *
 * This is the communication adapter, not an LED/switch application.
 * Configure candidate descriptors and implement the three callbacks below
 * for your own hardware. The same source works with any candidate count
 * supported by the device's memory and BLE packet-size limit.
 */
#include <Arduino.h>
#include <NimBLEDevice.h>
#include <vector>

static constexpr char SERVICE_UUID[] = "a6c60001-6b9f-4d8e-a251-5bd248e8c101";
static constexpr char COMMAND_UUID[] = "a6c60002-6b9f-4d8e-a251-5bd248e8c101";
static constexpr char EVENTS_UUID[]  = "a6c60003-6b9f-4d8e-a251-5bd248e8c101";
static constexpr uint8_t MAGIC0=0xEC, MAGIC1=0x53, VERSION=2;
enum : uint8_t { CAPS_REQUEST=0x01, CAPS=0x02, SWITCH_SET=0x10, RGB_SET=0x11,
                 EFFECT_SET=0x12, UPDATE=0x20, STATUS_REQUEST=0x21, STATUS=0x22 };
enum : uint8_t { SWITCH_KIND=1, RGB_KIND=2, EFFECT_KIND=3 };

struct Candidate { uint8_t kind, id; uint16_t leds; const char* name; };
struct Effect { uint8_t id; const char* name; };

// EDIT THESE DESCRIPTORS PER DEVICE. They describe capabilities only.
// The IDs are local to this ESP32; they do not need to be globally unique.
static const char DEVICE_NAME[] = "ESP32";
static const Candidate CANDIDATES[] = {
  {SWITCH_KIND, 1, 0, "Switch 1"},
  {RGB_KIND,    1, 64, "RGB 1"},
};
static const Effect EFFECTS[] = {
  {1, "Rainbow"}, {2, "Pulse"}, {3, "Wave"}, {4, "Chase"},
  {5, "Color Wipe"}, {6, "Breath"}, {7, "Sparkle"}, {8, "Static"}
};

static NimBLECharacteristic* eventChar = nullptr;
static std::vector<uint8_t> pendingFeedback;
static uint32_t feedbackDue = 0;

// USER HARDWARE HOOKS: replace bodies with your own implementation.
// Keep them non-blocking. They receive logical values, never raw packet buffers.
void onSwitchValue(uint8_t candidateId, bool on) { (void)candidateId; (void)on; }
void onRgbValue(uint8_t candidateId, uint8_t r, uint8_t g, uint8_t b) { (void)candidateId; (void)r; (void)g; (void)b; }
void onEffectValue(uint8_t candidateId, uint8_t effectId, uint8_t speed) { (void)candidateId; (void)effectId; (void)speed; }

// Implement these getters to return actual state after hardware application.
bool readSwitchValue(uint8_t id) { (void)id; return false; }
void readRgbValue(uint8_t id, uint8_t &r, uint8_t &g, uint8_t &b) { (void)id; r=g=b=0; }
void readEffectValue(uint8_t id, uint8_t &effect, uint8_t &speed) { (void)id; effect=speed=0; }

static uint16_t rd16(const uint8_t* p) { return uint16_t(p[0]) | (uint16_t(p[1])<<8); }
static void put16(std::vector<uint8_t>& b, uint16_t v) { b.push_back(v&255); b.push_back(v>>8); }
static bool hasCandidate(uint8_t kind,uint8_t id) {
  for (auto &c:CANDIDATES) if(c.kind==kind && c.id==id) return true;
  return false;
}
static bool hasEffect(uint8_t id) {
  if(id==0) return true;
  for(auto &e:EFFECTS) if(e.id==id) return true;
  return false;
}
static void sendFrame(uint8_t type,const std::vector<uint8_t>& p) {
  if(!eventChar || p.size()>508) return;
  std::vector<uint8_t> f={MAGIC0,MAGIC1,VERSION,type,uint8_t(p.size()),uint8_t(p.size()>>8)};
  f.insert(f.end(),p.begin(),p.end());
  eventChar->setValue(f.data(),f.size());
  eventChar->notify();
}
static void sendCaps() {
  std::vector<uint8_t> p;
  size_t nl=strlen(DEVICE_NAME); p.push_back(uint8_t(nl)); p.insert(p.end(),DEVICE_NAME,DEVICE_NAME+nl);
  p.push_back(uint8_t(sizeof(CANDIDATES)/sizeof(CANDIDATES[0])));
  for(auto &c:CANDIDATES) {
    p.push_back(c.kind); p.push_back(c.id);
    if(c.kind==RGB_KIND) put16(p,c.leds);
    size_t n=strlen(c.name); p.push_back(uint8_t(n)); p.insert(p.end(),c.name,c.name+n);
  }
  p.push_back(uint8_t(sizeof(EFFECTS)/sizeof(EFFECTS[0])));
  for(auto &e:EFFECTS) { p.push_back(e.id); size_t n=strlen(e.name); p.push_back(uint8_t(n)); p.insert(p.end(),e.name,e.name+n); }
  sendFrame(CAPS,p);
}
static void queueFeedback(uint8_t kind,uint8_t id) {
  // Replace an existing pending delta for this exact (kind,id); preserve all others.
  size_t pos=0; bool found=false;
  while(pos<pendingFeedback.size()) {
    uint8_t k=pendingFeedback[pos], cid=pendingFeedback[pos+1];
    size_t len=(k==SWITCH_KIND)?3:(k==RGB_KIND?5:4);
    if(k==kind && cid==id) { pendingFeedback.erase(pendingFeedback.begin()+pos,pendingFeedback.begin()+pos+len); found=true; break; }
    pos+=len;
  }
  (void)found;
  pendingFeedback.push_back(kind); pendingFeedback.push_back(id);
  if(kind==SWITCH_KIND) pendingFeedback.push_back(readSwitchValue(id)?1:0);
  else if(kind==RGB_KIND) { uint8_t r,g,b; readRgbValue(id,r,g,b); pendingFeedback.insert(pendingFeedback.end(),{r,g,b}); }
  else { uint8_t e,s; readEffectValue(id,e,s); pendingFeedback.push_back(e); pendingFeedback.push_back(s); }
  feedbackDue=millis()+500;
}
static void flushFeedback() {
  if(pendingFeedback.empty() || int32_t(millis()-feedbackDue)<0) return;
  // Max BLE ATT payload after negotiated MTU 185 is 182 bytes. One delta frame must fit.
  std::vector<uint8_t> p; p.push_back(0);
  size_t pos=0, used=0;
  while(pos<pendingFeedback.size()) {
    uint8_t k=pendingFeedback[pos]; size_t len=(k==SWITCH_KIND)?3:(k==RGB_KIND?5:4);
    if(used+len+1>175) break;
    p.insert(p.end(),pendingFeedback.begin()+pos,pendingFeedback.begin()+pos+len);
    pos+=len; used+=len; p[0]++;
  }
  if(p[0]) sendFrame(UPDATE,p);
  pendingFeedback.erase(pendingFeedback.begin(),pendingFeedback.begin()+pos);
  if(!pendingFeedback.empty()) feedbackDue=millis()+10;
}
static void sendStatus() {
  std::vector<uint8_t> p; p.push_back(0);
  for(auto &c:CANDIDATES) {
    if(c.kind==SWITCH_KIND) { p.insert(p.end(),{SWITCH_KIND,c.id,uint8_t(readSwitchValue(c.id)?1:0)}); }
    else if(c.kind==RGB_KIND) { uint8_t r,g,b; readRgbValue(c.id,r,g,b); p.insert(p.end(),{RGB_KIND,c.id,r,g,b}); }
    p[0]++;
  }
  for(auto &c:CANDIDATES) if(c.kind==RGB_KIND) { uint8_t e,s; readEffectValue(c.id,e,s); p.insert(p.end(),{EFFECT_KIND,c.id,e,s}); p[0]++; }
  sendFrame(STATUS,p);
}
static void handleCommand(const uint8_t* d,size_t n) {
  if(n<6 || d[0]!=MAGIC0 || d[1]!=MAGIC1 || d[2]!=VERSION) return;
  uint8_t type=d[3]; uint16_t len=rd16(d+4);
  if(size_t(len)+6!=n) return;
  const uint8_t* p=d+6;
  if(type==CAPS_REQUEST) { if(len==0) sendCaps(); return; }
  if(type==STATUS_REQUEST) { if(len==0) sendStatus(); return; }
  if(len<1) return;
  uint8_t count=p[0]; size_t i=1;
  for(uint8_t x=0;x<count;x++) {
    if(type==SWITCH_SET) {
      if(i+2>len) return; uint8_t id=p[i++],v=p[i++];
      if(v>1 || !hasCandidate(SWITCH_KIND,id)) continue;
      onSwitchValue(id,v!=0); queueFeedback(SWITCH_KIND,id);
    } else if(type==RGB_SET) {
      if(i+4>len) return; uint8_t id=p[i++],r=p[i++],g=p[i++],b=p[i++];
      if(!hasCandidate(RGB_KIND,id)) continue;
      onRgbValue(id,r,g,b); queueFeedback(RGB_KIND,id);
    } else if(type==EFFECT_SET) {
      if(i+3>len) return; uint8_t id=p[i++],e=p[i++],s=p[i++];
      if(!hasCandidate(RGB_KIND,id) || !hasEffect(e)) continue;
      onEffectValue(id,e,s); queueFeedback(EFFECT_KIND,id);
    } else return;
  }
  if(i!=len) return;
}
class CommandCallbacks: public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* c, NimBLEConnInfo&) override {
    auto v=c->getValue(); handleCommand((const uint8_t*)v.data(),v.size());
  }
};
void setup() {
  Serial.begin(115200);
  char devName[18]; snprintf(devName, sizeof(devName), "ESPC-%012llX", (unsigned long long)(ESP.getEfuseMac() & 0xFFFFFFFFFFFFULL));\n  NimBLEDevice::init(std::string(devName));
  NimBLEDevice::setMTU(517);
  auto *server=NimBLEDevice::createServer();
  auto *service=server->createService(SERVICE_UUID);
  auto *cmd=service->createCharacteristic(COMMAND_UUID,NIMBLE_PROPERTY::WRITE|NIMBLE_PROPERTY::WRITE_NR);
  eventChar=service->createCharacteristic(EVENTS_UUID,NIMBLE_PROPERTY::NOTIFY);
  cmd->setCallbacks(new CommandCallbacks());
  service->start();
  auto *adv=NimBLEDevice::getAdvertising(); adv->addServiceUUID(SERVICE_UUID); adv->start();
}
void loop() { flushFeedback(); delay(1); }
