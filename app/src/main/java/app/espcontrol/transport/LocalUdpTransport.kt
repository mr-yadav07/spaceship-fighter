package app.espcontrol.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.espcontrol.core.RouteKind
import app.espcontrol.protocol.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Local Wi-Fi UDP. The socket is bound to the Wi-Fi network (so traffic never leaks to mobile data)
 * and listens on the user-configurable app port (default: [Wire.PORT_APP]). Devices answer discovery on the configurable device port.
 */
class LocalUdpTransport(ctx: Context) : Transport {
    override val kind = RouteKind.LOCAL_UDP
    private val flow = MutableSharedFlow<Rx>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val incoming: SharedFlow<Rx> = flow.asSharedFlow()

    private val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val prefs = ctx.getSharedPreferences("transport_config", Context.MODE_PRIVATE)
    @Volatile private var appPort = prefs.getInt("udp_app_port", Wire.PORT_APP).coerceIn(1, 65535)
    @Volatile private var devicePort = prefs.getInt("udp_device_port", Wire.PORT_DEVICE).coerceIn(1, 65535)
    @Volatile private var socket: DatagramSocket? = null
    private var job: Job? = null
    private var scope: CoroutineScope? = null

    override fun start(scope: CoroutineScope) {
        this.scope = scope
        open()
    }

    /** Call when the Wi-Fi network changes so the socket is re-bound. */
    fun restart() {
        close()
        open()
    }

    fun configure(newAppPort: Int, newDevicePort: Int) {
        if (newAppPort !in 1..65535 || newDevicePort !in 1..65535) return
        appPort = newAppPort; devicePort = newDevicePort
        prefs.edit().putInt("udp_app_port", newAppPort).putInt("udp_device_port", newDevicePort).apply()
        restart()
    }

    override fun stop() {
        job?.cancel()
        close()
    }

    private fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun open() {
        val sc = scope ?: return
        job?.cancel()
        job = sc.launch(Dispatchers.IO) {
            while (currentCoroutineContext().isActive) {
                val s = try { createSocket() } catch (e: Exception) { delay(3000); continue }
                socket = s
                val buf = ByteArray(2048)
                try {
                    while (currentCoroutineContext().isActive) {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        flow.tryEmit(Rx(kind, p.data.copyOf(p.length), "${p.address.hostAddress}:${p.port}"))
                    }
                } catch (e: Exception) {
                    // socket closed or network lost: fall through and re-open
                } finally {
                    runCatching { s.close() }
                    if (socket === s) socket = null
                }
                delay(1000)
            }
        }
    }

    private fun wifiNetwork(): Network? = cm.allNetworks.firstOrNull { n ->
        cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    private fun createSocket(): DatagramSocket {
        val s = DatagramSocket(null)
        s.reuseAddress = true
        s.broadcast = true
        wifiNetwork()?.bindSocket(s)
        s.bind(InetSocketAddress(appPort))
        return s
    }

    /** endpoint = "ip:port" as reported in [Rx.source]. */
    suspend fun sendTo(endpoint: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val s = socket ?: return@withContext false
        val i = endpoint.lastIndexOf(':')
        if (i < 0) return@withContext false
        try {
            val addr = InetAddress.getByName(endpoint.substring(0, i))
            s.send(DatagramPacket(bytes, bytes.size, addr, endpoint.substring(i + 1).toInt()))
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun broadcast(bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val s = socket ?: return@withContext false
        var sent = false
        for (addr in broadcastAddresses()) {
            try {
                s.send(DatagramPacket(bytes, bytes.size, addr, devicePort))
                sent = true
            } catch (e: Exception) {
                // try the next address
            }
        }
        sent
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        try {
            val lp = wifiNetwork()?.let { cm.getLinkProperties(it) }
            lp?.linkAddresses?.forEach { la ->
                val a = la.address
                if (a is Inet4Address) {
                    val ip = a.address
                    val prefix = la.prefixLength
                    val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
                    val ipInt = ((ip[0].toInt() and 0xFF) shl 24) or ((ip[1].toInt() and 0xFF) shl 16) or ((ip[2].toInt() and 0xFF) shl 8) or (ip[3].toInt() and 0xFF)
                    val b = ipInt or mask.inv()
                    out.add(InetAddress.getByAddress(byteArrayOf((b ushr 24).toByte(), (b ushr 16).toByte(), (b ushr 8).toByte(), b.toByte())))
                }
            }
        } catch (e: Exception) {
            // fall back to limited broadcast only
        }
        out.add(InetAddress.getByName("255.255.255.255"))
        return out
    }
}
