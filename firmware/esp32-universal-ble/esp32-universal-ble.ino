/*
 * ESPControl Universal BLE Firmware - Protocol v2
 * Arduino-ESP32 + NimBLE-Arduino 2.x
 *
 * Configure the candidate/effect descriptors for each board. Implement the
 * hardware hooks and state readers for the actual GPIO/LED implementation.
 */
#include <Arduino.h>
#include <NimBLEDevice.h>
#include <vector>
#include <mutex>

static constexpr char SERVICE_UUID[] = "a6c60001-6b9f-4d8e-a251-5bd248e8c101";
static constexpr char COMMAND_UUID[] = "a6c60002-6b9f-4d8e-a251-5bd248e8c101";
static constexpr char EVENTS_UUID[]  = "a6c60003-6b9f-4d8e-a251-5bd248e8c101";
static constexpr uint8_t MAGIC0=0xEC, MAGIC1=0x53, VERSION=2;
enum : uint8_t { CAPS_REQUEST=0x01, CAPS=0x02, SWITCH_SET=0x10, RGB_SET=0x11,
                 EFFECT_SET=0x12, UPDATE=0x20, STATUS_REQUEST=0x21, STATUS=0x22 };
enum : uint8_t { SWITCH_KIND=1, RGB_KIND=2, EFFECT_KIND=3 };

struct Candidate { uint8_t kind, id; uint16_t leds; const char* name; };
struct Effect { uint8_t id; const char* name; };

// Configure candidates independently on every ESP32. IDs are device-local.
static const char DEVICE_NAME[] = "ESP32";
static const Candidate CANDIDATES[] = {
  {SWITCH_KIND, 1, 0, "Switch 1"},
  {RGB_KIND, 1, 64, "RGB 1"},
};
static const Effect EFFECTS[] = {
  {1,"Rainbow"},{2,"Pulse"},{3,"Wave"},{4,"Chase"},
  {5,"Color Wipe"},{6,"Breath"},{7,"Sparkle"},{8,"Static"}
};

static NimBLECharacteristic* eventChar=nullptr;
static std::vector<uint8_t> pending;
static uint32_t feedbackDue=0;
struct PendingCommand { uint8_t kind,id,a,b,c,brightness,on; };
static std::vector<PendingCommand> commandQueue;
static std::mutex commandMutex;

// Return true only after the requested value has actually been applied.
// Replace these stubs with non-blocking GPIO/LED driver operations.
bool applySwitch(uint8_t id,bool on) { (void)id; (void)on; return false; }
bool applyRgb(uint8_t id,uint8_t r,uint8_t g,uint8_t b,uint8_t brightness,bool on) {
  (void)id;(void)r;(void)g;(void)b;(void)brightness;(void)on; return false;
}
bool applyEffect(uint8_t id,uint8_t effect,uint8_t speed) { (void)id;(void)effect;(void)speed; return false; }

// Return actual applied state. Replace these defaults with hardware state readers.
bool readSwitch(uint8_t id) { (void)id; return false; }
void readRgb(uint8_t id,uint8_t &r,uint8_t &g,uint8_t &b,uint8_t &brightness,bool &on) {
  (void)id; r=g=b=0; brightness=255; on=false;
}
void readEffect(uint8_t id,uint8_t &effect,uint8_t &speed) { (void)id; effect=speed=0; }

