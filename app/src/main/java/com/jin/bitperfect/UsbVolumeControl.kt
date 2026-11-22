package com.jin.bitperfect

import android.content.Context

enum class UsbVolumeControl {
    DAC_HARDWARE,
    SOFTWARE;

    companion object {
        private const val PREFS = "BitPerfectPrefs"
        private const val MODE_KEY = "usb_volume_control"
        private const val LEVEL_KEY = "usb_software_volume_limited_v2"

        fun loadMode(context: Context): UsbVolumeControl =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(MODE_KEY, DAC_HARDWARE.name)
                ?.let { raw -> entries.firstOrNull { it.name == raw } }
                ?: DAC_HARDWARE

        fun loadLevel(context: Context): Float =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(LEVEL_KEY, 10).coerceIn(0, 100) / 100f

        fun save(context: Context, mode: UsbVolumeControl, level: Float) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(MODE_KEY, mode.name)
                .putInt(LEVEL_KEY, (level.coerceIn(0f, 1f) * 100).toInt())
                .apply()
        }
    }
}
