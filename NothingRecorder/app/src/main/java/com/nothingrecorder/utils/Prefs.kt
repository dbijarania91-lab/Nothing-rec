package com.nothingrecorder.utils

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("nr_prefs", Context.MODE_PRIVATE)

    var resolution: String
        get() = sp.getString("resolution", "1080p") ?: "1080p"
        set(v) = sp.edit().putString("resolution", v).apply()

    var fps: Int
        get() = sp.getInt("fps", 60)
        set(v) = sp.edit().putInt("fps", v).apply()

    // Bitrate in Mbps — 778G+ sweet spot defaults
    var bitrateMbps: Int
        get() = sp.getInt("bitrate", 35)
        set(v) = sp.edit().putInt("bitrate", v).apply()

    var internalAudio: Boolean
        get() = sp.getBoolean("internal_audio", true)
        set(v) = sp.edit().putBoolean("internal_audio", v).apply()

    var mic: Boolean
        get() = sp.getBoolean("mic", false)
        set(v) = sp.edit().putBoolean("mic", v).apply()

    var thermalGuard: Boolean
        get() = sp.getBoolean("thermal_guard", true)
        set(v) = sp.edit().putBoolean("thermal_guard", v).apply()
}
