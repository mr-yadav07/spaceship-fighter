package app.espcontrol.core

/** Routes in priority order: direct local UDP first, then BLE, then the internet relay. */
enum class RouteKind { LOCAL_UDP, BLE, RELAY_UDP }

enum class LinkState { OFFLINE, CONNECTING, ONLINE, UNAUTHORIZED }

data class RgbOutput(val id: Int, val leds: Int)
data class OnOffOutput(val id: Int)

data class Capabilities(
    val name: String,
    val type: Int,
    val fw: Int,
    val rgb: List<RgbOutput>,
    val onoff: List<OnOffOutput>,
)

data class RgbState(
    val on: Boolean = false,
    val r: Int = 0,
    val g: Int = 0,
    val b: Int = 0,
    val brightness: Int = 255,
    val effectActive: Boolean = false,
)

data class DeviceInfo(
    val id: String,
    val name: String,
    val type: Int,
    val authorized: Boolean,
    val paired: Boolean,
    val state: LinkState,
    val availableRoutes: Set<RouteKind>,
    val activeRoute: RouteKind?,
    val caps: Capabilities?,
    val rgbStates: Map<Int, RgbState>,
    val onOffStates: Map<Int, Boolean>,
    val lastError: String?,
)

object Hex {
    private const val D = "0123456789abcdef"
    fun of(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) { sb.append(D[(x.toInt() shr 4) and 15]); sb.append(D[x.toInt() and 15]) }
        return sb.toString()
    }
    fun parse(s: String): ByteArray? {
        val t = s.trim().lowercase().replace(":", "").replace("-", "")
        if (t.length % 2 != 0) return null
        return try { ByteArray(t.length / 2) { t.substring(it * 2, it * 2 + 2).toInt(16).toByte() } } catch (e: NumberFormatException) { null }
    }
}
