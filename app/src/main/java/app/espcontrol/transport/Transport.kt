package app.espcontrol.transport

import app.espcontrol.core.RouteKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow

/** A raw datagram/notification received on one route. [source] is route-specific (ip:port, BLE address, "relay"). */
class Rx(val route: RouteKind, val bytes: ByteArray, val source: String)

interface Transport {
    val kind: RouteKind
    val incoming: SharedFlow<Rx>
    fun start(scope: CoroutineScope)
    fun stop()
}
