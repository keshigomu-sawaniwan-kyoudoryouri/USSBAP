package com.decent.usbaudio

import android.hardware.usb.UsbDeviceConnection

/**
 * Information about an opened USB audio device, ready for native I/O.
 */
data class UsbAudioPlaybackFormat(
    val altSetting: Int,
    val transportBitDepth: Int,
    val maxPacketSize: Int,
    val endpointInterval: Int
)

data class UsbAudioDeviceInfo(
    val connection: UsbDeviceConnection,
    val fd: Int,
    val deviceName: String,
    val interfaceId: Int,
    val endpointOutAddress: Int,
    val endpointFeedbackAddress: Int,
    val maxPacketSize: Int,
    val altSettingCount: Int,
    val clockSourceId: Int,
    val bestAltSetting: Int,
    val bestBitDepth: Int,
    /** bInterfaceProtocol from the selected AudioStreaming interface (0 = UAC1, 0x20 = UAC2). */
    val audioProtocol: Int = 0,
    /** Endpoint bInterval from the selected playback alternate setting. */
    val endpointInterval: Int = 1,
    /** UAC1 class-specific endpoint bmAttributes, if present. */
    val endpointControlAttributes: Int = 0,
    /** USB_SPEED_* enum returned by USBDEVFS_GET_SPEED. */
    val usbSpeed: Int = 0,
    val playbackFormats: List<UsbAudioPlaybackFormat> = emptyList()
)
