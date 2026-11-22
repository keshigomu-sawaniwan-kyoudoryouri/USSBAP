package com.jin.bitperfect

object BitPerfectStatus {
    @Volatile var isActive: Boolean = false
    @Volatile var mode: String = "DIRECT"
    @Volatile var sampleRate: Int = 0
    @Volatile var bitDepth: Int = 0
    @Volatile var deviceName: String = ""
    @Volatile var dsdTransport: String = ""
    @Volatile var dsdSourceRate: Int = 0
}