static uint16_t rd16(const uint8_t* p) { return uint16_t(p[0]) | (uint16_t(p[1])<<8); }
static void put16(std::vector<uint8_t>& b,uint16_t v) { b.push_back(v&255); b.push_back(v>>8); }
static bool hasCandidate(uint8_t kind,uint8_t id) {
  for(const auto &c:CANDIDATES) if(c.kind==kind && c.id==id) return true;
  return false;
}
static bool hasEffect(uint8_t id) {
  if(id==0) return true;
  for(const auto &e:EFFECTS) if(e.id==id) return true;
  return false;
}
static size_t entryLength(uint8_t kind) {
  return kind==SWITCH_KIND?3:(kind==RGB_KIND?7:(kind==EFFECT_KIND?4:0));
}
static void sendFrame(uint8_t type,const std::vector<uint8_t>& p) {
  // MTU 517 gives 514 notification bytes; frame header consumes 6.
  if(!eventChar || p.size()>508) return;
  std::vector<uint8_t> f={MAGIC0,MAGIC1,VERSION,type,uint8_t(p.size()),uint8_t(p.size()>>8)};
  f.insert(f.end(),p.begin(),p.end());
  eventChar->setValue(f.data(),f.size());
  eventChar->notify();
}
static void sendCaps() {
  std::vector<uint8_t> p;
  size_t n=strlen(DEVICE_NAME); if(n>255) return;
  p.push_back(uint8_t(n)); p.insert(p.end(),DEVICE_NAME,DEVICE_NAME+n);
  if(sizeof(CANDIDATES)/sizeof(CANDIDATES[0])>255 || sizeof(EFFECTS)/sizeof(EFFECTS[0])>255) return;
  p.push_back(uint8_t(sizeof(CANDIDATES)/sizeof(CANDIDATES[0])));
  for(const auto &c:CANDIDATES) {
    p.push_back(c.kind); p.push_back(c.id);
    if(c.kind==RGB_KIND) put16(p,c.leds);
    n=strlen(c.name); if(n>255) return;
    p.push_back(uint8_t(n)); p.insert(p.end(),c.name,c.name+n);
  }
  p.push_back(uint8_t(sizeof(EFFECTS)/sizeof(EFFECTS[0])));
  for(const auto &e:EFFECTS) {
    p.push_back(e.id); n=strlen(e.name); if(n>255) return;
    p.push_back(uint8_t(n)); p.insert(p.end(),e.name,e.name+n);
  }
  sendFrame(CAPS,p);
}
static void appendActual(std::vector<uint8_t>& out,uint8_t kind,uint8_t id) {
  out.push_back(kind); out.push_back(id);
  if(kind==SWITCH_KIND) out.push_back(readSwitch(id)?1:0);
  else if(kind==RGB_KIND) {
    uint8_t r,g,b,br; bool on; readRgb(id,r,g,b,br,on);
    out.insert(out.end(),{r,g,b,br,uint8_t(on?1:0)});
  } else {
    uint8_t e,s; readEffect(id,e,s); out.push_back(e); out.push_back(s);
  }
}
static void queueActual(uint8_t kind,uint8_t id) {
  const size_t len=entryLength(kind);
  size_t pos=0;
  while(pos<pending.size()) {
    uint8_t k=pending[pos]; size_t oldLen=entryLength(k);
    if(!oldLen || pos+oldLen>pending.size()) { pending.clear(); break; }
    if(k==kind && pending[pos+1]==id) {
      pending.erase(pending.begin()+pos,pending.begin()+pos+oldLen);
      break;
    }
    pos+=oldLen;
  }
  std::vector<uint8_t> entry;
  appendActual(entry,kind,id);
  pending.insert(pending.end(),entry.begin(),entry.end());
  feedbackDue=millis()+500;
}
static void flushFeedback() {
  if(pending.empty() || int32_t(millis()-feedbackDue)<0) return;
  std::vector<uint8_t> p; p.push_back(0);
  size_t pos=0;
  while(pos<pending.size()) {
    uint8_t kind=pending[pos]; size_t n=entryLength(kind);
    if(!n || pos+n>pending.size() || p.size()+n>508) break;
    p.insert(p.end(),pending.begin()+pos,pending.begin()+pos+n);
    pos+=n; p[0]++;
  }
  if(p[0]) sendFrame(UPDATE,p);
  pending.erase(pending.begin(),pending.begin()+pos);
  if(!pending.empty()) feedbackDue=millis()+10;
}
static void sendStatus() {
  std::vector<uint8_t> p; p.push_back(0);
  for(const auto &c:CANDIDATES) {
    if(c.kind!=SWITCH_KIND && c.kind!=RGB_KIND) continue;
    appendActual(p,c.kind,c.id); p[0]++;
  }
  for(const auto &c:CANDIDATES) if(c.kind==RGB_KIND) {
    appendActual(p,EFFECT_KIND,c.id); p[0]++;
  }
  sendFrame(STATUS,p);
}
static bool validateCommand(uint8_t type,const uint8_t* p,size_t len) {
  if(len<1) return false;
  uint8_t count=p[0]; size_t entry=(type==SWITCH_SET?2:(type==RGB_SET?6:(type==EFFECT_SET?3:0)));
  if(!entry || len!=1+size_t(count)*entry) return false;
  size_t i=1;
  for(uint8_t x=0;x<count;x++) {
    uint8_t id=p[i++];
    if(type==SWITCH_SET) {
      uint8_t value=p[i++]; if(value>1 || !hasCandidate(SWITCH_KIND,id)) return false;
    } else if(type==RGB_SET) {
      i+=4; uint8_t on=p[i++]; if(on>1 || !hasCandidate(RGB_KIND,id)) return false;
    } else {
      uint8_t effect=p[i++]; i++; if(!hasCandidate(RGB_KIND,id) || !hasEffect(effect)) return false;
    }
  }
  return i==len;
}
static void enqueueCommand(const PendingCommand& cmd) {
  std::lock_guard<std::mutex> guard(commandMutex);
  for(auto &queued:commandQueue) {
    if(queued.kind==cmd.kind && queued.id==cmd.id) { queued=cmd; return; }
  }
  commandQueue.push_back(cmd);
}
static void processPendingCommands() {
  PendingCommand cmd{};
  {
    std::lock_guard<std::mutex> guard(commandMutex);
    if(commandQueue.empty()) return;
    cmd=commandQueue.front();
    commandQueue.erase(commandQueue.begin());
  }
  bool applied=false;
  if(cmd.kind==SWITCH_KIND) applied=applySwitch(cmd.id,cmd.a!=0);
  else if(cmd.kind==RGB_KIND) applied=applyRgb(cmd.id,cmd.a,cmd.b,cmd.c,cmd.brightness,cmd.on!=0);
  else if(cmd.kind==EFFECT_KIND) applied=applyEffect(cmd.id,cmd.a,cmd.b);
  if(applied) queueActual(cmd.kind,cmd.id);
}
static void handleCommand(const uint8_t* d,size_t n) {
  if(n<6 || d[0]!=MAGIC0 || d[1]!=MAGIC1 || d[2]!=VERSION) return;
  uint8_t type=d[3]; uint16_t len=rd16(d+4);
  if(size_t(len)+6!=n) return;
  const uint8_t* p=d+6;
  if(type==CAPS_REQUEST) { if(len==0) sendCaps(); return; }
  if(type==STATUS_REQUEST) { if(len==0) sendStatus(); return; }
  if(!validateCommand(type,p,len)) return;
  uint8_t count=p[0]; size_t i=1;
  for(uint8_t x=0;x<count;x++) {
    PendingCommand cmd{};
    cmd.id=p[i++];
    if(type==SWITCH_SET) {
      cmd.kind=SWITCH_KIND; cmd.a=p[i++];
    } else if(type==RGB_SET) {
      cmd.kind=RGB_KIND; cmd.a=p[i++]; cmd.b=p[i++]; cmd.c=p[i++];
      cmd.brightness=p[i++]; cmd.on=p[i++];
    } else {
      cmd.kind=EFFECT_KIND; cmd.a=p[i++]; cmd.b=p[i++];
    }
    enqueueCommand(cmd);
  }
