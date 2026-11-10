package app.espcontrol.transport

import app.espcontrol.core.RouteKind
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
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Internet UDP through the central relay. Same packet format as local UDP: the relay only needs the
 * plain header (device id) to route. A registration packet is refreshed every 15 s to keep NAT open.
 */
class RelayUdpTransport(
    host: String,
    port: Int,
    private val registration: () -> ByteArray?,
) : Transport {
    override val kind = RouteKind.RELAY_UDP
    private val flow = MutableSharedFlow<Rx>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val incoming: SharedFlow<Rx> = flow.asSharedFlow()

    @Volatile private var host: String = host
    @Volatile private var port: Int = port
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var server: InetSocketAddress? = null
    private var job: Job? = null
    private var scope: CoroutineScope? = null

    override fun start(scope: CoroutineScope) {
        this.scope = scope
        open()
    }

    fun restart() {
        job?.cancel()
        runCatching { socket?.close() }
        open()
    }

    fun configure(newHost: String, newPort: Int) {
        host = newHost.trim()
        port = newPort.coerceIn(1, 65535)
        restart()
    }

    override fun stop() {
        job?.cancel()
        runCatching { socket?.close() }
        socket = null
    }

    private fun open() {
        // Relay is intentionally disabled until a real server hostname is configured.
        if (host.isBlank() || host.endsWith(".example")) return
        val sc = scope ?: return
        job?.cancel()
        job = sc.launch(Dispatchers.IO) {
            while (currentCoroutineContext().isActive) {
                val s = try {
                    server = InetSocketAddress(InetAddress.getByName(host), port)
                    DatagramSocket()
                } catch (e: Exception) {
                    delay(5000); continue
                }
                socket = s
                val keepalive = launch {
                    while (isActive) {
                        sendRegistration()
                        delay(15_000)
                    }
                }
                val buf = ByteArray(2048)
                try {
                    while (currentCoroutineContext().isActive) {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        flow.tryEmit(Rx(kind, p.data.copyOf(p.length), "relay"))
                    }
                } catch (e: Exception) {
                    // closed or network lost
                } finally {
                    keepalive.cancel()
                    runCatching { s.close() }
                    if (socket === s) socket = null
                }
                delay(3000)
            }
        }
    }

    suspend fun sendRegistration() {
        val pkt = registration() ?: return
        send(pkt)
    }

    suspend fun send(bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val s = socket ?: return@withContext false
        val srv = server ?: return@withContext false
        try {
            s.send(DatagramPacket(bytes, bytes.size, srv))
            true
        } catch (e: Exception) {
            false
        }
    }
}
