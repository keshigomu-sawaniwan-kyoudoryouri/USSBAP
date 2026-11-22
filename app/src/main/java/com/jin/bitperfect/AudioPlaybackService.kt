package com.jin.bitperfect

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.media.AudioAttributes as PlatformAudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.AudioTrack
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes as Media3AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.decent.usbaudio.media3.UsbAudioSink
import com.decent.usbaudio.media3.UsbAudioSinkConfig
import com.decent.usbaudio.UsbAudioDevice
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

enum class PlaybackBackend { NONE, EXOPLAYER, FFMPEG }

enum class AlacPlaybackState {
    IDLE,
    PREPARING,
    PLAYING,
    PAUSED,
    STOPPING,
    ERROR
}

@UnstableApi
class AudioPlaybackService : MediaSessionService() {
    companion object {
        const val ACTION_REFRESH_USB_EXCLUSIVE = "com.jin.bitperfect.REFRESH_USB_EXCLUSIVE"
        const val ACTION_REFRESH_USB_VOLUME = "com.jin.bitperfect.REFRESH_USB_VOLUME"
        val usbSinkConfig = UsbAudioSinkConfig(bitPerfectEnabled = false, forceRouteToSpeaker = true)
    }
    private val streamingDsp = StreamingDspController()
    private var directUsbSink: UsbAudioSink? = null
    @Volatile private var mainIsPlaying = false
    private var highResAnalyzerThread: Thread? = null
    @Volatile private var highResAnalyzerStop = false
    private var highResAnalyzerSession = 0L
    private lateinit var systemAudioManager: AudioManager
    private var exclusiveFocusRequest: AudioFocusRequest? = null
    private var focusIsExclusive = false
    @Volatile private var resumeAfterTransientFocusLoss = false
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            mainHandler.postDelayed({ refreshUsbExclusiveOwnership() }, 250)
        }
    }

    private data class PcmSpec(val sampleRate: Int, val bitDepth: Int, val channels: Int)

    private fun probePcmSpec(inputPath: String): PcmSpec = try {
        val info = FFprobeKit.getMediaInformation(inputPath).mediaInformation
        val stream = info?.streams?.find { it.type == "audio" } ?: info?.streams?.firstOrNull()
        val rate = stream?.sampleRate?.toIntOrNull()?.takeIf { it in 8000..384000 } ?: 44100
        val rawBits = stream?.getStringProperty("bits_per_raw_sample")?.toIntOrNull()
            ?: stream?.getStringProperty("bits_per_sample")?.toIntOrNull() ?: 16
        val bits = when { rawBits > 24 -> 32; rawBits > 16 -> 24; else -> 16 }
        val channels = stream?.getNumberProperty("channels")?.toInt()?.coerceIn(1, 2) ?: 2
        PcmSpec(rate, bits, channels)
    } catch (t: Throwable) {
        AppLogger.w("FORMAT", "Probe failed; using 44.1k/16", t)
        PcmSpec(44100, 16, 2)
    }

    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    @Volatile private var currentBackend = PlaybackBackend.NONE
    
    // ALAC専用状態管理
    @Volatile private var alacState = AlacPlaybackState.IDLE
    @Volatile private var alacStopRequested = false
    @Volatile private var alacPaused = false
    @Volatile private var currentAlacSessionId = 0L
    
    private var alacPlaybackThread: Thread? = null
    @Volatile private var ffmpegPositionMs = 0L
    @Volatile private var ffmpegDurationMs = 0L
    private var currentAlacItem: MediaItem? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    // MediaSession は ExoPlayer イベント経由で状態をミラーする。ALAC 状態変化時に手動通知するため保持する。
    private val forwardingListeners = CopyOnWriteArrayList<Player.Listener>()

    override fun onCreate() {
        super.onCreate()
        AppLogger.i("SYSTEM", "Service Created. Independent ALAC State Engine.")
        cleanupTransientCache()
        try {
            val manager = getSystemService(android.app.NotificationManager::class.java)
            listOf(9990, 9991, 9992, 9993, 9994, 9998, 9999).forEach { manager.cancel(it) }
            listOf("bitperfect_debug", "bitperfect_spectrum_diag", "bitperfect_tap_diag", "bitperfect_player_error")
                .forEach { manager.deleteNotificationChannel(it) }
        } catch (_: Throwable) {}
        StreamingDspRuntime.load(this)
        try { YtStreamHelper.init(applicationContext) } catch (t: Throwable) { AppLogger.w("YT", "init in service failed", t) }
        StreamingDspRuntime.controller = streamingDsp
        // DIRECT_USB must install the raw UAC2 sink before ExoPlayer builds its
        // renderer. Otherwise the UI claims direct output while PCM uses only
        // the ordinary Android AudioTrack path.
        usbSinkConfig.bitPerfectEnabled = UsbOutputMode.load(this) == UsbOutputMode.DIRECT_USB
        usbSinkConfig.softwareVolumeEnabled = UsbVolumeControl.loadMode(this) == UsbVolumeControl.SOFTWARE
        usbSinkConfig.softwareVolume = UsbVolumeControl.loadLevel(this)
        usbSinkConfig.streamStateListener = { active, rate, depth, device ->
            BitPerfectStatus.isActive = active
            BitPerfectStatus.mode = "DIRECT USB"
            BitPerfectStatus.sampleRate = rate
            BitPerfectStatus.bitDepth = depth
            BitPerfectStatus.deviceName = device.orEmpty()
        }
        systemAudioManager = getSystemService(AudioManager::class.java)
        registerReceiver(usbReceiver, IntentFilter().apply { addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) })
        refreshUsbExclusiveOwnership()

        val prefs = getSharedPreferences("BitPerfectPrefs", MODE_PRIVATE)
        DauControl.userEnabled.set(prefs.getBoolean("stream_dau_enabled", true))
        DauControl.strength = prefs.getInt("stream_dau_strength", 68) / 100f
        DauControl.modelVersion = prefs.getInt("stream_dau_model", DauControl.MODEL_V2).coerceIn(DauControl.MODEL_V1, DauControl.MODEL_V2)
        val dauProcessor = TemporalDauAudioProcessor(applicationContext)

        val factory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
                val delegate = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(dauProcessor))
                    .build()
                // Keep the wrapper installed even when another output mode is
                // selected at service startup; the mode can change at runtime
                // without recreating ExoPlayer's audio renderer.
                return UsbAudioSink(delegate, context, usbSinkConfig).also { directUsbSink = it }
            }
        }.setMediaCodecSelector { m, _, _ ->
            if (currentBackend == PlaybackBackend.FFMPEG) emptyList() else MediaCodecSelector.DEFAULT.getDecoderInfos(m, false, false)
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        
        val routingDataSource = RoutingDataSourceFactory(this)
        val resolvingDataSource = ResolvingDataSource.Factory(routingDataSource) { dataSpec ->
            if (dataSpec.uri.scheme == "ytstream") {
                val videoId = dataSpec.uri.lastPathSegment ?: dataSpec.uri.host.orEmpty()
                val result = YtStreamHelper.resolveBlocking(videoId)
                val url = result.audioUrl
                    .ifEmpty { result.muxedUrl }
                    .ifEmpty { result.videoUrl }
                if (url.isBlank()) throw IllegalStateException("No playable stream URL for $videoId")
                dataSpec.withUri(Uri.parse(url))
            } else {
                dataSpec
            }
        }
        val defaultMediaSourceFactory =
            DefaultMediaSourceFactory(this).setDataSourceFactory(resolvingDataSource)
        val mediaSourceFactory = object : MediaSource.Factory {
            override fun setDrmSessionManagerProvider(
                provider: DrmSessionManagerProvider
            ): MediaSource.Factory {
                defaultMediaSourceFactory.setDrmSessionManagerProvider(provider)
                return this
            }

            override fun setLoadErrorHandlingPolicy(
                policy: LoadErrorHandlingPolicy
            ): MediaSource.Factory {
                defaultMediaSourceFactory.setLoadErrorHandlingPolicy(policy)
                return this
            }

            override fun getSupportedTypes(): IntArray = defaultMediaSourceFactory.supportedTypes

            override fun createMediaSource(mediaItem: MediaItem): MediaSource {
                if (mediaItem.mediaId == "DSD_FILE") {
                    val durationMs = mediaItem.mediaMetadata.extras?.getLong("duration") ?: 0L
                    return DsdMediaSource(mediaItem, durationMs * 1000L)
                }
                return defaultMediaSourceFactory.createMediaSource(mediaItem)
            }
        }
        val basePlayer = ExoPlayer.Builder(this, factory)
            .setMediaSourceFactory(mediaSourceFactory)
            // Hold the CPU and Wi-Fi radio only while ExoPlayer is actively
            // expected to play. This is essential for remote media on devices
            // with aggressive lock-screen/task-switch power management.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setAudioAttributes(Media3AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(), false)
            .build()
            
        // YouTube/MV video is intentionally unsupported. Disabling the video
        // track keeps muxed MP4 fallbacks audio-only and avoids waking a video
        // decoder in the background.
        basePlayer.trackSelectionParameters = basePlayer.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .build()
        exoPlayer = basePlayer
        directUsbSink?.attachToPlayer(basePlayer)

        val playerWrapper = object : ForwardingPlayer(basePlayer) {
            override fun addListener(listener: Player.Listener) {
                forwardingListeners.add(listener)
                super.addListener(listener)
            }

            override fun removeListener(listener: Player.Listener) {
                forwardingListeners.remove(listener)
                super.removeListener(listener)
            }

            override fun getPlaybackState(): Int {
                return if (currentBackend == PlaybackBackend.FFMPEG) {
                    when(alacState) {
                        AlacPlaybackState.IDLE -> Player.STATE_IDLE
                        AlacPlaybackState.PREPARING -> Player.STATE_BUFFERING
                        AlacPlaybackState.STOPPING -> Player.STATE_ENDED
                        else -> Player.STATE_READY
                    }
                } else super.getPlaybackState()
            }

            override fun getPlayWhenReady(): Boolean {
                return if (currentBackend == PlaybackBackend.FFMPEG) {
                    when (alacState) {
                        AlacPlaybackState.PLAYING, AlacPlaybackState.PREPARING -> true
                        else -> false
                    }
                } else super.getPlayWhenReady()
            }

            override fun isPlaying(): Boolean {
                return if (currentBackend == PlaybackBackend.FFMPEG) {
                    alacState == AlacPlaybackState.PLAYING
                } else {
                    super.isPlaying()
                }
            }

            override fun getCurrentPosition(): Long {
                return if (currentBackend == PlaybackBackend.FFMPEG) ffmpegPositionMs else super.getCurrentPosition()
            }

            override fun getDuration(): Long {
                return if (currentBackend == PlaybackBackend.FFMPEG) ffmpegDurationMs else super.getDuration()
            }

            override fun getPlayerError(): PlaybackException? {
                // DSF/DFF are intentionally handled outside ExoPlayer. The
                // platform parser may raise UnrecognizedInputFormatException
                // even though the native DSD engine is playing normally.
                return if (currentBackend == PlaybackBackend.FFMPEG) null else super.getPlayerError()
            }

            override fun play() {
                if (currentBackend == PlaybackBackend.FFMPEG) {
                    resumeAlacPlayback()
                } else {
                    super.play()
                }
            }

            override fun pause() {
                if (currentBackend == PlaybackBackend.FFMPEG) {
                    pauseAlacPlayback()
                } else {
                    super.pause()
                }
            }

            override fun seekTo(positionMs: Long) {
                if (currentBackend == PlaybackBackend.FFMPEG) {
                    seekAlacPlayback(positionMs)
                } else {
                    super.seekTo(positionMs)
                }
            }

            override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
                if (currentBackend == PlaybackBackend.FFMPEG) {
                    if (mediaItemIndex != currentMediaItemIndex) {
                        stopBypass()
                        super.seekTo(mediaItemIndex, positionMs)
                    } else {
                        seekAlacPlayback(positionMs)
                    }
                } else {
                    super.seekTo(mediaItemIndex, positionMs)
                }
            }

            override fun seekToNext() {
                if (currentBackend == PlaybackBackend.FFMPEG) stopBypass()
                super.seekToNext()
            }

            override fun seekToPrevious() {
                if (currentBackend == PlaybackBackend.FFMPEG) stopBypass()
                super.seekToPrevious()
            }

            override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
                val shuffleBefore = basePlayer.shuffleModeEnabled
                val repeatBefore = basePlayer.repeatMode
                val hasAlac = mediaItems.any { it.mediaId == "ALAC_FILE" }
                val hasDsd = mediaItems.any { it.mediaId == "DSD_FILE" }
                val hasDauMedia = mediaItems.any { it.mediaId == "YT_STREAM" || it.mediaId == "COMPRESSED_FILE" || it.mediaId == "DOWNLOADED_VIDEO" }
                val usbNow = try {
                    (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                        .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                        .any { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
                } catch (_: Exception) { false }
                if (hasDsd || (UsbOutputMode.load(this@AudioPlaybackService) == UsbOutputMode.HAL_API && (hasAlac || usbNow) && !(hasDauMedia && DauControl.userEnabled.get()))) {
                    currentBackend = PlaybackBackend.FFMPEG
                }
                // Select the PCM transport before ExoPlayer configures its
                // audio renderer. Compressed/DAU audio must use the normal USB
                // HAL; only local FLAC uses the raw UAC2 native engine.
                super.setMediaItems(mediaItems, startIndex, startPositionMs)
                if (repeatBefore != Player.REPEAT_MODE_OFF) super.setRepeatMode(repeatBefore)
                if (shuffleBefore) {
                    super.setShuffleModeEnabled(true)
                    if (mediaItems.isNotEmpty()) {
                        super.seekTo(startIndex.coerceIn(0, mediaItems.size - 1), startPositionMs)
                    }
                }
            }
        }

        // The system media card uses MediaSession's sessionActivity for a tap
        // on the card body. Without it some OEM SystemUI implementations leave
        // the card non-clickable even though transport buttons still work.
        val openPlayerIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPlayer = PendingIntent.getActivity(
            this,
            1001,
            openPlayerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        mediaSession = MediaSession.Builder(this, playerWrapper)
            .setSessionActivity(openPlayer)
            .build()
        setupListeners(basePlayer)
    }

    private fun setupListeners(p: ExoPlayer) {
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                mainIsPlaying = isPlaying
                if (isPlaying) {
                    if (UsbOutputMode.load(this@AudioPlaybackService) == UsbOutputMode.DIRECT_USB) requestExclusiveAudioFocus()
                    else requestStandardAudioFocus(p)
                }
            }
            override fun onMediaItemTransition(m: MediaItem?, r: Int) {
                stopHighResAnalyzer()
                if (m == null) return
                
                // ALAC_FILE: MediaCodecでデコード不可のため常にバイパス
                // YT_STREAM: リモートURLのためバイパスしない
                // USB DAC接続時: 全フォーマットをバイパスして排他(BIT-PERFECT)モードを試行
                val isAlac = m.mediaId == "ALAC_FILE"
                val isDsd = m.mediaId == "DSD_FILE"
                val isYtStream = m.mediaId == "YT_STREAM"
                val isCompressed = m.mediaId == "COMPRESSED_FILE" || m.mediaId == "DOWNLOADED_VIDEO"
                val isDauEligible = isYtStream || isCompressed
                DauControl.sourceProfile = m.mediaMetadata.extras?.getString("source_format") ?: if(isYtStream)"stream" else "aac"
                DauControl.streamingActive.set(isDauEligible)
                streamingDsp.setStreaming(isYtStream)
                val usbConnected = try {
                    (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                        .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                        .any { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
                } catch (_: Exception) { false }
                val usbMode = UsbOutputMode.load(this@AudioPlaybackService)
                val localUriText = m.localConfiguration?.uri?.toString()?.lowercase().orEmpty()
                val isFlacLocal = !isYtStream && localUriText.endsWith(".flac") &&
                    (localUriText.startsWith("file:") || localUriText.startsWith("content:"))
                // The raw UAC2 sink is only safe for local FLAC, where the
                // native engine knows the exact source depth and rate. Sending
                // compressed/DAU audio through the same raw stream can make the
                // DAC run at twice the intended rate on some UAC2 devices.
                // DIRECT_USB routes ExoPlayer PCM through UsbAudioSink.
                // DSD is always decoded to PCM via FFmpeg (direct DoP/Native
                // output was removed) and therefore never enters the raw path.
                usbSinkConfig.bitPerfectEnabled = usbMode == UsbOutputMode.DIRECT_USB
                val localFlacBypass = isFlacLocal && isHighResLocal(m.localConfiguration?.uri) &&
                    (usbMode == UsbOutputMode.HAL_API || usbMode == UsbOutputMode.OFF)
                val useBypass = (isDsd || isAlac || ((usbMode == UsbOutputMode.HAL_API) && usbConnected && !isYtStream) || localFlacBypass) &&
                    !(isCompressed && DauControl.userEnabled.get())
                AppLogger.i("PLAYER", ">>> Transition: ${m.mediaMetadata.title} (mode=$usbMode ALAC=$isAlac DSD=$isDsd YT=$isYtStream COMPRESSED=$isCompressed USB=$usbConnected bypass=$useBypass)")

                if (isDsd) {
                    // DSD is PCM-conversion only: FFmpeg decodes DSF/DFF to
                    // 176.4kHz PCM (direct DoP/Native output was removed).
                    currentBackend = PlaybackBackend.FFMPEG
                    ffmpegDurationMs = m.mediaMetadata.extras?.getLong("duration") ?: 0L
                    startBypassPlayback(m, forcePcmRate = 176_400)
                } else if (useBypass) {
                    currentBackend = PlaybackBackend.FFMPEG
                    ffmpegDurationMs = m.mediaMetadata.extras?.getLong("duration") ?: 180000
                    startBypassPlayback(m)
                } else {
                    currentBackend = PlaybackBackend.EXOPLAYER
                    currentAlacItem = null
                    BitPerfectStatus.isActive = false
                    stopBypass()
                    if (usbMode == UsbOutputMode.DIRECT_USB && isFlacLocal && isHighResLocal(m.localConfiguration?.uri)) {
                        startHighResAnalyzer(m)
                    }
                }
            }
            override fun onAudioSessionIdChanged(audioSessionId: Int) { streamingDsp.attach(audioSessionId) }
            override fun onPlayerError(error: PlaybackException) {
                val current = p.currentMediaItem
                if (current?.mediaId == "DSD_FILE") {
                    // DSF/DFF have no platform extractor. The actual audio is
                    // produced by the PCM-conversion bypass, so an ExoPlayer
                    // parser error for this media item must not surface as a
                    // user facing playback failure.
                    AppLogger.w("DSD", "ExoPlayer parser error ignored for DSD bypass: ${error.message}")
                    return
                }
                AppLogger.e("PLAYER", "Playback error ${error.errorCodeName}: ${error.message}", error)
                mainHandler.post {
                    val cause = error.cause?.let { " / ${it.javaClass.simpleName}: ${it.message}" }.orEmpty()
                    android.widget.Toast.makeText(
                        this@AudioPlaybackService,
                        "ERR ${error.errorCodeName}: ${error.message}$cause",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    AppLogger.e("PLAYER", "Playback cause: ${error.cause?.stackTraceToString()}", error.cause)
                }
                val item = p.currentMediaItem ?: return
                if (item.mediaId != "YT_STREAM") return
                val source = item.mediaMetadata.extras?.getString("source_url") ?: return
                val videoId = YtUrlParser.parse(source).videoId
                if (videoId.isNotEmpty()) StreamUrlCache.remove(this@AudioPlaybackService, videoId)
            }
        })
    }

    private fun requestExclusiveAudioFocus() {
        if (Build.VERSION.SDK_INT < 26) return
        if (!focusIsExclusive) {
            exclusiveFocusRequest?.let { systemAudioManager.abandonAudioFocusRequest(it) }
            exclusiveFocusRequest = null
        }
        val request = exclusiveFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(PlatformAudioAttributes.Builder().setUsage(PlatformAudioAttributes.USAGE_MEDIA).setContentType(PlatformAudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { change ->
                if (change < 0 && UsbOutputMode.load(this) == UsbOutputMode.DIRECT_USB) {
                    // USB exclusive mode owns the physical DAC. Do not pause or tear down
                    // the native stream when another application asks for focus.
                    mainHandler.postDelayed({ requestExclusiveAudioFocus() }, 120)
                }
            }
            .build().also { exclusiveFocusRequest = it; focusIsExclusive = true }
        systemAudioManager.requestAudioFocus(request)
    }

    private fun requestStandardAudioFocus(player: ExoPlayer) {
        if (Build.VERSION.SDK_INT < 26) return
        exclusiveFocusRequest?.let { systemAudioManager.abandonAudioFocusRequest(it) }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(PlatformAudioAttributes.Builder().setUsage(PlatformAudioAttributes.USAGE_MEDIA).setContentType(PlatformAudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { change ->
                mainHandler.post {
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS -> player.pause()
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            resumeAfterTransientFocusLoss = player.playWhenReady
                            player.pause()
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            if (resumeAfterTransientFocusLoss) {
                                resumeAfterTransientFocusLoss = false
                                player.play()
                            }
                        }
                    }
                }
            }
            .build()
        exclusiveFocusRequest = request
        focusIsExclusive = false
        systemAudioManager.requestAudioFocus(request)
    }

    private fun refreshUsbExclusiveOwnership() {
        val driver = UsbAudioDevice.getInstance(applicationContext)
        val device = driver.findUsbAudioDevice()
        val exclusive = UsbOutputMode.load(this) == UsbOutputMode.DIRECT_USB && device != null
        usbSinkConfig.bitPerfectEnabled = exclusive
        if (exclusive && driver.hasPermission(device!!)) {
            // Keep one claimed connection for both PCM and DSD. Opening it here
            // also ensures that a cold start already has a valid fd when the
            // first PCM buffer reaches UsbAudioSink.
            val info = driver.openDevice(device)
            if (info != null) {
                BitPerfectStatus.mode = "DIRECT USB"
                BitPerfectStatus.deviceName = info.deviceName
                requestExclusiveAudioFocus()
                AppLogger.i("USB_EXCLUSIVE", "DAC claimed for direct PCM/DSD: ${info.deviceName}")
            }
        } else if (!exclusive) {
            exclusiveFocusRequest?.let { if (Build.VERSION.SDK_INT >= 26) systemAudioManager.abandonAudioFocusRequest(it) }
            exclusiveFocusRequest = null
            focusIsExclusive = false
            if (!BitPerfectStatus.isActive) driver.closeDevice()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH_USB_EXCLUSIVE) refreshUsbExclusiveOwnership()
        if (intent?.action == ACTION_REFRESH_USB_VOLUME) directUsbSink?.refreshSoftwareVolume()
        super.onStartCommand(intent, flags, startId)
        // Keep the MediaSession owner around if the OEM temporarily detaches
        // every controller while showing Recents or the lock screen.
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = exoPlayer
        val isRemotePlayback = player?.currentMediaItem?.mediaId == "YT_STREAM"
        if (isRemotePlayback && (player.playWhenReady || player.playbackState == Player.STATE_BUFFERING)) {
            AppLogger.i("PLAYER", "Task removed while streaming; MediaSession retained")
            return
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun pauseAlacPlayback() {
        if (alacState == AlacPlaybackState.PLAYING) {
            AppLogger.i("ALAC_SYNC", "Action: PAUSE")
            alacPaused = true
            alacState = AlacPlaybackState.PAUSED
            // MediaSessionへ状態変更を通知（isPlayingの結果が変わる）
            invalidatePlayerState()
        }
    }

    private fun resumeAlacPlayback() {
        if (alacState == AlacPlaybackState.PAUSED) {
            AppLogger.i("ALAC_SYNC", "Action: RESUME")
            alacPaused = false
            alacState = AlacPlaybackState.PLAYING
            invalidatePlayerState()
        }
    }

    private fun seekAlacPlayback(positionMs: Long) {
        val item = currentAlacItem ?: return
        val clamped = positionMs.coerceAtLeast(0L).let { pos ->
            if (ffmpegDurationMs > 0L) pos.coerceAtMost(ffmpegDurationMs) else pos
        }
        val keepPaused = alacPaused || alacState == AlacPlaybackState.PAUSED
        AppLogger.i("ALAC_SYNC", "Action: SEEK to ${clamped}ms keepPaused=$keepPaused")
        // DSD is PCM-conversion only (176.4kHz), same as the initial playback.
        val dsdRate = if (item.mediaId == "DSD_FILE") 176_400 else null
        startBypassPlayback(item, startPositionMs = clamped, startPaused = keepPaused, forcePcmRate = dsdRate)
    }

    private fun invalidatePlayerState(
        seekDiscontinuity: Boolean = false,
        forcedState: Int? = null,
        forcedIsPlaying: Boolean? = null
    ) {
        // ExoPlayer state must be read on the main thread. The ALAC/bypass
        // thread calls this during cleanup, so do both the read and listener
        // delivery in one main-thread runnable.
        val notify = Runnable {
            val player = mediaSession?.player
            val capturedState = forcedState ?: player?.playbackState ?: Player.STATE_IDLE
            val capturedPlaying = forcedIsPlaying ?: (player?.isPlaying ?: false)
            AppLogger.i(
                "ALAC_SYNC",
                "invalidatePlayerState playing=$capturedPlaying state=$capturedState " +
                    "pos=$ffmpegPositionMs seek=$seekDiscontinuity listeners=${forwardingListeners.size}"
            )
            for (listener in forwardingListeners) {
                if (seekDiscontinuity) {
                    @Suppress("DEPRECATION")
                    listener.onPositionDiscontinuity(Player.DISCONTINUITY_REASON_SEEK)
                }
                listener.onPlayWhenReadyChanged(
                    capturedPlaying,
                    Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
                )
                listener.onPlaybackStateChanged(capturedState)
                listener.onIsPlayingChanged(capturedPlaying)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            notify.run()
        } else {
            mainHandler.post(notify)
        }
    }

    private fun startBypassPlayback(
        item: MediaItem,
        startPositionMs: Long = 0L,
        startPaused: Boolean = false,
        forcePcmRate: Int? = null
    ) {
        // 1. 以前のセッションを停止
        stopBypass()
        
        // 2. 新しいセッションの初期化
        currentAlacItem = item
        currentAlacSessionId = System.currentTimeMillis()
        val localSessionId = currentAlacSessionId
        val startOffsetMs = startPositionMs.coerceAtLeast(0L)
        alacStopRequested = false
        alacPaused = startPaused
        alacState = AlacPlaybackState.PREPARING
        ffmpegPositionMs = startOffsetMs
        invalidatePlayerState(seekDiscontinuity = startOffsetMs > 0L || startPaused)
        
        alacPlaybackThread = Thread {
            var audioTrack: AudioTrack? = null
            var pipePath: String? = null
            var pfd: ParcelFileDescriptor? = null
            var tempInput: File? = null
            var ffSession: FFmpegSession? = null
            var writeCount = 0
            var totalWritten = 0L
            var exitReason = "unknown"
            var loopError: Throwable? = null
            
            try {
                AppLogger.i(
                    "ALAC_START",
                    "localSessionId=$localSessionId, currentAlacSessionId=$currentAlacSessionId, " +
                        "alacState=$alacState, alacPaused=$alacPaused, " +
                        "alacStopRequested=$alacStopRequested, currentBackend=$currentBackend, " +
                        "startOffsetMs=$startOffsetMs"
                )
                
                // 3. FFmpeg Pipe
                pipePath = FFmpegKitConfig.registerNewFFmpegPipe(applicationContext)
                val uri = item.localConfiguration?.uri!!
                // FFmpegKit's SAF pseudo path is unreliable for FFmpeg input.
                // Copy the document to a real cache file first; it is removed
                // in cleanupAlac after the session ends.
                val localInput = File(cacheDir, "bypass_input_${localSessionId}.audio")
                contentResolver.openInputStream(uri)?.use { input ->
                    localInput.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
                } ?: error("Cannot open audio file")
                tempInput = localInput
                val inputPath = localInput.absolutePath
                val ssArg = if (startOffsetMs > 0L) {
                    "-ss ${startOffsetMs / 1000.0} "
                } else {
                    ""
                }
                
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val usb = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).find { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
                val sourceSpec = if (forcePcmRate != null) PcmSpec(forcePcmRate, 24, 2) else probePcmSpec(inputPath)
                val sourceEncoding = when (sourceSpec.bitDepth) { 24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED; 32 -> AudioFormat.ENCODING_PCM_32BIT; else -> AudioFormat.ENCODING_PCM_16BIT }
                val allAttrs = if (usb != null) try { am.getSupportedMixerAttributes(usb) } catch (_: Throwable) { emptyList() } else emptyList()
                val exactBp = allAttrs.firstOrNull { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT && it.format.sampleRate == sourceSpec.sampleRate && it.format.encoding == sourceEncoding }
                val exactAny = allAttrs.firstOrNull { it.format.sampleRate == sourceSpec.sampleRate && it.format.encoding == sourceEncoding }
                val selectedAttr = exactBp ?: exactAny ?: allAttrs.minByOrNull { kotlin.math.abs(it.format.sampleRate - sourceSpec.sampleRate) }
                val exactFormat = selectedAttr == null || (selectedAttr.format.sampleRate == sourceSpec.sampleRate && selectedAttr.format.encoding == sourceEncoding)
                val sr = forcePcmRate ?: selectedAttr?.format?.sampleRate ?: sourceSpec.sampleRate
                // Without a real USB mixer attribute we are going through the
                // Android system mixer. 24-bit packed AudioTrack output is not
                // reliably supported there on all devices and can crackle even
                // when frames are aligned, so use 16-bit for the system path.
                // A USB DAC that exposes a 24/32-bit mixer attribute keeps the
                // source depth.
                val selectedEncoding = selectedAttr?.format?.encoding?.takeIf {
                    it == AudioFormat.ENCODING_PCM_16BIT ||
                        it == AudioFormat.ENCODING_PCM_24BIT_PACKED ||
                        it == AudioFormat.ENCODING_PCM_32BIT
                }
                val enc = if (usb == null) AudioFormat.ENCODING_PCM_16BIT else (selectedEncoding ?: sourceEncoding)
                val bitDepth = when (enc) { AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24; AudioFormat.ENCODING_PCM_32BIT -> 32; else -> 16 }
                val ch = AudioFormat.CHANNEL_OUT_STEREO
                val rawFormat = when (bitDepth) { 24 -> "s24le"; 32 -> "s32le"; else -> "s16le" }
                val converted = forcePcmRate != null || !exactFormat || sourceSpec.channels != 2
                val rateArg = if (forcePcmRate != null) "-ar $forcePcmRate " else if (sr != sourceSpec.sampleRate) "-ar $sr " else ""
                ffSession = FFmpegKit.executeAsync("-y ${ssArg}-i $inputPath -vn -map_metadata -1 -f $rawFormat ${rateArg}-ac 2 $pipePath") { session ->
                    AppLogger.i("FFMPEG", "PCM ${sourceSpec.sampleRate}Hz/${sourceSpec.bitDepth}bit/${sourceSpec.channels}ch -> ${sr}Hz/${bitDepth}bit/2ch converted=$converted result=${session.returnCode}")
                }

                // 5. USB DACで排他(BIT-PERFECT)モードを要求
                var preferredSet = false
                if (usb != null) {
                    try {
                        // 全属性をログ出力(診断用)
                        val attrSummary = allAttrs.joinToString(" | ") { a ->
                            val b = if (a.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT) "BP" else "Mix"
                            "${a.format.sampleRate}Hz/" + when(a.format.encoding) {
                                AudioFormat.ENCODING_PCM_16BIT -> "16"
                                AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24"
                                AudioFormat.ENCODING_PCM_32BIT -> "32"
                                AudioFormat.ENCODING_PCM_FLOAT -> "FLT"
                                else -> "?"
                            } + "/$b"
                        }
                        AppLogger.i("BITPERFECT", "DAC [${usb.productName}] mixer: $attrSummary")

                        // BIT_PERFECT属性を検索
                        val bp = exactBp
                        if (bp != null) {
                            val rc = am.setPreferredMixerAttributes(
                                PlatformAudioAttributes.Builder().setUsage(PlatformAudioAttributes.USAGE_MEDIA).build(),
                                usb,
                                bp
                            )
                            preferredSet = rc
                            AppLogger.i("BITPERFECT", "BP ${sr}Hz/${bitDepth}bit rc=$rc success=$preferredSet")
                        } else {
                            // フォールバック: BIT_PERFECT未対応でも再生は継続する
                            val fallback = selectedAttr
                            if (fallback != null) {
                                val rc = am.setPreferredMixerAttributes(
                                    PlatformAudioAttributes.Builder().setUsage(PlatformAudioAttributes.USAGE_MEDIA).build(),
                                    usb,
                                    fallback
                                )
                                val fbBehavior = if (fallback.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT) "BP" else "MIXING"
                                AppLogger.i("BITPERFECT", "Fallback $fbBehavior rc=$rc")
                                preferredSet = fallback.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT && rc
                            } else {
                                AppLogger.w("BITPERFECT", "No mixer attributes for USB DAC")
                            }
                        }
                    } catch (e: Exception) {
                        AppLogger.w("BITPERFECT", "setPreferredMixerAttributes error", e)
                    }
                }
                BitPerfectStatus.sampleRate = sr
                BitPerfectStatus.bitDepth = bitDepth
                BitPerfectStatus.isActive = preferredSet && !converted
                BitPerfectStatus.mode = when { BitPerfectStatus.isActive -> "BIT-PERFECT VERIFIED"; selectedAttr != null -> "USB DIRECT"; usb != null -> "USB / SYSTEM FORMAT"; else -> "SYSTEM MIXER" }

                val trackBuilder = AudioTrack.Builder().setAudioAttributes(PlatformAudioAttributes.Builder().setUsage(PlatformAudioAttributes.USAGE_MEDIA).setContentType(PlatformAudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(sr).setChannelMask(ch).setEncoding(enc).build())
                    .setBufferSizeInBytes(AudioTrack.getMinBufferSize(sr, ch, enc).coerceAtLeast(4096) * 8).setTransferMode(AudioTrack.MODE_STREAM)

                audioTrack = trackBuilder.build()
                if (audioTrack.state != AudioTrack.STATE_INITIALIZED) {
                    exitReason = "audiotrack_init_failed"
                    AppLogger.e("ALAC_START", "AudioTrack Init Failed")
                    alacState = AlacPlaybackState.ERROR
                    return@Thread
                }

                // 6. 再生開始（シーク時に一時停止維持も可）
                if (startPaused) {
                    audioTrack.play()
                    audioTrack.pause()
                    alacPaused = true
                    alacState = AlacPlaybackState.PAUSED
                } else {
                    audioTrack.play()
                    alacState = AlacPlaybackState.PLAYING
                }
                invalidatePlayerState(seekDiscontinuity = startOffsetMs > 0L)
                AppLogger.i(
                    "ALAC_LOOP_ENTER",
                    "localSessionId=$localSessionId, currentAlacSessionId=$currentAlacSessionId, " +
                        "alacPaused=$alacPaused, alacStopRequested=$alacStopRequested, startOffsetMs=$startOffsetMs, " +
                        "preferred=$preferredSet"
                )

                FileInputStream(pipePath).use { stream ->
                    // PCM frames must stay byte-aligned. 8192-byte reads are
                    // not a multiple of a 6-byte 24-bit stereo frame, which
                    // made every buffer start mid-sample and produced constant
                    // crackle. Read into a frame-aligned accumulator and only
                    // hand complete frames to AudioTrack.
                    val frameBytes = ((bitDepth + 7) / 8) * 2
                    val buffer = ByteArray(frameBytes * 4096)
                    var filled = 0
                    var writeFailed = false
                    while (!alacStopRequested && localSessionId == currentAlacSessionId) {
                        if (alacPaused) {
                            if (audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING) audioTrack.pause()
                            Thread.sleep(10)
                            continue
                        } else {
                            if (audioTrack.playState == AudioTrack.PLAYSTATE_PAUSED) audioTrack.play()
                        }

                        val read = stream.read(buffer, filled, buffer.size - filled)
                        if (read < 0) {
                            exitReason = "pcm_eof_or_empty_read(read=$read)"
                            break
                        }
                        if (read == 0) continue
                        filled += read
                        val frames = filled / frameBytes
                        if (frames <= 0) continue
                        val chunkBytes = frames * frameBytes

                        if (writeCount == 0) {
                            analyzeBytes(buffer, chunkBytes, "ALAC_FIRST_PCM")
                        }
                        feedBypassSpectrum(buffer, chunkBytes, sr, bitDepth)

                        var offset = 0
                        while (offset < chunkBytes) {
                            val res = audioTrack.write(buffer, offset, chunkBytes - offset)
                            if (res < 0) {
                                exitReason = "audiowrite_error(res=$res)"
                                AppLogger.e("ALAC_LOOP", "Write Error: $res")
                                writeFailed = true
                                break
                            }
                            if (res == 0) {
                                Thread.sleep(1)
                                continue
                            }
                            offset += res
                            totalWritten += res
                            val bytesPerFrame = frameBytes.toLong()
                            ffmpegPositionMs = startOffsetMs + (totalWritten * 1000L) / (sr * bytesPerFrame)
                        }
                        if (writeCount == 0) {
                            AppLogger.i(
                                "ALAC_FIRST_WRITE",
                                "requested=$chunkBytes, writeResult=$offset, playState=${audioTrack.playState}"
                            )
                        }
                        if (writeFailed) break

                        val leftover = filled - chunkBytes
                        if (leftover > 0) System.arraycopy(buffer, chunkBytes, buffer, 0, leftover)
                        filled = leftover
                        writeCount++
                    }
                    if (exitReason == "unknown") {
                        exitReason = when {
                            alacStopRequested -> "stop_requested"
                            localSessionId != currentAlacSessionId -> "session_mismatch"
                            else -> "loop_condition_false"
                        }
                    }
                }
            } catch (e: Exception) {
                exitReason = "exception"
                loopError = e
                AppLogger.e("ALAC_LOOP", "Thread Error", e)
                alacState = AlacPlaybackState.ERROR
            } finally {
                cleanupAlac(
                    localSessionId,
                    audioTrack,
                    pipePath,
                    pfd,
                    tempInput,
                    ffSession,
                    writeCount,
                    totalWritten,
                    exitReason,
                    loopError
                )
            }
        }.apply { name = "AlacThread"; start() }
    }

    /**
     * Remove temp files left behind when a previous process was force-stopped
     * while decoding high-res audio. These files are regenerable and can each
     * be over a hundred megabytes (a 24/96 FLAC copy).
     */
    private fun cleanupTransientCache() {
        val dir = cacheDir ?: return
        var freed = 0L
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) return@forEach
            val name = file.name
            val transient = name.startsWith("bypass_input_") ||
                name.startsWith("analyzer_input_") ||
                name.startsWith("ffmpeg-pipe") ||
                name.startsWith("slm_migrate_") ||
                name.startsWith("secure_playback_") ||
                name.startsWith("yt_dlp_update_") ||
                name == "player_response.json" ||
                name == "ytm_search_response.json" ||
                name == "ytdlp_test.log"
            if (transient) {
                val size = file.length()
                if (file.delete()) freed += size
            }
        }
        if (freed > 0) AppLogger.i("CACHE", "Removed ${freed / 1024 / 1024}MB of stale audio cache")
    }

    private fun stopHighResAnalyzer() {
        highResAnalyzerStop = true
        highResAnalyzerThread?.join(800)
        highResAnalyzerThread = null
    }

    /**
     * DIRECT USB uses the native FLAC engine and therefore never passes PCM
     * through Media3's audio processors. Decode the same file a second time
     * (analysis only, no audio output) so 24/96 local playback still has a
     * live spectrum. This path is best-effort and never affects audio.
     */
    private fun startHighResAnalyzer(item: MediaItem) {
        val uri = item.localConfiguration?.uri ?: return
        val session = System.currentTimeMillis()
        highResAnalyzerSession = session
        highResAnalyzerStop = false
        highResAnalyzerThread = Thread {
            var pipe: String? = null
            var temp: File? = null
            var ff: FFmpegSession? = null
            try {
                val local = File(cacheDir, "analyzer_input_$session.audio")
                contentResolver.openInputStream(uri)?.use { input ->
                    local.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
                } ?: return@Thread
                temp = local
                val spec = probePcmSpec(local.absolutePath)
                pipe = FFmpegKitConfig.registerNewFFmpegPipe(applicationContext)
                ff = FFmpegKit.executeAsync("-y -i ${local.absolutePath} -vn -map_metadata -1 -f s16le -ac 2 $pipe") { }
                FileInputStream(pipe).use { stream ->
                    val buffer = ByteArray(8192)
                    while (!highResAnalyzerStop && session == highResAnalyzerSession) {
                        if (!mainIsPlaying) { Thread.sleep(60); continue }
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        val frames = read / 4
                        if (frames <= 0) continue
                        val source = ByteBuffer.wrap(buffer, 0, read).order(java.nio.ByteOrder.nativeOrder())
                        val left = FloatArray(frames)
                        val right = FloatArray(frames)
                        for (i in 0 until frames) {
                            left[i] = source.short.toFloat() / 32768f
                            right[i] = source.short.toFloat() / 32768f
                        }
                        SpectrumRuntime.offer(left, right, 0, frames, spec.sampleRate)
                    }
                }
            } catch (t: Throwable) {
                AppLogger.w("SPECTRUM", "Direct USB analyzer failed", t)
            } finally {
                ff?.cancel()
                try { pipe?.let { FFmpegKitConfig.closeFFmpegPipe(it) } } catch (_: Throwable) {}
                try { temp?.delete() } catch (_: Throwable) {}
            }
        }.apply { name = "DirectUsbSpectrum"; priority = Thread.MIN_PRIORITY; start() }
    }

    private fun isHighResLocal(uri: Uri?): Boolean {
        if (uri == null) return false
        val retriever = MediaMetadataRetriever()
        return try {
            contentResolver.openFileDescriptor(uri, "r")?.use { retriever.setDataSource(it.fileDescriptor) }
            val rate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 0
            val bits = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull() ?: 0
            rate > 48_000 || bits > 16
        } catch (_: Throwable) {
            false
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
        }
    }

    /** The FFmpeg bypass path writes straight to AudioTrack; mirror its PCM
     *  into the shared analyzer so hi-res local playback keeps a live spectrum. */
    private fun feedBypassSpectrum(buffer: ByteArray, size: Int, sampleRate: Int, bitDepth: Int) {
        val bytesPerSample = when (bitDepth) { 16 -> 2; 24 -> 3; 32 -> 4; else -> return }
        val frameBytes = bytesPerSample * 2
        val frames = size / frameBytes
        if (frames <= 0) return
        val source = ByteBuffer.wrap(buffer, 0, size).order(java.nio.ByteOrder.nativeOrder())
        val left = FloatArray(frames)
        val right = FloatArray(frames)
        for (i in 0 until frames) {
            left[i] = readPcmSample(source, bitDepth)
            right[i] = readPcmSample(source, bitDepth)
        }
        SpectrumRuntime.offer(left, right, 0, frames, sampleRate)
    }

    private fun readPcmSample(buffer: ByteBuffer, bitDepth: Int): Float = when (bitDepth) {
        16 -> buffer.short.toFloat() / 32768f
        24 -> {
            val b0 = buffer.get().toInt() and 0xff
            val b1 = buffer.get().toInt() and 0xff
            val b2 = buffer.get().toInt() and 0xff
            val unsigned = b0 or (b1 shl 8) or (b2 shl 16)
            val signed = if (unsigned and 0x800000 != 0) unsigned or -0x1000000 else unsigned
            signed / 8388608f
        }
        32 -> buffer.int.toFloat() / 2147483648f
        else -> 0f
    }

    private fun analyzeBytes(buffer: ByteArray, size: Int, tag: String) {
        var nz = 0; var peak = 0
        for (i in 0 until Math.min(size, 1024)) {
            val v = Math.abs(buffer[i].toInt())
            if (v != 0) nz++
            if (v > peak) peak = v
        }
        AppLogger.i(tag, "Size: $size, NonZero: $nz, Peak: $peak")
    }

    private fun cleanupAlac(
        sessionId: Long,
        track: AudioTrack?,
        pipe: String?,
        pfd: ParcelFileDescriptor?,
        tempInput: File?,
        ffSession: FFmpegSession?,
        writes: Int,
        bytes: Long,
        exitReason: String,
        error: Throwable?
    ) {
        val sessionMismatch = sessionId != currentAlacSessionId
        AppLogger.i(
            "ALAC_LOOP_EXIT",
            "reason=$exitReason, writeCount=$writes, totalWritten=$bytes, " +
                "stopRequested=$alacStopRequested, sessionMismatch=$sessionMismatch, " +
                "localSessionId=$sessionId, currentAlacSessionId=$currentAlacSessionId"
        )
        if (error != null) {
            AppLogger.e("ALAC_LOOP_EXIT", "exception detail", error)
        }
        
        // ローカルリソースの解放
        ffSession?.cancel()
        pipe?.let { FFmpegKitConfig.closeFFmpegPipe(it) }
        try { pfd?.close() } catch (_: Exception) {}
        try { tempInput?.delete() } catch (_: Exception) {}
        try { track?.stop(); track?.release() } catch (_: Exception) {}

        // USB DACのプリファードミキサー属性を解除(後続のExoPlayer再生が誤った形式で固定されないように)
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val usb = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .find { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
            if (usb != null) {
                am.clearPreferredMixerAttributes(
                    PlatformAudioAttributes.Builder().setUsage(PlatformAudioAttributes.USAGE_MEDIA).build(),
                    usb
                )
            }
        } catch (_: Exception) {}

        // セッションが一致する場合のみ、共有状態を戻す
        if (sessionId == currentAlacSessionId) {
            val naturalEnd = exitReason != "stop_requested" && exitReason != "session_mismatch"
            if (naturalEnd) {
                // 自然終了: 先に STATE_ENDED を通知 → Media3 が次曲送り実行
                invalidatePlayerState(forcedState = Player.STATE_ENDED, forcedIsPlaying = false)
            }
            alacState = AlacPlaybackState.IDLE
            BitPerfectStatus.isActive = false
            BitPerfectStatus.mode = "DIRECT"
            BitPerfectStatus.sampleRate = 0
            BitPerfectStatus.bitDepth = 0
            invalidatePlayerState()
        }
    }

    private fun stopBypass() {
        if (alacState != AlacPlaybackState.IDLE) {
            AppLogger.i("ALAC_SYNC", "Requesting Stop Bypass")
            alacStopRequested = true
            alacPlaybackThread?.join(1000)
            alacPlaybackThread = null
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession
    override fun onDestroy() { stopHighResAnalyzer(); try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}; exclusiveFocusRequest?.let { if (Build.VERSION.SDK_INT >= 26) systemAudioManager.abandonAudioFocusRequest(it) }; DauControl.streamingActive.set(false); streamingDsp.release(); StreamingDspRuntime.controller=null; stopBypass(); exoPlayer?.release(); mediaSession?.release(); UsbAudioDevice.getInstance(applicationContext).closeDevice(); super.onDestroy() }
}
