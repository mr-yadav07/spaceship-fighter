package app.espcontrol.protocol

import app.espcontrol.core.Capabilities
import app.espcontrol.core.EffectCapability
import app.espcontrol.core.OnOffOutput
import app.espcontrol.core.RgbOutput

object ProtocolV2 {
    const val CAPS_REQUEST = 0x01
    const val CAPS = 0x02
    const val SWITCH_SET = 0x10
    const val RGB_SET = 0x11
    const val EFFECT_SET = 0x12
    const val RGB_TRANSITION = 0x13
    const val UPDATE = 0x20
    const val STATUS_REQUEST = 0x21
    const val STATUS = 0x22
    const val SWITCH = 1
    const val RGB = 2
    const val EFFECT = 3
    private const val MAGIC_A = 0xEC
    private const val MAGIC_B = 0x53
    private const val VERSION = 2

    data class Frame(val type: Int, val payload: ByteArray)
    sealed class StateEntry {
        data class Switch(val id: Int, val on: Boolean) : StateEntry()
        data class Rgb(val id: Int, val r: Int, val g: Int, val b: Int, val brightness: Int, val on: Boolean) : StateEntry()
        data class Effect(val id: Int, val effectId: Int, val speed: Int) : StateEntry()
    }

    fun encode(type: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        require(type in 0..255 && payload.size <= 65535)
        return byteArrayOf(MAGIC_A.toByte(), MAGIC_B.toByte(), VERSION.toByte(), type.toByte(),
            payload.size.toByte(), (payload.size ushr 8).toByte()) + payload
    }

    fun decode(bytes: ByteArray): Frame? {
        if (bytes.size < 6 || (bytes[0].toInt() and 255) != MAGIC_A ||
            (bytes[1].toInt() and 255) != MAGIC_B || (bytes[2].toInt() and 255) != VERSION) return null
        val n = (bytes[4].toInt() and 255) or ((bytes[5].toInt() and 255) shl 8)
        if (n != bytes.size - 6) return null
        val type = bytes[3].toInt() and 255
        if (type !in setOf(CAPS, UPDATE, STATUS)) return null
        return Frame(type, bytes.copyOfRange(6, bytes.size))
    }

    fun parseCaps(p: ByteArray): Capabilities? = runCatching {
        var i = 0
        fun u(): Int { require(i < p.size); return p[i++].toInt() and 255 }
        fun str(): String { val n=u(); require(i+n<=p.size); return String(p, i, n, Charsets.UTF_8).also { i+=n } }
        val name = str()
        val count = u()
        val rgb = mutableListOf<RgbOutput>()
        val switches = mutableListOf<OnOffOutput>()
        val ids = mutableSetOf<Pair<Int,Int>>()
        repeat(count) {
            val kind=u(); val id=u()
            require(ids.add(kind to id))
            if (kind == SWITCH) { switches += OnOffOutput(id, str()) }
            else if (kind == RGB) { require(i+2<=p.size); val leds=u() or (u() shl 8); require(leds>0); rgb += RgbOutput(id,leds,str()) }
            else error("Unknown candidate kind")
        }
        val effectCount=u()
        val effects=mutableListOf<EffectCapability>()
        val effectIds=mutableSetOf<Int>()
        repeat(effectCount) { val id=u(); require(effectIds.add(id)); effects += EffectCapability(id,str()) }
        require(i==p.size)
        Capabilities(name, 0, 2, rgb, switches, effects)
    }.getOrNull()

    fun parseStates(p: ByteArray): List<StateEntry>? = runCatching {
        var i=0
        fun u(): Int { require(i<p.size); return p[i++].toInt() and 255 }
        val count=u()
        val result=ArrayList<StateEntry>(count)
        repeat(count) {
            when(val kind=u()) {
                SWITCH -> result += StateEntry.Switch(u(), u().also { require(it<=1) } == 1)
                RGB -> { val id=u(); val r=u(); val g=u(); val b=u(); val brightness=u(); val on=u(); require(on<=1); result += StateEntry.Rgb(id,r,g,b,brightness,on==1) }
                EFFECT -> result += StateEntry.Effect(u(),u(),u())
                else -> error("Unknown state kind $kind")
            }
        }
        require(i==p.size)
        result
    }.getOrNull()

    fun switches(values: List<Pair<Int,Boolean>>) = byteArrayOf(values.size.toByte()) +
        values.flatMap { listOf(it.first.toByte(), (if(it.second) 1 else 0).toByte()) }.toByteArray()

    fun rgbs(values: List<RgbCommand>) = byteArrayOf(values.size.toByte()) + values.flatMap {
        listOf(it.id.toByte(),it.r.toByte(),it.g.toByte(),it.b.toByte(),it.brightness.toByte(),(if(it.on) 1 else 0).toByte())
    }.toByteArray()

    fun effects(values: List<EffectCommand>) = byteArrayOf(values.size.toByte()) +
        values.flatMap { listOf(it.id.toByte(),it.effectId.toByte(),it.speed.toByte()) }.toByteArray()

    /** Target state plus local transition duration and common start delay. */
    fun rgbTransitions(values: List<RgbTransitionCommand>) = byteArrayOf(values.size.toByte()) +
        values.flatMap {
            listOf(it.id,it.r,it.g,it.b,it.brightness,if(it.on) 1 else 0,
                it.durationMs and 255,(it.durationMs ushr 8) and 255,
                it.startDelayMs and 255,(it.startDelayMs ushr 8) and 255).map(Int::toByte)
        }.toByteArray()

    data class RgbCommand(val id:Int,val r:Int,val g:Int,val b:Int,val brightness:Int,val on:Boolean)
    data class EffectCommand(val id:Int,val effectId:Int,val speed:Int)
    data class RgbTransitionCommand(val id:Int,val r:Int,val g:Int,val b:Int,val brightness:Int,val on:Boolean,val durationMs:Int=180,val startDelayMs:Int=60)
}
