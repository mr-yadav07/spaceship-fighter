package app.espcontrol.protocol

data class Header(
    val type: Int,
    val flags: Int,
    val deviceId: ByteArray,
    val session: Int,
    val seq: Int,
    val bodyLen: Int,
) {
    val encrypted: Boolean get() = flags and Wire.FLAG_ENC != 0
}

object PacketCodec {
    private fun putInt(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 24).toByte(); b[off + 1] = (v ushr 16).toByte(); b[off + 2] = (v ushr 8).toByte(); b[off + 3] = v.toByte()
    }

    private fun getInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    /** Parse and validate the plain header. Never throws. */
    fun peek(raw: ByteArray): Header? {
        if (raw.size < Wire.HEADER_LEN) return null
        if (raw[0] != 0x45.toByte() || raw[1] != 0x43.toByte() || raw[2].toInt() != Wire.VERSION) return null
        val len = ((raw[19].toInt() and 0xFF) shl 8) or (raw[20].toInt() and 0xFF)
        if (raw.size < Wire.HEADER_LEN + len) return null
        return Header(
            type = raw[3].toInt() and 0xFF,
            flags = raw[4].toInt() and 0xFF,
            deviceId = raw.copyOfRange(5, 11),
            session = getInt(raw, 11),
            seq = getInt(raw, 15),
            bodyLen = len,
        )
    }

    /** key == null -> plain packet; otherwise payload is sealed with ChaCha20-Poly1305 (header is the AAD). */
    fun encode(
        type: Int, deviceId: ByteArray, session: Int, seq: Int, payload: ByteArray,
        key: ByteArray?, dir: Int = Wire.DIR_APP_TO_DEV,
    ): ByteArray {
        val enc = key != null
        val bodyLen = payload.size + if (enc) Wire.TAG_LEN else 0
        val out = ByteArray(Wire.HEADER_LEN + bodyLen)
        out[0] = 0x45; out[1] = 0x43; out[2] = Wire.VERSION.toByte()
        out[3] = type.toByte()
        out[4] = (if (enc) Wire.FLAG_ENC else 0).toByte()
        deviceId.copyInto(out, 5, 0, 6)
        putInt(out, 11, session)
        putInt(out, 15, seq)
        out[19] = (bodyLen ushr 8).toByte(); out[20] = bodyLen.toByte()
        if (enc) {
            val aad = out.copyOf(Wire.HEADER_LEN)
            val sealed = Crypto.seal(key!!, Crypto.nonce(dir, session, seq), aad, payload)
            sealed.copyInto(out, Wire.HEADER_LEN)
        } else {
            payload.copyInto(out, Wire.HEADER_LEN)
        }
        return out
    }

    /** Returns the plaintext payload, or null if authentication fails / key missing for an encrypted packet. */
    fun payload(raw: ByteArray, h: Header, key: ByteArray?, senderDir: Int): ByteArray? {
        val body = raw.copyOfRange(Wire.HEADER_LEN, Wire.HEADER_LEN + h.bodyLen)
        if (!h.encrypted) return body
        if (key == null || body.size < Wire.TAG_LEN) return null
        val aad = raw.copyOf(Wire.HEADER_LEN)
        return Crypto.open(key, Crypto.nonce(senderDir, h.session, h.seq), aad, body)
    }
}

/** Sliding replay window (64 packets) for sequence numbers of one direction. */
class ReplayWindow {
    private var max = -1L
    private var bits = 0L

    fun reset() { max = -1L; bits = 0L }

    /** Returns true if the sequence is fresh and records it. */
    fun accept(seq: Int): Boolean {
        val s = seq.toLong() and 0xFFFFFFFFL
        if (max < 0) { max = s; bits = 1L; return true }
        if (s > max) {
            val shift = s - max
            bits = if (shift >= 64) 1L else (bits shl shift.toInt()) or 1L
            max = s
            return true
        }
        val diff = max - s
        if (diff >= 64) return false
        val mask = 1L shl diff.toInt()
        if (bits and mask != 0L) return false
        bits = bits or mask
        return true
    }
}
