package app.espcontrol.protocol

import app.espcontrol.core.Capabilities
import app.espcontrol.core.OnOffOutput
import app.espcontrol.core.RgbOutput

sealed class StatusEntry {
    data class Rgb(val out: Int, val on: Boolean?, val r: Int?, val g: Int?, val b: Int?, val brightness: Int?, val effect: Boolean?) : StatusEntry()
    data class OnOff(val out: Int, val state: Boolean) : StatusEntry()
}

data class Announce(val authorized: Boolean, val type: Int, val fw: Int, val name: String)
data class RelayDevice(val id: ByteArray, val type: Int, val online: Boolean)

/** Payload builders and parsers for ESPCTL v1. Parsers never throw; they return null on malformed input. */
object Payloads {
    private fun u8(v: Int) = v.toByte()

    fun discover(nonce8: ByteArray, hint8: ByteArray) = nonce8 + hint8
    fun helloReq(nonceA: ByteArray, mac16: ByteArray) = nonceA + mac16

    fun setRgb(out: Int, r: Int, g: Int, b: Int) = byteArrayOf(u8(out), u8(r), u8(g), u8(b))
    fun setBrightness(out: Int, level: Int) = byteArrayOf(u8(out), u8(level))
    fun setPower(out: Int, on: Boolean) = byteArrayOf(u8(out), u8(if (on) 1 else 0))
    fun setOnOff(out: Int, on: Boolean) = byteArrayOf(u8(out), u8(if (on) 1 else 0))
    fun getStatus(out: Int) = byteArrayOf(u8(out))
    fun effectTrigger(out: Int, effectId: Int, start: Boolean) = byteArrayOf(u8(out), u8(effectId), u8(if (start) 1 else 0))
    fun effectData(effectId: Int, index: Int, total: Int, data: ByteArray) =
        byteArrayOf(u8(effectId), u8(index), u8(total)) + data

    fun relayRegister(role: Int, ownerId8: ByteArray, deviceIds: List<ByteArray>): ByteArray {
        var out = byteArrayOf(u8(role)) + ownerId8 + byteArrayOf(u8(deviceIds.size))
        deviceIds.forEach { out += it }
        return out
    }

    fun parseAnnounce(p: ByteArray): Announce? {
        if (p.size < 4) return null
        val nameLen = p[3].toInt() and 0xFF
        if (p.size < 4 + nameLen) return null
        return Announce(
            authorized = p[0].toInt() != 0,
            type = p[1].toInt() and 0xFF,
            fw = p[2].toInt() and 0xFF,
            name = String(p, 4, nameLen, Charsets.UTF_8),
        )
    }

    fun parseCaps(p: ByteArray): Capabilities? {
        var i = 0
        fun has(n: Int) = i + n <= p.size
        if (!has(3)) return null
        val type = p[i++].toInt() and 0xFF
        val fw = p[i++].toInt() and 0xFF
        val nameLen = p[i++].toInt() and 0xFF
        if (!has(nameLen + 1)) return null
        val name = String(p, i, nameLen, Charsets.UTF_8); i += nameLen
        val nRgb = p[i++].toInt() and 0xFF
        if (!has(nRgb * 2 + 1)) return null
        val rgb = ArrayList<RgbOutput>()
        repeat(nRgb) { rgb.add(RgbOutput(p[i].toInt() and 0xFF, p[i + 1].toInt() and 0xFF)); i += 2 }
        val nOn = p[i++].toInt() and 0xFF
        if (!has(nOn)) return null
        val on = ArrayList<OnOffOutput>()
        repeat(nOn) { on.add(OnOffOutput(p[i].toInt() and 0xFF)); i++ }
        return Capabilities(name, type, fw, rgb, on)
    }

    fun parseStatus(p: ByteArray): List<StatusEntry>? {
        if (p.isEmpty()) return null
        var i = 1
        val count = p[0].toInt() and 0xFF
        val out = ArrayList<StatusEntry>()
        repeat(count) {
            if (i + 3 > p.size) return null
            val id = p[i].toInt() and 0xFF
            val kind = p[i + 1].toInt() and 0xFF
            val mask = p[i + 2].toInt() and 0xFF
            i += 3
            if (kind == 0) {
                var on: Boolean? = null; var r: Int? = null; var g: Int? = null; var b: Int? = null
                var br: Int? = null; var fx: Boolean? = null
                if (mask and 1 != 0) { if (i + 1 > p.size) return null; on = p[i++].toInt() != 0 }
                if (mask and 2 != 0) {
                    if (i + 3 > p.size) return null
                    r = p[i].toInt() and 0xFF; g = p[i + 1].toInt() and 0xFF; b = p[i + 2].toInt() and 0xFF; i += 3
                }
                if (mask and 4 != 0) { if (i + 1 > p.size) return null; br = p[i++].toInt() and 0xFF }
                if (mask and 8 != 0) { if (i + 1 > p.size) return null; fx = p[i++].toInt() != 0 }
                out.add(StatusEntry.Rgb(id, on, r, g, b, br, fx))
            } else {
                if (mask and 1 != 0) { if (i + 1 > p.size) return null; out.add(StatusEntry.OnOff(id, p[i++].toInt() != 0)) }
            }
        }
        return out
    }

    fun parseAck(p: ByteArray): Pair<Int, Int>? {
        if (p.size < 5) return null
        val seq = ((p[0].toInt() and 0xFF) shl 24) or ((p[1].toInt() and 0xFF) shl 16) or ((p[2].toInt() and 0xFF) shl 8) or (p[3].toInt() and 0xFF)
        return seq to (p[4].toInt() and 0xFF)
    }

    fun parseRelayList(p: ByteArray): List<RelayDevice>? {
        if (p.isEmpty()) return null
        val n = p[0].toInt() and 0xFF
        if (p.size < 1 + n * 8) return null
        val out = ArrayList<RelayDevice>()
        var i = 1
        repeat(n) {
            out.add(RelayDevice(p.copyOfRange(i, i + 6), p[i + 6].toInt() and 0xFF, p[i + 7].toInt() != 0))
            i += 8
        }
        return out
    }
}
