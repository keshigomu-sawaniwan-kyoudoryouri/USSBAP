package com.jin.bitperfect

import android.content.Context

enum class UsbOutputMode {
    OFF,
    HAL_API,
    DIRECT_USB;

    companion object {
        fun load(context: Context): UsbOutputMode {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, HAL_API.name)
            return entries.firstOrNull { it.name == raw } ?: HAL_API
        }

        fun save(context: Context, mode: UsbOutputMode) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, mode.name).apply()
        }

        private const val PREFS = "BitPerfectPrefs"
        private const val KEY = "usb_output_mode"
    }
}
