package app.espcontrol

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import app.espcontrol.ui.ControlViewModel
import app.espcontrol.ui.EspTheme
import app.espcontrol.ui.MainScreen

class MainActivity : ComponentActivity() {
    private val vm: ControlViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                (application as EspApp).hub.onPermissionsChanged()
            }
            LaunchedEffect(Unit) {
                val perms = if (Build.VERSION.SDK_INT >= 31) {
                    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                }
                launcher.launch(perms)
            }
            EspTheme { MainScreen(vm) }
        }
    }
}
