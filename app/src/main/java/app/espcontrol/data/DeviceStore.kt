package app.espcontrol.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class PairedDevice(val id: String, val name: String, val type: Int)

/** Persists the user's authorized (paired) devices. Capabilities are re-read from the device on every connect. */
class DeviceStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("devices", Context.MODE_PRIVATE)

    fun load(): List<PairedDevice> = try {
        val arr = JSONArray(prefs.getString("list", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            PairedDevice(o.getString("id"), o.optString("name", ""), o.optInt("type", 0))
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun save(list: List<PairedDevice>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("type", it.type)) }
        prefs.edit().putString("list", arr.toString()).apply()
    }
}
