package app.universal3d.inspector.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsState(
    val allowHttp: Boolean = false,
    val javascript: Boolean = true,
    val captureWebGl: Boolean = true,
    val maxTextureSize: Int = 2048,
    val backgroundIndex: Int = 0,
    val homeUrl: String = "https://sketchfab.com/feed",
    val lowMemoryMode: Boolean = false,
    val confirmLargeBytes: Long = 50L * 1024 * 1024,
)

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private fun load() = SettingsState(
        allowHttp = prefs.getBoolean("allowHttp", false),
        javascript = prefs.getBoolean("javascript", true),
        captureWebGl = prefs.getBoolean("captureWebGl", true),
        maxTextureSize = prefs.getInt("maxTextureSize", 2048),
        backgroundIndex = prefs.getInt("backgroundIndex", 0),
        homeUrl = prefs.getString("homeUrl", null) ?: "https://sketchfab.com/feed",
        lowMemoryMode = prefs.getBoolean("lowMemoryMode", false),
    )

    fun update(f: (SettingsState) -> SettingsState) {
        val n = f(_state.value)
        prefs.edit()
            .putBoolean("allowHttp", n.allowHttp).putBoolean("javascript", n.javascript).putBoolean("captureWebGl", n.captureWebGl)
            .putInt("maxTextureSize", n.maxTextureSize).putInt("backgroundIndex", n.backgroundIndex).putString("homeUrl", n.homeUrl)
            .putBoolean("lowMemoryMode", n.lowMemoryMode).apply()
        _state.value = n
    }
}
