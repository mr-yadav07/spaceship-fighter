package app.espcontrol.protocol

/** ESPCTL v1 wire constants. See docs/PROTOCOL.md. All multi-byte integers are big-endian. */
object Wire {
    const val VERSION = 1
    const val HEADER_LEN = 21
    const val TAG_LEN = 16
    const val FLAG_ENC = 0x01

    const val PORT_DEVICE = 47800   // devices listen here (local UDP)
    const val PORT_APP = 47801      // app listens here (local UDP)

    const val DIR_APP_TO_DEV = 1
    const val DIR_DEV_TO_APP = 2

    // Discovery / handshake (plain)
    const val T_DISCOVER = 0x01
    const val T_ANNOUNCE = 0x02
    const val T_HELLO_REQ = 0x10
    const val T_HELLO_RESP = 0x11

    // Session (encrypted)
    const val T_CAPS_GET = 0x20
    const val T_CAPS = 0x21
    const val T_SET_RGB = 0x30
    const val T_SET_BRIGHT = 0x31
    const val T_SET_POWER = 0x32
    const val T_SET_ONOFF = 0x33
    const val T_EFFECT_DATA = 0x40
    const val T_EFFECT_TRIGGER = 0x41
    const val T_GET_STATUS = 0x50
    const val T_STATUS = 0x60
    const val T_ACK = 0x61
    const val T_NO_DATA_1 = 0x62
    const val T_NO_DATA_2 = 0x63
    const val T_PING = 0x70
    const val T_ERROR = 0x7F

    // Relay server control (plain)
    const val T_RELAY_REGISTER = 0x80
    const val T_RELAY_PING = 0x81
    const val T_RELAY_OK = 0x82
    const val T_RELAY_LIST = 0x83

    const val E_BAD_CMD = 1
    const val E_NO_SESSION = 2
    const val E_AUTH = 3
    const val E_RANGE = 4
    const val E_BUSY = 5
    const val E_UNSUPPORTED = 6

    const val ALL_OUTPUTS = 0xFF
}
