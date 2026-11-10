package app.espcontrol

import android.app.Application
import app.espcontrol.data.DeviceStore
import app.espcontrol.hub.DeviceHub
import app.espcontrol.security.OwnerKeyStore

class EspApp : Application() {
    lateinit var keys: OwnerKeyStore
        private set
    lateinit var hub: DeviceHub
        private set

    override fun onCreate() {
        super.onCreate()
        keys = OwnerKeyStore(this)
        hub = DeviceHub(this, keys, DeviceStore(this))
        hub.start()
    }
}
