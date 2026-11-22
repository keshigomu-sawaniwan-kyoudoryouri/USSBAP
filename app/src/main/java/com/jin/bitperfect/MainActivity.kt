package com.jin.bitperfect

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.hardware.usb.UsbManager
import androidx.appcompat.app.AppCompatDelegate
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.PopupMenu
import android.view.inputmethod.EditorInfo
import android.widget.Button
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.ActionBarDrawerToggle
import android.view.Gravity
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.Localization
import dev.ffmpegkit_maintained.ytdlp.YtDlp
import dev.ffmpegkit_maintained.ytdlp.YtDlpRequest
import com.chaquo.python.Python
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayout
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.decent.usbaudio.UsbAudioDevice
import com.decent.usbaudio.UsbAudioPermissionHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.io.File
import com.jin.bitperfect.BuildConfig
import kotlin.math.roundToInt

@UnstableApi
class MainActivity : AppCompatActivity() {

    private val LIBRARY_LOCAL = "ローカル"
    private val LIBRARY_DOWNLOADS = "ダウンロード"
    private val LOCAL_AUDIO_EXTENSIONS = listOf(".flac", ".wav", ".aac", ".m4a", ".mp3", ".dsf", ".dff", ".dsdiff")
    private val DSD_EXTENSIONS = setOf("dsf", "dff", "dsdiff")
    /** ローカル表示の絞り込み: null=選択前, false=PCMのみ, true=DSDのみ */
    private var libraryDsdOnly: Boolean? = null

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navView: NavigationView
    private lateinit var rvMusicList: RecyclerView
    private lateinit var logUiContainer: View
    private lateinit var tvAppLogs: TextView
    private lateinit var toolbar: Toolbar
    private lateinit var toolbarPageTitle: TextView
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var miniPlayer: View
    private lateinit var fullPlayerContainer: View
    private lateinit var fullSeekBar: SeekBar
    private lateinit var lyricsSheet: View
    private var lyricsDragStartY = 0f
    private lateinit var lyricsTitle: TextView
    private lateinit var lyricsStatus: TextView
    private lateinit var lyricsText: TextView
    private lateinit var lyricsScroll: ScrollView
    private lateinit var lyricsLines: LinearLayout
    private var lyricsVisible = false
    private var lastLyricsKey: String? = null
    private var syncedLyrics: List<LyricsLine> = emptyList()
    private var currentLyricLine = -1
    private var lastLyricsTouch = 0L
    private var suppressBackUntil = 0L
    private lateinit var homeContainer: View
    private lateinit var tvHomeTitle: TextView
    private lateinit var tvHomeArtist: TextView
    private lateinit var btnHomePlayPause: ImageButton
    private lateinit var loadingContainer: View
    private lateinit var tvFullPosition: TextView
    private lateinit var tvFullDuration: TextView
    private lateinit var tvFullFormat: TextView
    private lateinit var tvFullBitrate: TextView
    private lateinit var dacInfoContainer: View
    private lateinit var tvDacStatus: TextView
    private lateinit var tvDacList: TextView
    private lateinit var otherContainer: View
    private lateinit var playbackSettingsContainer: View
    private lateinit var switchDarkMode: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var tvAppVersion: TextView
    private lateinit var tvAppInfo: TextView
    private lateinit var playlistContainer: View
    private lateinit var rvPlaylistList: RecyclerView
    private lateinit var playlistAdapter: PlaylistListAdapter
    private val playlists = mutableListOf<Playlist>()
    private lateinit var rvHistoryList: RecyclerView
    private lateinit var historyAdapter: HistoryListAdapter
    private val historyEntries get() = StreamHistoryStore.load(this)
    private lateinit var libraryDB: LibraryDB
    private lateinit var searchContainer: View
    private lateinit var etSearchInput: EditText
    private lateinit var searchResultScroll: ScrollView
    private lateinit var searchResultList: LinearLayout
    private var searchFilter = listOf("videos")
    private var useYtMusicSearch = false
    private lateinit var bpIndicator: TextView
    private var logFilter: ((String) -> Boolean)? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controller: MediaController? get() = if (controllerFuture?.isDone == true) try { controllerFuture?.get() } catch (_: Exception) { null } else null
    private var albums = mutableListOf<AlbumItem>()
    private var currentAlbumSongs: List<MusicItem> = emptyList()
    private lateinit var musicAdapter: MusicListAdapter
    private var currentLevel = 0
    private data class TrackFormat(val codec: String, val sampleRate: Int, val channels: Int, val bitDepth: Int?, val bitrateKbps: Int)
    private val formatCache = ConcurrentHashMap<String, TrackFormat>()
    private val secureFormatCache = java.util.concurrent.ConcurrentHashMap<String, TrackFormat>()
    private val secureProbeStarted = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val localCodecCache = ConcurrentHashMap<String, String>()
    private var lastFormatUri: String? = null
    private var updatingUsbModeUi = false
    private var usbSoftwareVolumeSeek: SeekBar? = null
    private val searchImageExecutor = Executors.newFixedThreadPool(3)
    private val backgroundExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "MainBg").apply { isDaemon = true }
    }
    private var currentStreamSourceUrl: String? = null
    private var pendingSharedText: String? = null
    private var currentStreamResult: YtAudioResult? = null
    @Volatile private var securePlaybackRequestedAt = 0L
    @Volatile private var streamPlaybackRequestedAt = 0L
    private var trackedRecommendationKey="";private var trackedTitle="";private var trackedArtist="";private var trackedMaxPosition=0L;private var trackedDuration=0L
    private var downloadSort=0;private var downloadFilter=""
    private var btA2dp: BluetoothA2dp? = null
    private var btCodecText = ""
    private var btProxyRequested = false
    private var btPermissionRequested = false
    private var lastLogUiAt = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val updateProgressTask = object : Runnable {
        override fun run() {
            controller?.let { p ->
                val pos = p.currentPosition
                val title=p.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty();val artist=p.currentMediaItem?.mediaMetadata?.artist?.toString().orEmpty();val key="$title\u0000$artist";if(key!=trackedRecommendationKey){if(trackedRecommendationKey.isNotEmpty()&&trackedDuration>0)SearchRecommendationStore.recordCompletion(this@MainActivity,trackedTitle,trackedArtist,trackedMaxPosition.toFloat()/trackedDuration);trackedRecommendationKey=key;trackedTitle=title;trackedArtist=artist;trackedMaxPosition=0;trackedDuration=p.duration.coerceAtLeast(0)};trackedMaxPosition=maxOf(trackedMaxPosition,pos);if(p.duration>0)trackedDuration=p.duration
                if (p.isPlaying) fullSeekBar.progress = pos.toInt()
                tvFullPosition.text = formatTime(pos)
                p.currentMediaItem?.let { updateMiniProcessingBadge(it) }
            }
            updateBpIndicator()
            updateDauTelemetry()
            updateSignalDeck()
            updateUsbModeAvailability()
            handler.postDelayed(this, 1000)
        }
    }

    private val lyricsTicker = object : Runnable {
        override fun run() {
            if (!lyricsVisible || syncedLyrics.isEmpty()) return
            updateSyncedLyrics()
            handler.postDelayed(this, 120)
        }
    }

    private fun startLyricsTicker() {
        handler.removeCallbacks(lyricsTicker)
        handler.post(lyricsTicker)
    }

    private fun stopLyricsTicker() {
        handler.removeCallbacks(lyricsTicker)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val t0 = System.currentTimeMillis()
        CrashReporter.init(this)
        AppLogger.i("PERF", "[timing] CrashReporter.init: ${System.currentTimeMillis() - t0}ms")
        
        val t1 = System.currentTimeMillis()
        val darkMode = getSharedPreferences("BitPerfectPrefs", MODE_PRIVATE).getBoolean("dark_mode", true)
        AppCompatDelegate.setDefaultNightMode(if (darkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
        AppLogger.i("PERF", "[timing] Prefs+DarkMode: ${System.currentTimeMillis() - t1}ms")

        AppLogger.i("UI", "MainActivity onCreate")
        val t2 = System.currentTimeMillis()
        setContentView(R.layout.activity_main)
        if (UsbOutputMode.load(this) == UsbOutputMode.DIRECT_USB) UsbAudioPermissionHelper.handleIntent(this, intent)
        AppLogger.i("PERF", "[timing] setContentView: ${System.currentTimeMillis() - t2}ms")
        
        val t3 = System.currentTimeMillis()
        YtStreamHelper.init(this)
        SecureDownloadManager.migrateLegacyInBackground(this)
        AppLogger.i("PERF", "[timing] YtStreamHelper.init: ${System.currentTimeMillis() - t3}ms")
        
        val t3b = System.currentTimeMillis()
        try { YtDlp.init(applicationContext); AppLogger.i("UI", "YtDlp initialized") } catch (e: Exception) { AppLogger.w("UI", "YtDlp init failed", e) }
        AppLogger.i("PERF", "[timing] YtDlp.init: ${System.currentTimeMillis() - t3b}ms")
        
        val t3c = System.currentTimeMillis()
        try {
            val loc = Localization.fromLocale(if (Build.VERSION.SDK_INT >= 24) resources.configuration.locales[0] else resources.configuration.locale)
            NewPipe.init(OkHttpDownloader(), loc); AppLogger.i("UI", "NewPipe initialized with ${loc.localizationCode}")
        } catch (e: Exception) { NewPipe.init(OkHttpDownloader(), Localization.DEFAULT); AppLogger.w("UI", "NewPipe init failed, using DEFAULT", e) }
        AppLogger.i("PERF", "[timing] NewPipe.init: ${System.currentTimeMillis() - t3c}ms")
        
        val t4 = System.currentTimeMillis()
        playlists.addAll(PlaylistStore.load(this))
        AppLogger.i("PERF", "[timing] PlaylistStore.load: ${System.currentTimeMillis() - t4}ms")
        
        libraryDB = LibraryDB(this)
        val t4b = System.currentTimeMillis()
        val cachedItems = libraryDB.loadAll()
        if (cachedItems.isNotEmpty()) {
            val grouped = cachedItems.groupBy { it.album }
            albums.clear(); grouped.forEach { (name, songs) -> albums.add(AlbumItem(name, songs.sortedBy { it.trackNumber })) }
            albums.sortBy { it.name }
            AppLogger.i("PERF", "[timing] DB load ${cachedItems.size} files -> ${albums.size} albums: ${System.currentTimeMillis() - t4b}ms")
        }
        
        val t5 = System.currentTimeMillis()
        setupViews()
        AppLogger.i("PERF", "[timing] setupViews: ${System.currentTimeMillis() - t5}ms")
        
        val t6 = System.currentTimeMillis()
        showHome()
        AppLogger.i("PERF", "[timing] showHome: ${System.currentTimeMillis() - t6}ms")
        val prefs = getSharedPreferences("BitPerfectPrefs", MODE_PRIVATE)
        if (prefs.getBoolean("reopen_settings", false)) {
            prefs.edit().remove("reopen_settings").apply()
            showOther()
        }
        
        loadSavedFolderAndScan()
        handler.post(updateProgressTask); AppLogger.onLogUpdate = ::onLogUpdated
        handleIntent(intent); checkForCrash()
        
        AppLogger.i("PERF", "[timing] TOTAL onCreate: ${System.currentTimeMillis() - t0}ms")
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); if (UsbOutputMode.load(this) == UsbOutputMode.DIRECT_USB) UsbAudioPermissionHelper.handleIntent(this, intent); handleIntent(intent) }

    private fun requestDirectUsbPermission() {
        val driver = UsbAudioDevice.getInstance(applicationContext)
        val device = driver.findUsbAudioDevice() ?: run {
            Toast.makeText(this, "USB DACを接続してください", Toast.LENGTH_SHORT).show()
            return
        }
        if (driver.hasPermission(device)) {
            AppLogger.i("USB_DIRECT", "USB permission ready: ${device.productName}")
            startService(Intent(this, AudioPlaybackService::class.java).setAction(AudioPlaybackService.ACTION_REFRESH_USB_EXCLUSIVE))
        } else {
            driver.requestPermission(device) { granted ->
                runOnUiThread {
                    AppLogger.i("USB_DIRECT", "USB permission granted=$granted")
                    if (granted) startService(Intent(this@MainActivity, AudioPlaybackService::class.java).setAction(AudioPlaybackService.ACTION_REFRESH_USB_EXCLUSIVE))
                    Toast.makeText(this, if (granted) "DIRECT USB ready" else "USB access denied", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        if (controller == null) {
            pendingSharedText = sharedText
        } else {
            handleSharedText(sharedText)
        }
    }

    private fun handleSharedText(sharedText: String) {
        val parsed = YtUrlParser.parse(sharedText)
        when (parsed.type) {
            YtParsedUrl.Type.WATCH -> {
                val playSingle = {
                    Toast.makeText(this, getString(R.string.yt_share_received), Toast.LENGTH_SHORT).show()
                    playStreamUrl("https://www.youtube.com/watch?v=${parsed.videoId}")
                }
                val collectionId = parsed.playlistId
                if (collectionId.startsWith("RD")) {
                    AlertDialog.Builder(this)
                        .setTitle("ミックスリスト")
                        .setMessage("この曲を起点にしたミックスを再生しますか？")
                        .setPositiveButton("ミックスを再生") { _, _ -> importSharedMix(parsed.videoId, collectionId) }
                        .setNegativeButton("この曲だけ") { _, _ -> playSingle() }
                        .show()
                } else if (collectionId.startsWith("PL") || collectionId.startsWith("OLAK5uy_")) {
                    AlertDialog.Builder(this)
                        .setTitle("アルバム / プレイリスト")
                        .setMessage("この曲を含むアルバムまたはプレイリストをまとめて追加しますか？")
                        .setPositiveButton("まとめて追加") { _, _ -> importSharedPlaylist(collectionId) }
                        .setNegativeButton("この曲だけ") { _, _ -> playSingle() }
                        .show()
                } else {
                    playSingle()
                }
            }
            YtParsedUrl.Type.PLAYLIST -> importSharedPlaylist(parsed.playlistId)
            YtParsedUrl.Type.MIX -> importSharedMix(parsed.videoId, parsed.playlistId)
            YtParsedUrl.Type.BROWSE -> { showLoading(); YtStreamHelper.fetchBrowseItems(parsed.browseId, false, onSuccess = { ids -> runOnUiThread { hideLoading(); if (ids.isNotEmpty()) playQueueFromVideoIds(ids) else Toast.makeText(this, "No items", Toast.LENGTH_SHORT).show() } }, onError = { msg -> runOnUiThread { hideLoading(); Toast.makeText(this, msg, Toast.LENGTH_LONG).show() } }) }
            YtParsedUrl.Type.UNKNOWN -> Toast.makeText(this, getString(R.string.yt_url_unsupported), Toast.LENGTH_SHORT).show()
        }
    }

    private fun importSharedPlaylist(playlistId: String) {
        if (playlistId.isBlank()) return
        showLoading()
        YtStreamHelper.fetchPlaylist(playlistId, onSuccess = { info ->
            runOnUiThread { hideLoading(); addImportedPlaylist(info) }
        }, onError = { message ->
            runOnUiThread {
                hideLoading()
                Toast.makeText(this, "プレイリスト取得失敗: $message", Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun importSharedMix(seedVideoId: String, mixId: String) {
        if (seedVideoId.length != 11) {
            Toast.makeText(this, "ミックスの起点曲を特定できませんでした", Toast.LENGTH_LONG).show()
            return
        }
        showLoading()
        YtStreamHelper.fetchMix(seedVideoId, mixId, onSuccess = { info ->
            runOnUiThread { hideLoading(); addImportedPlaylist(info) }
        }, onError = { message ->
            runOnUiThread {
                hideLoading()
                Toast.makeText(this, "ミックス取得失敗: $message", Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun addImportedPlaylist(info: YtPlaylistInfo) {
        val songs = info.entries.mapIndexed { index, entry ->
            MusicItem(
                id = entry.videoId.hashCode().toLong(),
                title = entry.title,
                artist = entry.artist,
                album = entry.album.ifBlank { info.title },
                trackNumber = index + 1,
                duration = entry.durationSeconds * 1000L,
                contentUri = "https://www.youtube.com/watch?v=${entry.videoId}".toUri(),
                albumArtUri = entry.thumbnailUrl.takeIf { it.isNotBlank() }?.toUri(),
                format = "YT_STREAM"
            )
        }
        if (songs.isEmpty()) {
            Toast.makeText(this, "曲が見つかりませんでした", Toast.LENGTH_LONG).show()
            return
        }
        val name = uniquePlaylistName(info.title)
        val pl = Playlist(System.currentTimeMillis(), name, songs.toMutableList())
        playlists.add(0, pl)
        savePlaylists()
        refreshPlaylistList()
        currentAlbumSongs = pl.songs
        playMusicInContext(pl.songs[0])
        Toast.makeText(this, "「$name」に${songs.size}曲を追加しました", Toast.LENGTH_LONG).show()
    }

    private fun uniquePlaylistName(base: String): String {
        val clean = base.trim().ifBlank { "YouTube Playlist" }
        if (playlists.none { it.name == clean }) return clean
        var suffix = 2
        while (playlists.any { it.name == "$clean ($suffix)" }) suffix++
        return "$clean ($suffix)"
    }

    private fun checkForCrash() {
        if (!CrashReporter.hasCrash(this)) return
        val reportFile = CrashReporter.getLatestReport(this) ?: return
        val reportText = reportFile.readText(); val lines = reportText.split("\n")
        val time = lines.find { it.startsWith("202") } ?: ""
        val exception = lines.find { it.contains("Exception") } ?: ""
        val message = lines.find { it.contains("Message") } ?: ""
        AlertDialog.Builder(this).setTitle(getString(R.string.crash_title)).setMessage("日時: $time\n$exception\n$message")
            .setPositiveButton(getString(R.string.crash_view_report)) { _, _ -> startActivity(Intent(this, CrashReportActivity::class.java).apply { putExtra("report_path", reportFile.absolutePath) }) }
            .setNeutralButton(getString(R.string.crash_share)) { _, _ -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, reportText) }, "Share Crash Report")) }
            .setNegativeButton(getString(R.string.crash_delete)) { _, _ -> CrashReporter.deleteReports(this) }.show()
    }

    override fun onStart() { super.onStart(); val token = SessionToken(this, ComponentName(this, AudioPlaybackService::class.java)); controllerFuture = MediaController.Builder(this, token).buildAsync(); controllerFuture?.addListener({ updatePlayerUi(); updateHomeNowPlaying(); updateSignalDeck(); controller?.addListener(object : Player.Listener { override fun onEvents(p: Player, e: Player.Events) { updatePlayerUi(); updateHomeNowPlaying() }; override fun onIsPlayingChanged(playing:Boolean){if(playing&&securePlaybackRequestedAt>0){AppLogger.i("DL_PERF","tap-to-playing: ${android.os.SystemClock.elapsedRealtime()-securePlaybackRequestedAt}ms");securePlaybackRequestedAt=0};if(playing&&streamPlaybackRequestedAt>0){AppLogger.i("STREAM_PERF","tap-to-playing: ${android.os.SystemClock.elapsedRealtime()-streamPlaybackRequestedAt}ms");streamPlaybackRequestedAt=0}} }); AppLogger.i("PLAYER", "MediaController bound"); pendingSharedText?.let { text -> pendingSharedText = null; handleSharedText(text) } }, MoreExecutors.directExecutor()) }
    override fun onResume() { super.onResume(); updateSignalDeck(); updateYouTubeLoginStatus(); updateYtDlpStatus() }

    private fun setupViews() {
        toolbar = findViewById(R.id.toolbar); setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        toolbarPageTitle = findViewById(R.id.toolbarPageTitle)
        drawerLayout = findViewById(R.id.drawerLayout); navView = findViewById(R.id.navView)
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        toolbar.navigationIcon = null
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        findViewById<View>(R.id.btnTopPlaybackSettings).setOnClickListener { showPlaybackSettings() }
        findViewById<View>(R.id.btnTopSettings).setOnClickListener { showOther() }
        bottomNav = findViewById(R.id.bottomNav)
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.bottom_home -> showHome()
                R.id.bottom_search -> showSearch()
                R.id.bottom_library -> showAlbums()
                R.id.bottom_playlists -> showPlaylists()
                else -> return@setOnItemSelectedListener false
            }
            true
        }
        bpIndicator = TextView(this).apply { textSize = 10f; maxLines = 1; setPadding(0, 0, (8f * resources.displayMetrics.density + 0.5f).toInt(), 0) }
        toolbar.addView(bpIndicator, Toolbar.LayoutParams(Toolbar.LayoutParams.WRAP_CONTENT, Toolbar.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(54) }); updateBpIndicator()
        rvMusicList = findViewById(R.id.rvMusicList); logUiContainer = findViewById(R.id.logUiContainer); tvAppLogs = findViewById(R.id.tvAppLogs)
        val tabLogFilter = findViewById<TabLayout>(R.id.tabLogFilter)
        for (t in listOf("ALL", "BITPERFECT", "PLAYER", "UI")) tabLogFilter.addTab(tabLogFilter.newTab().setText(t))
        tabLogFilter.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { logFilter = when (tab.text?.toString()) { "ALL" -> null; "BITPERFECT" -> { { e -> e.contains("[BITPERFECT]") } }; "PLAYER" -> { { e -> e.contains("[PLAYER]") || e.contains("[ALAC_") || e.contains("[FFMPEG]") } }; "UI" -> { { e -> e.contains("[UI]") || e.contains("[SYSTEM]") } }; else -> null }; updateLogDisplay(AppLogger.getHistory()) }
            override fun onTabUnselected(t: TabLayout.Tab?) {}; override fun onTabReselected(t: TabLayout.Tab?) {}
        })
        rvMusicList.layoutManager = LinearLayoutManager(this)
        musicAdapter = MusicListAdapter(emptyList()) { item ->
            when (item) {
                is LibraryCategory -> when (item.action) {
                    "ALBUMS" -> showLocalAlbums()
                    "ARTISTS" -> showLocalArtists()
                    "ALL_SONGS" -> showAllLocalSongs()
                    "SCOPE_PCM" -> { libraryDsdOnly = false; showLocalLibrary() }
                    "SCOPE_DSD" -> { libraryDsdOnly = true; showLocalLibrary() }
                }
                is AlbumItem -> when {
                    item.name == LIBRARY_LOCAL -> showLocalLibrary()
                    item.name == LIBRARY_DOWNLOADS -> showDownloadedLibrary()
                    else -> showSongsInAlbum(item)
                }
                is ArtistItem -> showSongsByArtist(item)
                is MusicItem -> if (item.format.startsWith("SECURE_")) playSecureMusicItem(item) else playMusicInContext(item)
            }
        }
        musicAdapter.onMenuAction = { item, action -> when (action) { "NEXT" -> playNextInQueue(item); "ADD" -> addToQueue(item); "PLAYLIST" -> showAddToPlaylistDialog(item); "DELETE" -> confirmDeleteDownloads(listOf(item)) } }
        rvMusicList.adapter = musicAdapter
        val selectionBar=findViewById<View>(R.id.librarySelectionBar); val selectionText=findViewById<TextView>(R.id.tvDownloadSelection); val deleteSelected=findViewById<View>(R.id.btnDeleteSelectedDownloads)
        musicAdapter.onSelectionChanged={selected->selectionText.text=if(selected.isEmpty())"長押し、または全選択で複数削除" else "${selected.size}件を選択中";deleteSelected.isEnabled=selected.isNotEmpty()}
        findViewById<View>(R.id.btnSelectAllDownloads).setOnClickListener{musicAdapter.selectAllSecure()}
        findViewById<View>(R.id.btnFilterDownloads).setOnClickListener{showDownloadFilterDialog()}
        findViewById<View>(R.id.btnCancelDownloadSelection).setOnClickListener{musicAdapter.setSelectionMode(false)}
        deleteSelected.setOnClickListener{val selected=musicAdapter.selectedMusicItems();if(selected.isNotEmpty())confirmDeleteDownloads(selected)}
        playlistContainer = findViewById(R.id.playlistContainer); rvPlaylistList = findViewById(R.id.rvPlaylistList); rvPlaylistList.layoutManager = LinearLayoutManager(this)
        playlistAdapter = PlaylistListAdapter(playlists) { pl -> showPlaylistSongs(pl) }
        playlistAdapter.onMenuAction = { pl, action -> when (action) { "RENAME" -> showRenamePlaylistDialog(pl); "DELETE" -> deletePlaylist(pl) } }
        rvPlaylistList.adapter = playlistAdapter; findViewById<View>(R.id.btnCreatePlaylist).setOnClickListener { showCreatePlaylistDialog(null) }
        rvHistoryList = findViewById(R.id.rvHistoryList); rvHistoryList.layoutManager = LinearLayoutManager(this)
        historyAdapter = HistoryListAdapter(historyEntries, onItemClick = { entry -> playStreamUrl(entry.url, entry.title) }, onMenuAction = { entry, action -> showAddYtToPlaylistDialog(entry) })
        rvHistoryList.adapter = historyAdapter
        searchContainer = findViewById(R.id.searchContainer); etSearchInput = findViewById(R.id.etSearchInput)
        searchResultScroll = findViewById(R.id.searchResultScroll); searchResultList = findViewById(R.id.searchResultList)
        searchFilter = listOf("videos"); useYtMusicSearch = false
        findViewById<View>(R.id.btnDoSearch).setOnClickListener { doSearchTab() }
        etSearchInput.setOnEditorActionListener { _, actionId, _ -> if (actionId == EditorInfo.IME_ACTION_SEARCH) { doSearchTab(); true } else false }
        miniPlayer = findViewById(R.id.miniPlayer); miniPlayer.setOnClickListener { showFullPlayer() }
        val miniGesture=GestureDetector(this,object:GestureDetector.SimpleOnGestureListener(){override fun onDown(e:MotionEvent)=true;override fun onSingleTapUp(e:MotionEvent):Boolean{showFullPlayer();return true};override fun onFling(e1:MotionEvent?,e2:MotionEvent,vx:Float,vy:Float):Boolean{if(kotlin.math.abs(vx)>kotlin.math.abs(vy)&&kotlin.math.abs(vx)>500){if(vx<0)controller?.seekToNext()else controller?.seekToPrevious();return true};return false}});miniPlayer.setOnTouchListener{_,e->miniGesture.onTouchEvent(e)}
        findViewById<View>(R.id.btnMiniPlayPause).setOnClickListener { val p = controller ?: return@setOnClickListener; if (p.isPlaying) p.pause() else p.play() }
        fullPlayerContainer = findViewById(R.id.fullPlayerContainer); fullSeekBar = findViewById(R.id.fullSeekBar)
        lyricsSheet = findViewById(R.id.lyricsSheet)
        lyricsTitle = findViewById(R.id.tvLyricsTitle); lyricsStatus = findViewById(R.id.tvLyricsStatus)
        lyricsText = findViewById(R.id.tvLyricsText); lyricsScroll = findViewById(R.id.lyricsScroll)
        lyricsLines = findViewById(R.id.lyricsLines)
        lyricsScroll.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN ||
                event.actionMasked == MotionEvent.ACTION_MOVE ||
                event.actionMasked == MotionEvent.ACTION_UP) {
                lastLyricsTouch = SystemClock.elapsedRealtime()
            }
            false
        }
        findViewById<View>(R.id.tvLyricsHint).setOnClickListener { showLyricsSheet() }
        findViewById<View>(R.id.btnLyricsClose).setOnClickListener { hideLyricsSheet() }
        // Keep the full-player show/hide fling from reacting to gestures that
        // originate on the lyrics sheet; the sheet has its own close handle.
        lyricsSheet.setOnTouchListener { _, _ -> true }
        findViewById<View>(R.id.lyricsHandle).setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lyricsDragStartY = event.rawY; true }
                MotionEvent.ACTION_UP -> { if (event.rawY - lyricsDragStartY > dp(60)) hideLyricsSheet(); true }
                else -> true
            }
        }
        homeContainer = findViewById(R.id.homeContainer); tvHomeTitle = findViewById(R.id.tvHomeTitle); tvHomeArtist = findViewById(R.id.tvHomeArtist)
        btnHomePlayPause = findViewById(R.id.btnHomePlayPause); loadingContainer = findViewById(R.id.loadingContainer)
        tvFullPosition = findViewById(R.id.tvFullPosition); tvFullDuration = findViewById(R.id.tvFullDuration)
        tvFullFormat = findViewById(R.id.tvFullFormat); tvFullBitrate = findViewById(R.id.tvFullBitrate)
        dacInfoContainer = findViewById(R.id.dacInfoContainer); tvDacStatus = findViewById(R.id.tvDacStatus); tvDacList = findViewById(R.id.tvDacList)
        otherContainer = findViewById(R.id.otherContainer); playbackSettingsContainer = findViewById(R.id.playbackSettingsContainer); switchDarkMode = findViewById(R.id.switchDarkMode); tvAppVersion = findViewById(R.id.tvAppVersion); tvAppInfo = findViewById(R.id.tvAppInfo)
        findViewById<View>(R.id.btnApplicationLog).let { if (!BuildConfig.DEBUG) it.visibility = View.GONE else it.setOnClickListener { showLogsUi() } }
        findViewById<View>(R.id.btnTestYtDlp).let { if (!BuildConfig.DEBUG) it.visibility = View.GONE else it.setOnClickListener { testYtDlp() } }
        findViewById<View>(R.id.btnYouTubeLogin).setOnClickListener { startActivity(Intent(this, YouTubeLoginActivity::class.java)) }
        findViewById<View>(R.id.btnYouTubeCookieManual).setOnClickListener { showManualCookieDialog() }
        findViewById<View>(R.id.btnYouTubeLogout).setOnClickListener {
            YtStreamHelper.clearCookies(this)
            updateYouTubeLoginStatus()
            Toast.makeText(this, getString(R.string.youtube_login_status_logged_out), Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.btnYtDlpUpdate).setOnClickListener { updateYtDlp() }
        findViewById<View>(R.id.btnYtDlpReset).setOnClickListener {
            YtDlpUpdater.reset(this)
            updateYtDlpStatus()
            Toast.makeText(this, getString(R.string.ytdlp_reverted), Toast.LENGTH_SHORT).show()
        }
        updateYouTubeLoginStatus()
        updateYtDlpStatus()
        findViewById<View>(R.id.btnDacScan).setOnClickListener { refreshDacInfo() }
        findViewById<View>(R.id.btnSelectFolder).setOnClickListener { selectFolderLauncher.launch(null) }
        findViewById<View>(R.id.btnLanguage).setOnClickListener { showLanguageDialog() }
        findViewById<View>(R.id.btnDownloadQuality).setOnClickListener { showDownloadQualityDialog() }
        // YouTube handling is audio-only now; downloads no longer offer video.
        findViewById<View>(R.id.btnDownloadType).visibility = View.GONE
        findViewById<View>(R.id.btnDownloadMobile).setOnClickListener { showDownloadMobileDialog() }
        findViewById<View>(R.id.btnManageDownloads).setOnClickListener { showManagedDownloads() }
        findViewById<View>(R.id.btnDownloadQueue).setOnClickListener { showDownloadQueueControl() }
        findViewById<View>(R.id.btnCredits).setOnClickListener { showCreditsDialog() }
        findViewById<View>(R.id.btnLicenses).setOnClickListener { showLicensesDialog() }
        // Hide debug section in Release
        if (!BuildConfig.DEBUG) {
            findViewById<View>(R.id.debugDivider).visibility = View.GONE
            findViewById<View>(R.id.debugHeader).visibility = View.GONE
            findViewById<View>(R.id.btnApplicationLog).visibility = View.GONE
            findViewById<View>(R.id.btnTestYtDlp).visibility = View.GONE
        }
        switchDarkMode.isChecked = getSharedPreferences("BitPerfectPrefs", MODE_PRIVATE).getBoolean("dark_mode", true)
        switchDarkMode.setOnCheckedChangeListener { _, checked ->
            getSharedPreferences("BitPerfectPrefs", MODE_PRIVATE).edit {
                putBoolean("dark_mode", checked)
                putBoolean("reopen_settings", true)
            }
            AppCompatDelegate.setDefaultNightMode(if (checked) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
        }
        val usbModeGroup = findViewById<android.widget.RadioGroup>(R.id.radioUsbOutputMode)
        val usbModeStatus = findViewById<TextView>(R.id.tvUsbModeStatus)
        updatingUsbModeUi = true
        fun refreshUsbMode(mode: UsbOutputMode) {
            usbModeStatus.text = when (mode) {
                UsbOutputMode.DIRECT_USB -> "ACTIVE ROUTE: DIRECT UAC2 · ANDROID MIXER BYPASS"
                UsbOutputMode.HAL_API -> "ACTIVE ROUTE: ANDROID USB HAL · LOSSLESS API"
                UsbOutputMode.OFF -> "ACTIVE ROUTE: SYSTEM MIXER"
            }
        }
        // Do not overwrite the user's saved route just because USB enumeration
        // has not completed yet at cold start. The saved route is restored as
        // soon as the DAC appears.
        val initialUsbMode = if (hasUsbAudioOutput()) UsbOutputMode.load(this) else UsbOutputMode.OFF
        when (initialUsbMode) {
            UsbOutputMode.DIRECT_USB -> usbModeGroup.check(R.id.radioUsbDirect)
            UsbOutputMode.HAL_API -> usbModeGroup.check(R.id.radioUsbHal)
            UsbOutputMode.OFF -> usbModeGroup.check(R.id.radioUsbOff)
        }
        refreshUsbMode(initialUsbMode)
        updatingUsbModeUi = false
        updateUsbModeAvailability()
        usbModeGroup.setOnCheckedChangeListener { _, checkedId ->
            if (updatingUsbModeUi) return@setOnCheckedChangeListener
            val mode = when (checkedId) {
                R.id.radioUsbDirect -> UsbOutputMode.DIRECT_USB
                R.id.radioUsbHal -> UsbOutputMode.HAL_API
                else -> UsbOutputMode.OFF
            }
            UsbOutputMode.save(this, mode)
            AudioPlaybackService.usbSinkConfig.bitPerfectEnabled = mode == UsbOutputMode.DIRECT_USB
            startService(Intent(this, AudioPlaybackService::class.java).setAction(AudioPlaybackService.ACTION_REFRESH_USB_EXCLUSIVE))
            refreshUsbMode(mode)
            if (mode == UsbOutputMode.DIRECT_USB) requestDirectUsbPermission()
            controller?.let { p ->
                if (p.mediaItemCount > 0) {
                    val wasPlaying = p.playWhenReady
                    val pos = p.currentPosition
                    p.stop(); p.prepare(); p.seekTo(pos); p.playWhenReady = wasPlaying
                }
            }
            Toast.makeText(this, "USB output: ${mode.name.replace('_', ' ')}", Toast.LENGTH_SHORT).show()
        }

        val volumeModeGroup = findViewById<android.widget.RadioGroup>(R.id.radioUsbVolumeMode)
        val softwareVolumeSeek = findViewById<SeekBar>(R.id.seekUsbSoftwareVolume)
        usbSoftwareVolumeSeek = softwareVolumeSeek
        val softwareVolumeLabel = findViewById<TextView>(R.id.tvUsbSoftwareVolume)
        var selectedVolumeMode = UsbVolumeControl.loadMode(this)
        var softwareVolumeLevel = UsbVolumeControl.loadLevel(this)
        fun updateUsbVolumeUi() {
            AudioPlaybackService.usbSinkConfig.softwareVolumeEnabled = selectedVolumeMode == UsbVolumeControl.SOFTWARE
            AudioPlaybackService.usbSinkConfig.softwareVolume = softwareVolumeLevel
            softwareVolumeSeek.isEnabled = selectedVolumeMode == UsbVolumeControl.SOFTWARE
            softwareVolumeSeek.alpha = if (softwareVolumeSeek.isEnabled) 1f else 0.45f
            val sliderPercent = (softwareVolumeLevel * 100).roundToInt()
            val outputPercent = (softwareVolumeLevel * 10).roundToInt()
            softwareVolumeLabel.text = "アプリ音量 $sliderPercent% · DAC最大出力の約$outputPercent%"
            UsbVolumeControl.save(this, selectedVolumeMode, softwareVolumeLevel)
            startService(Intent(this, AudioPlaybackService::class.java).setAction(AudioPlaybackService.ACTION_REFRESH_USB_VOLUME))
        }
        volumeModeGroup.check(if (selectedVolumeMode == UsbVolumeControl.SOFTWARE) R.id.radioUsbSoftwareVolume else R.id.radioUsbHardwareVolume)
        softwareVolumeSeek.progress = (softwareVolumeLevel * 100).roundToInt()
        softwareVolumeLabel.text = "アプリ音量 ${(softwareVolumeLevel * 100).roundToInt()}% · DAC最大出力の約${(softwareVolumeLevel * 10).roundToInt()}%"
        softwareVolumeSeek.isEnabled = selectedVolumeMode == UsbVolumeControl.SOFTWARE
        softwareVolumeSeek.alpha = if (softwareVolumeSeek.isEnabled) 1f else 0.45f
        volumeModeGroup.setOnCheckedChangeListener { _, checkedId ->
            selectedVolumeMode = if (checkedId == R.id.radioUsbSoftwareVolume) UsbVolumeControl.SOFTWARE else UsbVolumeControl.DAC_HARDWARE
            updateUsbVolumeUi()
        }
        softwareVolumeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                softwareVolumeLevel = progress / 100f
                updateUsbVolumeUi()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val dauPrefs = getSharedPreferences("BitPerfectPrefs", MODE_PRIVATE)
        val dauSwitch = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchStreamDau)
        val dauSeek = findViewById<SeekBar>(R.id.seekDauStrength)
        val dauStrength = findViewById<TextView>(R.id.tvDauStrength)
        val dauStatus = findViewById<TextView>(R.id.tvDauStatus)
        val dauModel = findViewById<android.widget.Spinner>(R.id.spinnerDauModel)
        val processingMode = findViewById<android.widget.Spinner>(R.id.spinnerProcessingMode)
        val dauAuto = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchDauAutoStrength)
        StreamingDspRuntime.load(this)
        val dspSwitch = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchStreamDsp)
        val dspPreset = findViewById<android.widget.Spinner>(R.id.spinnerDspPreset)
        val dspBass = findViewById<SeekBar>(R.id.seekDspBass)
        val dspCompressor = findViewById<SeekBar>(R.id.seekDspCompressor)
        val dspStatus = findViewById<TextView>(R.id.tvDspStatus)
        val eqGraph = findViewById<DspEqGraphView>(R.id.eqGraph)
        val presetNames = listOf("FLAT", "BASS", "VOCAL", "BASS + VOCAL", "V-SHAPE", "CLARITY")
        processingMode.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,listOf("OFF","AI DAU","DSP / EQ"))
        dspPreset.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, presetNames)
        var initializingDsp = true
        fun refreshDspUi() { val s=StreamingDspRuntime.state; dspSwitch.isChecked=s.enabled; if(s.preset>=0)dspPreset.setSelection(s.preset); dspBass.progress=(s.bass*100).toInt(); dspCompressor.progress=(s.compressor*100).toInt(); eqGraph.setGains(s.gains); listOf<View>(dspPreset,dspBass,dspCompressor,eqGraph).forEach{it.isEnabled=s.enabled;it.alpha=if(s.enabled)1f else .38f}; dspStatus.text="${StreamingDspRuntime.backend} · STREAMING ONLY" }
        dauSwitch.isChecked = dauPrefs.getBoolean("stream_dau_enabled", true)
        dauSeek.progress = dauPrefs.getInt("stream_dau_strength", 68)
        DauControl.modelVersion = dauPrefs.getInt("stream_dau_model", DauControl.MODEL_V2).coerceIn(DauControl.MODEL_V1, DauControl.MODEL_V2)
        dauModel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("DAU 1.0.1 · CLASSIC (NON-AI)", "AI DAU · TEMPORAL 1.5M"))
        dauModel.setSelection(DauControl.modelVersion - 1)
        DauControl.userEnabled.set(dauSwitch.isChecked); DauControl.strength = dauSeek.progress / 100f
        DauControl.autoStrength=dauPrefs.getBoolean("dau_auto_strength",true);dauAuto.isChecked=DauControl.autoStrength
        fun refreshDauUi() { val enabled=dauSwitch.isChecked; listOf<View>(dauModel,dauSeek,dauAuto).forEach{it.isEnabled=enabled;it.alpha=if(enabled)1f else .38f};dauStrength.alpha=if(enabled)1f else .38f; dauStrength.text = if(DauControl.autoStrength)"AUTO · ${dauSeek.progress}% max" else "${dauSeek.progress}%"; dauStatus.text = "${DauControl.backend}  ·  ${DauControl.sourceProfile.uppercase()}  ·  ${if (DauControl.streamingActive.get()) if (DauControl.active) "ACTIVE ${"%.0f".format(DauControl.effectiveStrength*100)}%" else "BYPASS" else "STREAM / COMPRESSED STANDBY"}" }
        dauModel.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val version = position + 1
                if (DauControl.modelVersion != version) {
                    DauControl.modelVersion = version
                    DauControl.inferenceActive = false
                    DauControl.backend = "${DauControl.modelLabel} / SWITCHING"
                    dauPrefs.edit { putInt("stream_dau_model", version) }
                    refreshDauUi()
                }
            }
        }
        dauSwitch.setOnCheckedChangeListener { _, checked -> DauControl.userEnabled.set(checked); dauPrefs.edit { putBoolean("stream_dau_enabled", checked) }; if(checked && StreamingDspRuntime.state.enabled){StreamingDspRuntime.state.enabled=false;StreamingDspRuntime.save(this);dspSwitch.isChecked=false}; refreshDauUi();refreshDspUi() }
        dauAuto.setOnCheckedChangeListener{_,checked->DauControl.autoStrength=checked;dauPrefs.edit{putBoolean("dau_auto_strength",checked)};refreshDauUi()}
        dauSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(s: SeekBar?, value: Int, fromUser: Boolean) { DauControl.strength = value / 100f; dauStrength.text = "$value%"; if (fromUser) dauPrefs.edit { putInt("stream_dau_strength", value) } }; override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) {} })
        refreshDauUi()
        refreshDspUi();initializingDsp=false
        dspSwitch.setOnCheckedChangeListener { _, checked -> if(initializingDsp)return@setOnCheckedChangeListener;StreamingDspRuntime.state.enabled=checked;if(checked){dauSwitch.isChecked=false};StreamingDspRuntime.save(this);refreshDspUi();refreshDauUi() }
        var initializingMode=true;processingMode.setSelection(if(StreamingDspRuntime.state.enabled)2 else if(dauSwitch.isChecked)1 else 0);initializingMode=false
        processingMode.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener{override fun onNothingSelected(p:android.widget.AdapterView<*>?){};override fun onItemSelected(p:android.widget.AdapterView<*>?,v:View?,position:Int,id:Long){if(initializingMode)return;when(position){0->{dauSwitch.isChecked=false;dspSwitch.isChecked=false};1->{dspSwitch.isChecked=false;dauSwitch.isChecked=true};2->{dauSwitch.isChecked=false;dspSwitch.isChecked=true}}}}
        dspPreset.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener{override fun onNothingSelected(p:android.widget.AdapterView<*>?){};override fun onItemSelected(p:android.widget.AdapterView<*>?,v:View?,position:Int,id:Long){if(initializingDsp)return;StreamingDspRuntime.state.applyPreset(position);StreamingDspRuntime.save(this@MainActivity);initializingDsp=true;refreshDspUi();initializingDsp=false}}
        fun dspSeek(action:(Int)->Unit)=object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(s:SeekBar?,v:Int,user:Boolean){if(user){action(v);StreamingDspRuntime.save(this@MainActivity);dspStatus.text=StreamingDspRuntime.backend}};override fun onStartTrackingTouch(s:SeekBar?){};override fun onStopTrackingTouch(s:SeekBar?){}}
        eqGraph.onGainChanged = { index, gain -> StreamingDspRuntime.state.gains[index]=gain;StreamingDspRuntime.state.preset=-1;StreamingDspRuntime.save(this@MainActivity);dspStatus.text="${listOf("60 Hz","250 Hz","1 kHz","4 kHz","12 kHz")[index]}  ${if(gain>=0) "+" else ""}${"%.1f".format(gain)} dB" }
        dspBass.setOnSeekBarChangeListener(dspSeek{StreamingDspRuntime.state.bass=it/100f})
        dspCompressor.setOnSeekBarChangeListener(dspSeek{StreamingDspRuntime.state.compressor=it/100f})
        findViewById<View>(R.id.btnHomeLibrary).setOnClickListener { showAlbums() }; findViewById<View>(R.id.btnHomePlaylists).setOnClickListener { showPlaylists() }
        findViewById<View>(R.id.btnHomeStreamUrl).setOnClickListener { showSearch() }; findViewById<View>(R.id.btnHomeHistory).setOnClickListener { showHistory() }
        findViewById<View>(R.id.btnHomePrev).setOnClickListener { controller?.seekToPrevious() }
        btnHomePlayPause.setOnClickListener { val p = controller ?: return@setOnClickListener; if (p.isPlaying) p.pause() else p.play() }
        findViewById<View>(R.id.btnHomeNext).setOnClickListener { controller?.seekToNext() }
        findViewById<View>(R.id.btnCollapse).setOnClickListener { hideFullPlayer() }
        findViewById<View>(R.id.btnFullPlayPause).setOnClickListener { val p = controller ?: return@setOnClickListener; if (p.isPlaying) p.pause() else p.play() }
        findViewById<View>(R.id.btnNext).setOnClickListener { controller?.seekToNext() }; findViewById<View>(R.id.btnPrev).setOnClickListener { controller?.seekToPrevious() }
        findViewById<View>(R.id.btnQueue).setOnClickListener { showQueueBottomSheet() }
        findViewById<View>(R.id.btnFullRepeat).setOnClickListener { val p = controller ?: return@setOnClickListener; val next = when (p.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }; p.repeatMode = next; updateRepeatButton(); Toast.makeText(this, repeatLabel(next), Toast.LENGTH_SHORT).show() }
        findViewById<View>(R.id.btnFullShuffle).setOnClickListener { val p = controller ?: return@setOnClickListener; p.shuffleModeEnabled = !p.shuffleModeEnabled; updateShuffleButton() }
        fullSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(p0: SeekBar?, p1: Int, p2: Boolean) {}; override fun onStartTrackingTouch(p0: SeekBar?) {}; override fun onStopTrackingTouch(sb: SeekBar?) { sb?.let { controller?.seekTo(it.progress.toLong()) } } })
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dy = e2.y - (e1?.y ?: e2.y)
                if (lyricsVisible) return false
                if (vy < -450 && dy < -120) { showLyricsSheet(); return true }
                if (vy > 500 && dy > 150) { hideFullPlayer(); return true }
                return false
            }
        })
        fun attach(v: View) { v.setOnTouchListener { view, event -> if (gd.onTouchEvent(event)) return@setOnTouchListener true; if (event.action == MotionEvent.ACTION_UP) view.performClick(); true } }
        attach(fullPlayerContainer)
        findViewById<View>(R.id.llSongDetails).visibility = View.GONE
        findViewById<View>(R.id.btnOpenYouTube).setOnClickListener { openCurrentOnYouTube() }
        findViewById<View>(R.id.btnPlayerMenu).setOnClickListener { showPlayerMenu(it) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val now = SystemClock.elapsedRealtime()
                if (now < suppressBackUntil) return
                if (lyricsVisible) {
                    suppressBackUntil = now + 350L
                    hideLyricsSheet()
                    return
                }
                when {
                    fullPlayerContainer.visibility == View.VISIBLE -> hideFullPlayer()
                    homeContainer.visibility == View.VISIBLE -> finish()
                    playlistContainer.visibility == View.VISIBLE -> showHome()
                    rvHistoryList.visibility == View.VISIBLE -> showHome()
                    searchContainer.visibility == View.VISIBLE -> showHome()
                    rvMusicList.visibility == View.VISIBLE -> when (currentLevel) {
                        1 -> if (libraryDsdOnly == null) showAlbums() else { libraryDsdOnly = null; showLocalLibrary() }
                        2 -> showLocalLibrary()
                        3 -> showLocalAlbums()
                        4 -> showLocalLibrary()
                        5 -> showLocalArtists()
                        6 -> showLocalLibrary()
                        7 -> showAlbums()
                        8 -> showPlaylists()
                        else -> showHome()
                    }
                    else -> showHome()
                }
            }
        })
    }

    private fun updatePlayerUi() {
        val p = controller ?: return; val item = p.currentMediaItem ?: return
        runOnUiThread {
            miniPlayer.visibility = View.VISIBLE
            findViewById<TextView>(R.id.tvMiniTitle).text = item.mediaMetadata.title
            findViewById<TextView>(R.id.tvMiniArtist).text = item.mediaMetadata.artist
            findViewById<TextView>(R.id.tvFullTitle).text = item.mediaMetadata.title
            findViewById<TextView>(R.id.tvFullArtist).text = item.mediaMetadata.artist
            fullSeekBar.max = if (p.duration > 0) p.duration.toInt() else 100
            tvFullDuration.text = formatTime(if (p.duration > 0) p.duration else 0L)
            val ivMini = findViewById<ImageView>(R.id.ivMiniAlbumArt); val ivFull = findViewById<ImageView>(R.id.ivFullAlbumArt)
            loadArtwork(item.mediaMetadata.artworkUri ?: item.localConfiguration?.uri, ivMini, ivFull)
            val extras = item.mediaMetadata.extras
            val source = extras?.getString("source_url")
            if (!source.isNullOrEmpty()) {
                if (source != currentStreamSourceUrl) currentStreamResult = null
                currentStreamSourceUrl = source
            } else if (item.mediaId != "YT_STREAM") {
                currentStreamSourceUrl = null
                currentStreamResult = null
            }
            // Video/MV playback was removed. YouTube items are audio-only and
            // the full-player surface always shows the thumbnail artwork.
            ivFull.visibility = View.VISIBLE
            findViewById<View>(R.id.btnOpenYouTube).visibility = if (currentStreamSourceUrl.isNullOrEmpty()) View.GONE else View.VISIBLE
            updateMiniProcessingBadge(item)
            val icon = if (p.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            findViewById<ImageButton>(R.id.btnMiniPlayPause).setImageResource(icon); findViewById<ImageButton>(R.id.btnFullPlayPause).setImageResource(icon)
            updateFormatInfo(item); updateRepeatButton(); updateShuffleButton()
            if (lyricsVisible) loadLyricsForCurrent()
        }
    }

    private fun updateMiniProcessingBadge(item: MediaItem) {
        findViewById<TextView>(R.id.tvMiniProcessing).text = when {
            DauControl.active -> "AI DAU ${"%.0f".format(DauControl.effectiveStrength * 100)}%"
            StreamingDspRuntime.state.enabled -> "DSP P${StreamingDspRuntime.state.preset}"
            item.mediaId.startsWith("DOWNLOADED") || item.mediaId == "COMPRESSED_FILE" -> "PROTECTED"
            else -> ""
        }
    }

    private fun loadArtwork(uri: Uri?, vararg targets: ImageView) {
        targets.forEach { target ->
            if (uri == null) {
                target.tag = null
                target.setImageResource(R.drawable.ic_default_art)
            } else {
                AlbumArtLoader.load(this, uri, target)
            }
        }
    }

    private fun buildSecureMediaItem(entry: SecureDownloadManager.Entry): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(entry.title)
            .setArtist(entry.artist)
            .setArtworkUri(entry.thumbnail.takeIf { it.isNotEmpty() }?.toUri())
            .setExtras(Bundle().apply {
                putString("source_url", entry.sourceUrl)
                putString("source_format", if (entry.video) "mp4" else "aac")
                putLong("duration", entry.durationSeconds * 1000L)
            })
            .build()
        return MediaItem.Builder()
            .setUri("slm://${entry.id}".toUri())
            .setMediaId(if (entry.video) "DOWNLOADED_VIDEO" else "COMPRESSED_FILE")
            .setMediaMetadata(meta)
            .build()
    }

    private fun secureEntryFor(item: MusicItem): SecureDownloadManager.Entry? =
        SecureDownloadManager.list(this).firstOrNull { it.id.hashCode().toLong() == item.id }

    private fun detectLocalFormat(uri: Uri, ext: String): String {
        if (ext != "m4a" && ext != "mp4") return ext
        val key = uri.toString()
        localCodecCache[key]?.let { return it }
        var mime = ""
        try {
            val extractor = MediaExtractor()
            try {
                contentResolver.openFileDescriptor(uri, "r")?.use { extractor.setDataSource(it.fileDescriptor) }
                for (i in 0 until extractor.trackCount) {
                    val trackMime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                    if (trackMime.startsWith("audio/")) { mime = trackMime.lowercase(); break }
                }
            } finally {
                extractor.release()
            }
        } catch (_: Throwable) {}
        val fourcc = try {
            contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(1024 * 1024)
                val count = input.read(buffer)
                val text = String(buffer, 0, count.coerceAtLeast(0), Charsets.ISO_8859_1)
                when {
                    text.contains("alac") -> "alac"
                    text.contains("mp4a") -> "aac"
                    else -> ""
                }
            }.orEmpty()
        } catch (_: Throwable) { "" }
        val format = when {
            fourcc == "alac" || mime.contains("alac") -> "alac"
            fourcc == "aac" || mime.contains("mp4a") || mime.contains("aac") -> "aac"
            else -> {
                val codec = try {
                    val parameter = FFmpegKitConfig.getSafParameterForRead(this, uri)
                    FFprobeKit.getMediaInformation(parameter).mediaInformation
                        ?.streams?.firstOrNull { it.type == "audio" }?.codec?.lowercase().orEmpty()
                } catch (_: Throwable) { "" }
                if (codec == "alac") "alac" else if (codec.isNotEmpty()) "aac" else "m4a"
            }
        }
        localCodecCache[key] = format
        return format
    }

    private fun buildMediaItem(item: MusicItem): MediaItem {
        if (item.format.startsWith("SECURE_", ignoreCase = true)) {
            secureEntryFor(item)?.let { return buildSecureMediaItem(it) }
        }
        val ext = item.contentUri.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
        val f = item.format.lowercase().ifBlank { detectLocalFormat(item.contentUri, ext) }
        val dsd = f in DSD_EXTENSIONS
        val alac = f == "alac"
        val compressed = f in setOf("mp3", "aac", "opus", "ogg", "vorbis", "wma") || (f == "m4a" && !alac)
        val mid = when {
            dsd -> "DSD_FILE"
            alac -> "ALAC_FILE"
            compressed -> "COMPRESSED_FILE"
            else -> "NORMAL_FILE"
        }
        val extras = Bundle().apply {
            putLong("duration", item.duration)
            putString("source_format", f)
            if (dsd) {
                try {
                    contentResolver.openFileDescriptor(item.contentUri, "r")?.use { pfd ->
                        java.io.FileInputStream(pfd.fileDescriptor).use { input ->
                            val fileSize = input.channel.size().takeIf { it > 0L } ?: pfd.statSize
                            DsdParser.parse(input.channel, fileSize)?.let { info ->
                                putInt("dsd_rate", info.sampleRate)
                                putInt("dsd_channels", info.channels)
                                putString("dsd_container", info.container.name)
                                putString("dsd_label", info.dsdLabel)
                                if (item.duration <= 0L) putLong("duration", info.durationMs)
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }
        }
        return MediaItem.Builder().setUri(item.contentUri).setMediaId(mid).setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(item.title)
                .setArtist(item.artist)
                .setAlbumTitle(item.album)
                .setArtworkUri(item.albumArtUri ?: item.contentUri)
                .setExtras(extras)
                .build()
        ).build()
    }
    private fun buildStreamMediaItem(
        result: YtAudioResult,
        sourceUrl: String = "https://www.youtube.com/watch?v=${result.videoId}"
    ): MediaItem {
        // Audio-only playback. Muxed MP4 is used only as an audio fallback when
        // no separate audio stream was offered; ExoPlayer disables video tracks
        // globally, so the video half is never decoded.
        val playbackUrl = result.audioUrl
            .ifEmpty { result.muxedUrl }
            .ifEmpty { result.videoUrl }
        val mime = runCatching { playbackUrl.toUri().getQueryParameter("mime") }.getOrNull().orEmpty()
        val codec = when {
            mime.contains("mp4", true) -> "AAC"
            mime.contains("webm", true) || mime.contains("ogg", true) -> "OPUS"
            mime.contains("mpeg", true) -> "MP3"
            else -> "STREAM"
        }
        val meta = MediaMetadata.Builder().setTitle(result.title).setArtist(result.uploader)
            .setExtras(Bundle().apply {
                putString("source_url", sourceUrl)
                putString("source_format", "stream")
                putString("source_codec", codec)
                putInt("source_bitrate", result.bestBitrate)
                putLong("duration", result.durationSeconds * 1000L)
            })
        if (result.thumbnailUrl.isNotEmpty()) meta.setArtworkUri(result.thumbnailUrl.toUri())
        return MediaItem.Builder().setUri(playbackUrl.toUri()).setMediaId("YT_STREAM")
            .setMediaMetadata(meta.build()).build()
    }

    private fun playMusicInContext(item: MusicItem) {
        val p = controller ?: return
        if (currentAlbumSongs.isNotEmpty() && currentAlbumSongs.any { it.contentUri == item.contentUri }) {
            val items = currentAlbumSongs.map { song ->
                if (song.format == "YT_STREAM") buildQueuedStreamMediaItem(song) else buildMediaItem(song)
            }
            var startIndex = currentAlbumSongs.indexOf(item)
            if (startIndex < 0) startIndex = currentAlbumSongs.indexOfFirst { it.contentUri == item.contentUri }
            p.setMediaItems(items, startIndex.coerceAtLeast(0), 0)
            p.prepare()
            p.play()
            return
        }
        if (item.format == "YT_STREAM") {
            playStreamUrl(item.contentUri.toString(), item.title)
            return
        }
        val items = currentAlbumSongs.map { buildMediaItem(it) }
        p.setMediaItems(items, currentAlbumSongs.indexOf(item), 0)
        p.prepare()
        p.play()
    }

    private fun buildQueuedStreamMediaItem(song: MusicItem): MediaItem {
        val sourceUrl = song.contentUri.toString()
        val videoId = song.contentUri.getQueryParameter("v")
            ?: Regex("[?&]v=([a-zA-Z0-9_-]{11})").find(sourceUrl)?.groupValues?.get(1)
            ?: Regex("youtu\\.be/([a-zA-Z0-9_-]{11})").find(sourceUrl)?.groupValues?.get(1)
            ?: song.contentUri.lastPathSegment.orEmpty()
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .setExtras(Bundle().apply {
                putString("source_url", sourceUrl)
                putString("source_format", "stream")
                putLong("duration", song.duration)
            })
        if (song.albumArtUri != null) metadata.setArtworkUri(song.albumArtUri)
        return MediaItem.Builder()
            .setUri("ytstream:///$videoId")
            .setMediaId("YT_STREAM")
            .setMediaMetadata(metadata.build())
            .build()
    }
    private fun playNextInQueue(item: MusicItem) { val p = controller ?: return; if (item.format == "YT_STREAM") { Toast.makeText(this, getString(R.string.toast_stream_loading), Toast.LENGTH_SHORT).show(); YtStreamHelper.fetchAndPlay(item.contentUri.toString(), onSuccess = { r -> runOnUiThread { val idx = (p.currentMediaItemIndex + 1).coerceAtLeast(0); p.addMediaItem(idx, buildStreamMediaItem(r)); Toast.makeText(this, getString(R.string.toast_added_to_queue), Toast.LENGTH_SHORT).show() } }, onError = { m -> runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_LONG).show() } }); return }; val mi = buildMediaItem(item); p.addMediaItem((p.currentMediaItemIndex + 1).coerceAtLeast(0), mi) }
    private fun addToQueue(item: MusicItem) { val p = controller ?: return; if (item.format == "YT_STREAM") { Toast.makeText(this, getString(R.string.toast_stream_loading), Toast.LENGTH_SHORT).show(); YtStreamHelper.fetchAndPlay(item.contentUri.toString(), onSuccess = { r -> runOnUiThread { p.addMediaItem(buildStreamMediaItem(r)); Toast.makeText(this, getString(R.string.toast_added_to_queue), Toast.LENGTH_SHORT).show() } }, onError = { m -> runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_LONG).show() } }); return }; p.addMediaItem(buildMediaItem(item)); Toast.makeText(this, getString(R.string.toast_added_to_queue), Toast.LENGTH_SHORT).show() }

    private fun showQueueBottomSheet() { val d=BottomSheetDialog(this);val v=layoutInflater.inflate(R.layout.bottom_sheet_queue,drawerLayout,false);val rv=v.findViewById<RecyclerView>(R.id.rvQueue);rv.layoutManager=LinearLayoutManager(this);val p=controller?:return;val q=(0 until p.mediaItemCount).map{p.getMediaItemAt(it).mediaMetadata.title?.toString()?:getString(R.string.unknown)}.toMutableList();val adapter=object:RecyclerView.Adapter<RecyclerView.ViewHolder>(){override fun onCreateViewHolder(parent:ViewGroup,t:Int)=object:RecyclerView.ViewHolder(TextView(parent.context).apply{setPadding(32,32,32,32);textSize=14f;setTextColor(uiColor(R.color.text_primary))}){};override fun onBindViewHolder(h:RecyclerView.ViewHolder,pos:Int){(h.itemView as TextView).text="☰  ${q[pos]}";h.itemView.setOnClickListener{p.seekToDefaultPosition(pos);p.play()}};override fun getItemCount()=q.size};rv.adapter=adapter;androidx.recyclerview.widget.ItemTouchHelper(object:androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(androidx.recyclerview.widget.ItemTouchHelper.UP or androidx.recyclerview.widget.ItemTouchHelper.DOWN,0){override fun onMove(r:RecyclerView,h:RecyclerView.ViewHolder,target:RecyclerView.ViewHolder):Boolean{val from=h.bindingAdapterPosition;val to=target.bindingAdapterPosition;if(from<0||to<0)return false;java.util.Collections.swap(q,from,to);p.moveMediaItem(from,to);adapter.notifyItemMoved(from,to);return true};override fun onSwiped(h:RecyclerView.ViewHolder,direction:Int){}}).attachToRecyclerView(rv);d.setContentView(v);d.show() }

    private fun openCurrentOnYouTube() {
        val url = currentStreamSourceUrl ?: return
        try { startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) } catch (_: Exception) { Toast.makeText(this, "YouTubeを開けません", Toast.LENGTH_SHORT).show() }
    }

    private fun showPlayerMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("ダウンロード"); menu.add("歌詞"); menu.add("YouTubeで開く"); menu.add("再生設定"); menu.add("キューを表示")
            setOnMenuItemClickListener { item -> when (item.title.toString()) {
                "ダウンロード" -> { currentStreamSourceUrl?.let { requestSecureDownload(it, currentStreamResult) } ?: Toast.makeText(this@MainActivity, "ストリーミング曲でのみ利用できます", Toast.LENGTH_SHORT).show(); true }
                "歌詞" -> { showLyricsSheet(); true }
                "YouTubeで開く" -> { openCurrentOnYouTube(); true }
                "再生設定" -> { hideFullPlayer(); showPlaybackSettings(); true }
                else -> { showQueueBottomSheet(); true }
            } }; show()
        }
    }

    private fun showDownloadQualityDialog() {
        val values = arrayOf("最高品質", "高品質", "標準品質"); val prefs=getSharedPreferences("BitPerfectPrefs",MODE_PRIVATE); val selected=prefs.getInt("download_quality",0)
        AlertDialog.Builder(this).setTitle("ダウンロード品質").setSingleChoiceItems(values,selected){d,w->prefs.edit{putInt("download_quality",w)};d.dismiss()}.show()
    }
    private fun showDownloadTypeDialog() {
        val values=arrayOf("音声", "動画"); val prefs=getSharedPreferences("BitPerfectPrefs",MODE_PRIVATE); val selected=prefs.getInt("download_type",0)
        AlertDialog.Builder(this).setTitle("保存形式").setSingleChoiceItems(values,selected){d,w->prefs.edit{putInt("download_type",w)};d.dismiss()}.show()
    }
    private fun showDownloadMobileDialog() {
        val values=arrayOf("毎回確認", "許可する", "許可しない"); val prefs=getSharedPreferences("BitPerfectPrefs",MODE_PRIVATE); val selected=prefs.getInt("download_mobile",0)
        AlertDialog.Builder(this).setTitle("モバイル通信でダウンロード").setSingleChoiceItems(values,selected){d,w->prefs.edit{putInt("download_mobile",w)};d.dismiss()}.show()
    }
    private fun isOnCellular(): Boolean { val cm=getSystemService(ConnectivityManager::class.java); val caps=cm.getNetworkCapabilities(cm.activeNetwork)?:return false; return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }

    private fun requestSecureDownload(sourceUrl: String, cached: YtAudioResult?) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 88)
        }
        val policy=getSharedPreferences("BitPerfectPrefs",MODE_PRIVATE).getInt("download_mobile",0)
        if(isOnCellular() && policy==2){AlertDialog.Builder(this).setTitle("ダウンロードできません").setMessage("モバイル通信でのダウンロードは設定で許可されていません。").setPositiveButton("OK",null).show();return}
        if(isOnCellular() && policy==0){AlertDialog.Builder(this).setTitle("モバイル通信を使用").setMessage("データ通信量を消費します。今回ダウンロードしますか？").setPositiveButton("ダウンロード"){_,_->resolveAndDownload(sourceUrl,cached)}.setNegativeButton("キャンセル",null).show();return}
        resolveAndDownload(sourceUrl,cached)
    }
    private fun resolveAndDownload(sourceUrl:String,cached:YtAudioResult?){
        if(cached!=null){startSecureDownload(sourceUrl,cached);return}; showLoading(); YtStreamHelper.fetchAndPlay(sourceUrl,onSuccess={r->runOnUiThread{hideLoading();startSecureDownload(sourceUrl,r)}},onError={m->runOnUiThread{hideLoading();Toast.makeText(this,m,Toast.LENGTH_LONG).show()}})
    }
    private fun startSecureDownload(sourceUrl:String,result:YtAudioResult){
        val prefs=getSharedPreferences("BitPerfectPrefs",MODE_PRIVATE); val video=false; val quality=prefs.getInt("download_quality",0)
        Toast.makeText(this,"暗号化ダウンロードを開始しました",Toast.LENGTH_SHORT).show()
        SecureDownloadManager.download(this,result,sourceUrl,video,quality,onProgress={},onDone={entry->runOnUiThread{Toast.makeText(this,"保存完了: ${entry.title}",Toast.LENGTH_LONG).show()}},onError={m->runOnUiThread{Toast.makeText(this,m,Toast.LENGTH_LONG).show()}})
    }
    private fun showManagedDownloads(){
        val entries=SecureDownloadManager.list(this); if(entries.isEmpty()){Toast.makeText(this,"保存済みメディアはありません",Toast.LENGTH_SHORT).show();return}
        AlertDialog.Builder(this).setTitle("保存済みメディア").setItems(entries.map{"${if(it.video) "VIDEO" else "AUDIO"}  ${it.title}"}.toTypedArray()){_,i->val e=entries[i];AlertDialog.Builder(this).setTitle(e.title).setItems(arrayOf("再生","削除")){d,w->if(w==0)playSecureDownload(e)else{SecureDownloadManager.delete(this,e);Toast.makeText(this,"削除しました",Toast.LENGTH_SHORT).show()};d.dismiss()}.show()}.show()
    }
    private fun showDownloadQueueControl(){val task=SecureDownloadManager.activeTask();if(task.isBlank()){Toast.makeText(this,"実行中のダウンロードはありません",Toast.LENGTH_SHORT).show();return};AlertDialog.Builder(this).setTitle("ダウンロードキュー").setMessage("実行中: $task").setPositiveButton(if(SecureDownloadManager.isPaused())"再開" else "一時停止"){_,_->if(SecureDownloadManager.isPaused())SecureDownloadManager.resumeActive()else SecureDownloadManager.pauseActive()}.setNegativeButton("キャンセル"){_,_->SecureDownloadManager.cancelActive()}.setNeutralButton("閉じる",null).show()}
    private fun playSecureDownload(entry:SecureDownloadManager.Entry){securePlaybackRequestedAt=android.os.SystemClock.elapsedRealtime();currentStreamSourceUrl=entry.sourceUrl;controller?.apply{setMediaItem(buildSecureMediaItem(entry));prepare();play()}}

    private fun downloadedMusicItems():List<MusicItem> {var entries=SecureDownloadManager.list(this).filter{downloadFilter.isBlank()||it.title.contains(downloadFilter,true)||it.artist.contains(downloadFilter,true)};entries=when(downloadSort){1->entries.sortedBy{it.createdAt};2->entries.sortedBy{it.title.lowercase()};3->entries.sortedBy{it.artist.lowercase()};else->entries.sortedByDescending{it.createdAt}};return entries.mapIndexed{index,e->val mb=e.bytes/1048576.0;MusicItem(e.id.hashCode().toLong(),e.title,"${e.artist} · ${if(e.video)"VIDEO" else "AUDIO"} · ${"%.1f".format(mb)} MB",LIBRARY_DOWNLOADS,index+1,e.durationSeconds*1000,"secure-download://${e.id}".toUri(),e.thumbnail.takeIf{it.isNotEmpty()}?.toUri(),if(e.video)"SECURE_VIDEO" else "SECURE_AUDIO")}}
    private fun showDownloadFilterDialog(){val options=arrayOf("新しい順","古い順","曲名順","アーティスト順","検索…","検索解除");AlertDialog.Builder(this).setTitle("検索・並べ替え").setItems(options){_,which->when(which){in 0..3->{downloadSort=which;showDownloadedLibrary()};4->{val input=EditText(this);input.hint="曲名またはアーティスト";AlertDialog.Builder(this).setTitle("ダウンロードを検索").setView(input).setPositiveButton("検索"){_,_->downloadFilter=input.text.toString().trim();showDownloadedLibrary()}.setNegativeButton("キャンセル",null).show()};5->{downloadFilter="";showDownloadedLibrary()}}}.show()}
    private fun playSecureMusicItem(item:MusicItem){val entry=secureEntryFor(item)?:return;playSecureDownload(entry)}
    private fun confirmDeleteDownloads(items:List<MusicItem>){
        AlertDialog.Builder(this).setTitle("ダウンロードを削除").setMessage("選択した${items.size}件を端末から削除します。元に戻せません。").setPositiveButton("削除"){_,_->val ids=items.map{it.id}.toSet();SecureDownloadManager.list(this).filter{it.id.hashCode().toLong() in ids}.forEach{SecureDownloadManager.delete(this,it)};Toast.makeText(this,"${items.size}件を削除しました",Toast.LENGTH_SHORT).show();showDownloadedLibrary()}.setNegativeButton("キャンセル",null).show()
    }

    private fun showFullPlayer() { val v = fullPlayerContainer; v.visibility = View.VISIBLE; v.alpha = 0f; v.translationY = if (v.height > 0) v.height.toFloat() else resources.displayMetrics.heightPixels.toFloat(); v.animate().translationY(0f).alpha(1f).setDuration(250).setInterpolator(DecelerateInterpolator()).start() }
    private fun hideFullPlayer() { if (lyricsVisible) hideLyricsSheet(); val v = fullPlayerContainer; if (v.visibility != View.VISIBLE) return; val ty = if (v.height > 0) v.height.toFloat() else resources.displayMetrics.heightPixels.toFloat(); v.animate().translationY(ty).alpha(0f).setDuration(300).setInterpolator(DecelerateInterpolator()).withEndAction { v.visibility = View.GONE; v.translationY = 0f; v.alpha = 1f }.start() }

    private fun showLyricsSheet() {
        if (!::lyricsSheet.isInitialized || lyricsVisible) return
        lyricsVisible = true
        lyricsSheet.visibility = View.VISIBLE
        lyricsSheet.post {
            val from = if (lyricsSheet.height > 0) lyricsSheet.height.toFloat() else resources.displayMetrics.heightPixels.toFloat()
            lyricsSheet.translationY = from
            lyricsSheet.animate().translationY(0f).setDuration(280).setInterpolator(DecelerateInterpolator()).start()
        }
        // Fresh views on every open; let the repository cache provide an
        // immediate redraw instead of returning before touching the views.
        lastLyricsKey = null
        lastLyricsTouch = 0L
        loadLyricsForCurrent()
    }

    private fun hideLyricsSheet() {
        if (!::lyricsSheet.isInitialized || !lyricsVisible) return
        lyricsVisible = false
        stopLyricsTicker()
        syncedLyrics = emptyList()
        currentLyricLine = -1
        val to = if (lyricsSheet.height > 0) lyricsSheet.height.toFloat() else resources.displayMetrics.heightPixels.toFloat()
        lyricsSheet.animate().translationY(to).setDuration(220).setInterpolator(DecelerateInterpolator()).withEndAction { lyricsSheet.visibility = View.GONE }.start()
    }

    private fun loadLyricsForCurrent() {
        val p = controller ?: return
        val item = p.currentMediaItem ?: run {
            resetLyricsViews()
            lyricsStatus.text = getString(R.string.lyrics_not_found)
            return
        }
        val meta = item.mediaMetadata
        val title = meta.title?.toString().orEmpty()
        val artist = meta.artist?.toString().orEmpty()
        val album = meta.albumTitle?.toString().orEmpty()
        val duration = meta.extras?.getLong("duration", 0L) ?: 0L
        val key = "$title|$artist|$album"
        if (key == lastLyricsKey) return
        lastLyricsKey = key
        resetLyricsViews()
        lyricsTitle.text = if (artist.isBlank()) title else "$title — $artist"
        lyricsStatus.text = getString(R.string.lyrics_loading)
        lyricsScroll.scrollTo(0, 0)

        // Stream titles keep YouTube decoration ([MV], (Official Video),
        // "- Topic") which makes LRCLIB miss. Look up Music metadata first and
        // search lyrics with the cleaned song name; fall back to the raw tags.
        val sourceUrl = meta.extras?.getString("source_url")
        val videoId = sourceUrl?.let { YtUrlParser.parse(it).videoId }.orEmpty()
        if (item.mediaId == "YT_STREAM" && videoId.isNotEmpty()) {
            Thread {
                val music = runCatching { YtStreamHelper.musicMetadata(videoId) }.getOrNull()
                runOnUiThread {
                    if (!lyricsVisible || lastLyricsKey != key) return@runOnUiThread
                    val queryTitle = music?.title?.takeIf { it.isNotBlank() } ?: title
                    val queryArtist = music?.artist?.takeIf { it.isNotBlank() } ?: artist
                    val queryAlbum = music?.album.orEmpty()
                    val queryDuration = music?.durationSeconds?.takeIf { it > 0L }?.times(1000L) ?: duration
                    fetchLyrics(key, queryTitle, queryArtist, queryAlbum, queryDuration)
                }
            }.apply { name = "LyricsMeta"; start() }
            return
        }

        fetchLyrics(key, title, artist, album, duration)
    }

    private fun fetchLyrics(key: String, title: String, artist: String, album: String, duration: Long) {
        LyricsFetcher.fetch(applicationContext, title, artist, album, duration) { result, error ->
            runOnUiThread {
                if (!lyricsVisible || lastLyricsKey != key) return@runOnUiThread
                if (result == null || (result.text.isBlank() && result.lines.isEmpty())) {
                    lyricsStatus.text = getString(R.string.lyrics_not_found) + if (!error.isNullOrBlank()) " ($error)" else ""
                    resetLyricsViews()
                } else if (result.lines.isNotEmpty()) {
                    lyricsStatus.text = getString(R.string.lyrics_source, result.source) + " · 同期"
                    buildSyncedLyrics(result.lines)
                } else {
                    lyricsStatus.text = getString(R.string.lyrics_source, result.source)
                    lyricsLines.visibility = View.GONE
                    lyricsText.visibility = View.VISIBLE
                    lyricsText.text = result.text
                    lyricsScroll.scrollTo(0, 0)
                }
            }
        }
    }

    private fun resetLyricsViews() {
        stopLyricsTicker()
        syncedLyrics = emptyList()
        currentLyricLine = -1
        lyricsLines.removeAllViews()
        lyricsLines.visibility = View.GONE
        lyricsText.visibility = View.VISIBLE
        lyricsText.text = ""
        lyricsScroll.scrollTo(0, 0)
    }

    private fun buildSyncedLyrics(lines: List<LyricsLine>) {
        syncedLyrics = lines
        currentLyricLine = -1
        lyricsText.visibility = View.GONE
        lyricsLines.visibility = View.VISIBLE
        lyricsLines.removeAllViews()
        lines.forEach { line ->
            lyricsLines.addView(TextView(this).apply {
                text = line.text
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(uiColor(R.color.text_secondary))
                setPadding(dp(24), dp(9), dp(24), dp(9))
                setLineSpacing(dp(4).toFloat(), 1f)
                isClickable = true
                setOnClickListener {
                    val player = controller ?: return@setOnClickListener
                    val duration = player.duration
                    val target = if (duration > 0) line.timeMs.coerceIn(0L, duration) else line.timeMs.coerceAtLeast(0L)
                    player.seekTo(target)
                    updateSyncedLyrics(force = true)
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        lyricsScroll.scrollTo(0, 0)
        updateSyncedLyrics(force = true)
        lyricsScroll.post {
            if (lyricsVisible && syncedLyrics === lines) updateSyncedLyrics(force = true)
        }
        startLyricsTicker()
    }

    private fun updateSyncedLyrics(force: Boolean = false) {
        if (syncedLyrics.isEmpty()) return
        val position = controller?.currentPosition ?: 0L
        var index = syncedLyrics.indexOfLast { it.timeMs <= position }
        if (index < 0) index = 0
        if (!force && index == currentLyricLine) return
        if (currentLyricLine in syncedLyrics.indices) {
            lyricsLines.getChildAt(currentLyricLine)?.let { view ->
                val tv = view as TextView
                tv.setTextColor(uiColor(R.color.text_secondary))
                tv.setTypeface(null, Typeface.NORMAL)
                tv.alpha = 0.78f
            }
        }
        currentLyricLine = index
        val lineView = lyricsLines.getChildAt(index) as? TextView ?: return
        lineView.setTextColor(uiColor(R.color.accent))
        lineView.setTypeface(null, Typeface.BOLD)
        lineView.alpha = 1f
        if (SystemClock.elapsedRealtime() - lastLyricsTouch > 3000) {
            val target = (lyricsLines.top + lineView.top - lyricsScroll.height / 3).coerceAtLeast(0)
            lyricsScroll.smoothScrollTo(0, target)
        }
    }

    private val selectFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); scanFolderDirectly(uri) } }
    private fun loadSavedFolderAndScan() {
        val prefs = getSharedPreferences("BitPerfectPrefs", MODE_PRIVATE)
        val savedUri = prefs.getString("last_folder_uri", null)
        savedUri?.let { uriStr ->
            val uri = uriStr.toUri()
            if (contentResolver.persistedUriPermissions.any { it.uri == uri }) {
                if (albums.isEmpty()) {
                    // No cached data - do full scan synchronously (show loading)
                    scanFolderDirectly(uri, showAlbumsAfter = false)
                } else {
                    // DB cache loaded - run diff scan in background
                    scanFolderDirectly(uri, showAlbumsAfter = false, diffOnly = true)
                }
            }
        }
    }
    private fun scanFolderDirectly(treeUri: Uri, showAlbumsAfter: Boolean = true, diffOnly: Boolean = false) {
        getSharedPreferences("BitPerfectPrefs", MODE_PRIVATE).edit { putString("last_folder_uri", treeUri.toString()) }
        val db = libraryDB
        if (!diffOnly) showLoading()
        backgroundExecutor.execute {
            val t0 = System.currentTimeMillis()
            val foundUris = mutableSetOf<String>()
            val newOrChanged = mutableListOf<MusicItem>()
            DocumentFile.fromTreeUri(this, treeUri)?.let { root ->
                walkForDiff(root, db, foundUris, newOrChanged)
            }
            val scanTime = System.currentTimeMillis() - t0
            AppLogger.i("PERF", "[timing] diff scan ${foundUris.size} files, ${newOrChanged.size} new/changed: ${scanTime}ms")
            
            // Remove files that no longer exist on disk
            if (diffOnly) {
                val allCached = db.loadAll().map { it.contentUri.toString() }.toSet()
                val deleted = allCached - foundUris
                deleted.forEach { db.deleteFile(it) }
                if (deleted.isNotEmpty()) AppLogger.i("PERF", "removed ${deleted.size} deleted files")
            }
            
            if (!diffOnly || newOrChanged.isNotEmpty() || foundUris.size != albums.sumOf { it.songs.size }) {
                // Rebuild albums from DB
                val allItems = db.loadAll()
                val grouped = allItems.groupBy { it.album }
                albums.clear(); grouped.forEach { (name, songs) -> albums.add(AlbumItem(name, songs.sortedBy { it.trackNumber })) }
                albums.sortBy { it.name }
            }
            runOnUiThread { hideLoading(); if (showAlbumsAfter) showAlbums() }
            val total = System.currentTimeMillis() - t0
            AppLogger.i("PERF", "[timing] scanFolder total: ${total}ms (new/changed: ${newOrChanged.size})")
        }
    }
    private fun traverse(dir: DocumentFile, list: MutableList<MusicItem>) { for (f in dir.listFiles()) { if (f.isDirectory) traverse(f, list) else if (LOCAL_AUDIO_EXTENSIONS.any { f.name?.endsWith(it, true) == true }) { val r = MediaMetadataRetriever(); try { contentResolver.openFileDescriptor(f.uri, "r")?.use { pfd -> r.setDataSource(pfd.fileDescriptor); val t = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: f.name ?: getString(R.string.unknown); val a = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: getString(R.string.unknown); val al = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: f.parentFile?.name ?: getString(R.string.unknown); val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L; val tn = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.split("/")?.get(0))?.toIntOrNull() ?: 0; list.add(MusicItem(f.uri.hashCode().toLong(), t, a, al, tn, d, f.uri, null)) } } catch (_: Exception) {} finally { r.release(); } } } }

    private fun walkForDiff(dir: DocumentFile, db: LibraryDB, foundUris: MutableSet<String>, changed: MutableList<MusicItem>) {
        for (f in dir.listFiles()) {
            if (f.isDirectory) { walkForDiff(f, db, foundUris, changed); continue }
            val name = f.name ?: continue
            if (!LOCAL_AUDIO_EXTENSIONS.any { name.endsWith(it, true) }) continue
            val uriStr = f.uri.toString()
            foundUris.add(uriStr)
            val lastMod = f.lastModified()
            val fileSize = f.length()
            val cached = db.getCachedInfo(uriStr)
            if (cached != null && cached.first == lastMod && cached.second == fileSize) continue
            val tm = System.currentTimeMillis()
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext in DSD_EXTENSIONS) {
                try {
                    val info = contentResolver.openFileDescriptor(f.uri, "r")?.use { pfd ->
                        java.io.FileInputStream(pfd.fileDescriptor).use { input ->
                            val fileSize = input.channel.size().takeIf { it > 0L } ?: pfd.statSize
                            DsdParser.parse(input.channel, fileSize)
                        }
                    }
                    val title = name.substringBeforeLast('.')
                    val album = f.parentFile?.name ?: "Unknown Album"
                    val item = MusicItem(
                        f.uri.hashCode().toLong(), title, "Unknown", album, 0,
                        info?.durationMs ?: 0L, f.uri, null, ext
                    )
                    db.saveFile(item, lastMod, fileSize)
                    changed.add(item)
                } catch (_: Exception) {}
            } else {
                val r = MediaMetadataRetriever()
                try {
                    contentResolver.openFileDescriptor(f.uri, "r")?.use { pfd ->
                        r.setDataSource(pfd.fileDescriptor)
                        val title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: name
                        val artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Unknown"
                        val album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: f.parentFile?.name ?: "Unknown Album"
                        val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
                        val trackNum = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.split("/")?.get(0))?.toIntOrNull() ?: 0
                        val item = MusicItem(f.uri.hashCode().toLong(), title, artist, album, trackNum, duration, f.uri, null)
                        db.saveFile(item, lastMod, fileSize)
                        changed.add(item)
                    }
                } catch (_: Exception) {} finally { r.release() }
            }
            val elapsed = System.currentTimeMillis() - tm
            if (elapsed > 50) AppLogger.i("PERF", "[timing] tag cache MISS ${elapsed}ms: $name")
        }
    }

    private fun hideAll() { rvMusicList.visibility = View.GONE; findViewById<View>(R.id.librarySelectionBar).visibility=View.GONE; rvMusicList.setPadding(0,0,0,0); musicAdapter.setSelectionMode(false); logUiContainer.visibility = View.GONE; homeContainer.visibility = View.GONE; dacInfoContainer.visibility = View.GONE; playbackSettingsContainer.visibility = View.GONE; otherContainer.visibility = View.GONE; playlistContainer.visibility = View.GONE; rvHistoryList.visibility = View.GONE; searchContainer.visibility = View.GONE }
    private fun selectBottom(id: Int) { if (::bottomNav.isInitialized && bottomNav.selectedItemId != id) bottomNav.menu.findItem(id).isChecked = true }
    private fun localSongs(): List<MusicItem> = albums.flatMap { it.songs }
    private fun isDsdSong(item: MusicItem): Boolean {
        if (item.format.lowercase() in DSD_EXTENSIONS) return true
        val ext = item.contentUri.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return ext in DSD_EXTENSIONS
    }
    private fun scopeSuffix() = when (libraryDsdOnly) { true -> "（DSD）"; false -> "（PCM）"; null -> "" }
    private fun scopedSongs(): List<MusicItem> {
        val scope = libraryDsdOnly ?: return localSongs()
        return localSongs().filter { isDsdSong(it) == scope }
    }
    private fun scopedAlbums(): List<AlbumItem> {
        val scope = libraryDsdOnly ?: return albums
        return albums.mapNotNull { a ->
            val songs = a.songs.filter { isDsdSong(it) == scope }
            if (songs.isEmpty()) null else AlbumItem(a.name, songs)
        }
    }
    private fun showAlbums() { hideAll(); currentLevel = 0; rvMusicList.visibility = View.VISIBLE; toolbarPageTitle.text = getString(R.string.nav_library); val localSongs=localSongs(); val downloaded=downloadedMusicItems(); musicAdapter.updateList(listOf(AlbumItem(LIBRARY_LOCAL,localSongs),AlbumItem(LIBRARY_DOWNLOADS,downloaded))); selectBottom(R.id.bottom_library) }
    private fun showLocalLibrary() {
        hideAll(); currentLevel = 1; rvMusicList.visibility = View.VISIBLE; toolbarPageTitle.text = LIBRARY_LOCAL + scopeSuffix()
        if (libraryDsdOnly == null) {
            val all = localSongs()
            val dsdCount = all.count { isDsdSong(it) }
            musicAdapter.updateList(listOf(
                LibraryCategory("PCM音源", "${all.size - dsdCount}曲", "SCOPE_PCM"),
                LibraryCategory("DSD音源", "${dsdCount}曲", "SCOPE_DSD")
            ))
            selectBottom(R.id.bottom_library)
            return
        }
        val songs = scopedSongs()
        val artists = songs.groupBy { it.artist.ifBlank { "不明なアーティスト" } }
        musicAdapter.updateList(listOf(
            LibraryCategory("アルバム", "${scopedAlbums().size}枚", "ALBUMS"),
            LibraryCategory("アーティスト", "${artists.size}人", "ARTISTS"),
            LibraryCategory("すべての曲", "${songs.size}曲", "ALL_SONGS")
        ))
        selectBottom(R.id.bottom_library)
    }
    private fun showLocalAlbums() { hideAll(); currentLevel = 2; rvMusicList.visibility=View.VISIBLE; toolbarPageTitle.text="アルバム" + scopeSuffix(); musicAdapter.updateList(scopedAlbums()); selectBottom(R.id.bottom_library) }
    private fun showSongsInAlbum(album: AlbumItem) { hideAll(); currentLevel = 3; rvMusicList.visibility = View.VISIBLE; toolbarPageTitle.text = album.name; currentAlbumSongs = album.songs; musicAdapter.updateList(album.songs) }
    private fun showLocalArtists() {
        hideAll(); currentLevel = 4; rvMusicList.visibility = View.VISIBLE; toolbarPageTitle.text = "アーティスト" + scopeSuffix()
        val artists = scopedSongs().groupBy { it.artist.ifBlank { "不明なアーティスト" } }
            .map { (name, songs) -> ArtistItem(name, songs.sortedWith(compareBy({ it.album.lowercase() }, { it.trackNumber }, { it.title.lowercase() }))) }
            .sortedBy { it.name.lowercase() }
        musicAdapter.updateList(artists)
        selectBottom(R.id.bottom_library)
    }
    private fun showSongsByArtist(artist: ArtistItem) { hideAll(); currentLevel = 5; rvMusicList.visibility = View.VISIBLE; toolbarPageTitle.text = artist.name; currentAlbumSongs = artist.songs; musicAdapter.updateList(artist.songs) }
    private fun showAllLocalSongs() {
        hideAll(); currentLevel = 6; rvMusicList.visibility = View.VISIBLE; toolbarPageTitle.text = "すべての曲" + scopeSuffix()
        val songs = scopedSongs().sortedBy { it.title.lowercase() }
        currentAlbumSongs = songs
        musicAdapter.updateList(songs)
        selectBottom(R.id.bottom_library)
    }
    private fun showDownloadedLibrary(){hideAll();currentLevel=7;rvMusicList.visibility=View.VISIBLE;toolbarPageTitle.text=LIBRARY_DOWNLOADS;val items=downloadedMusicItems();currentAlbumSongs=items;musicAdapter.updateList(items);val bar=findViewById<View>(R.id.librarySelectionBar);bar.visibility=View.VISIBLE;rvMusicList.setPadding(0,dp(64),0,0);findViewById<View>(R.id.btnDeleteSelectedDownloads).isEnabled=false;findViewById<TextView>(R.id.tvDownloadSelection).text="長押し、または全選択で複数削除";selectBottom(R.id.bottom_library)}
    private fun showLogsUi() { hideAll(); currentLevel = 0; logUiContainer.visibility = View.VISIBLE; toolbarPageTitle.text = getString(R.string.settings_app_log) }
    private fun showDacInfo() { hideAll(); currentLevel = 0; dacInfoContainer.visibility = View.VISIBLE; toolbarPageTitle.text = getString(R.string.nav_dac_info); refreshDacInfo() }
    private fun showOther() { hideAll(); currentLevel = 0; otherContainer.visibility = View.VISIBLE; toolbarPageTitle.text = getString(R.string.nav_other); loadAppInfo(); findViewById<TextView>(R.id.tvDauStatus).text = "${DauControl.backend}  ·  ${DauControl.parameterLabel}  ·  ${"%.2f".format(DauControl.processingMs)} ms  ·  ${if (DauControl.streamingActive.get()) if (DauControl.active) "ACTIVE" else "BYPASS" else "STREAMING STANDBY"}" }
    private fun showPlaybackSettings() { hideAll(); currentLevel=0; playbackSettingsContainer.visibility=View.VISIBLE;toolbarPageTitle.text=getString(R.string.nav_playback_settings);findViewById<TextView>(R.id.tvDauStatus).text="${DauControl.backend} · ${DauControl.parameterLabel} · ${"%.2f".format(DauControl.processingMs)} ms";findViewById<TextView>(R.id.tvDspStatus).text="${StreamingDspRuntime.backend} · STREAMING ONLY";updateYouTubeLoginStatus();updateYtDlpStatus() }

    private fun updateYouTubeLoginStatus() {
        val view = findViewById<TextView>(R.id.tvYouTubeLoginStatus) ?: return
        val loggedIn = YtStreamHelper.hasCookies()
        view.text = if (loggedIn) getString(R.string.youtube_login_status_logged_in) + " (" + YtStreamHelper.cookieMap.size + ")"
        else getString(R.string.youtube_login_status_logged_out)
        view.setTextColor(if (loggedIn) uiColor(R.color.accent) else uiColor(R.color.text_secondary))
    }

    private fun updateYtDlpStatus() {
        val view = findViewById<TextView>(R.id.tvYtDlpStatus) ?: return
        val version = YtDlpUpdater.currentVersion(this) ?: "?"
        val suffix = if (YtDlpUpdater.isUsingUpdate(this)) getString(R.string.ytdlp_status_updated)
        else getString(R.string.ytdlp_status_bundled)
        view.text = getString(R.string.ytdlp_status, version, suffix)
    }

    private fun updateYtDlp() {
        val button = findViewById<Button>(R.id.btnYtDlpUpdate) ?: return
        if (!button.isEnabled) return
        button.isEnabled = false
        findViewById<TextView>(R.id.tvYtDlpStatus)?.text = getString(R.string.ytdlp_checking)
        YtDlpUpdater.update(this) { success, message, _ ->
            runOnUiThread {
                button.isEnabled = true
                updateYtDlpStatus()
                val text = when {
                    !success -> getString(R.string.ytdlp_update_failed, message)
                    message.startsWith("latest:") -> getString(R.string.ytdlp_latest)
                    else -> getString(R.string.ytdlp_updated)
                }
                if (success) updateYtDlpStatus()
                else findViewById<TextView>(R.id.tvYtDlpStatus)?.text = text
                Toast.makeText(this, text, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showManualCookieDialog() {
        val input = EditText(this).apply {
            hint = "SID=...; HSID=...; SSID=...; APISID=...; SAPISID=..."
            setText(YtStreamHelper.cookies)
            setSingleLine(false)
            minLines = 4
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.youtube_login_manual))
            .setView(input)
            .setPositiveButton(getString(R.string.youtube_login_save)) { _, _ ->
                val value = input.text?.toString()?.trim().orEmpty()
                if (value.isEmpty()) {
                    Toast.makeText(this, getString(R.string.youtube_cookie_empty), Toast.LENGTH_SHORT).show()
                } else {
                    try {
                        YtStreamHelper.setCookies(this, value)
                        updateYouTubeLoginStatus()
                        Toast.makeText(this, getString(R.string.youtube_cookie_saved), Toast.LENGTH_SHORT).show()
                    } catch (t: Throwable) {
                        Toast.makeText(this, t.message ?: getString(R.string.youtube_cookie_empty), Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
    private fun showHome() { hideAll(); currentLevel = 0; homeContainer.visibility = View.VISIBLE; toolbarPageTitle.text = getString(R.string.nav_home); updateHomeNowPlaying(); updateSignalDeck(); selectBottom(R.id.bottom_home) }
    private fun showPlaylists() { hideAll(); currentLevel = 0; playlistContainer.visibility = View.VISIBLE; toolbarPageTitle.text = getString(R.string.nav_playlists); refreshPlaylistList(); selectBottom(R.id.bottom_playlists) }
    private fun showHistory() { hideAll(); currentLevel = 0; rvHistoryList.visibility = View.VISIBLE; toolbarPageTitle.text = getString(R.string.nav_history); historyAdapter.updateList(historyEntries) }
    private fun showSearch() { hideAll(); currentLevel = 0; searchContainer.visibility = View.VISIBLE; toolbarPageTitle.text = getString(R.string.nav_search); selectBottom(R.id.bottom_search); if (etSearchInput.text.isNullOrBlank()) showRecommendations() }
    private fun showPlaylistSongs(pl: Playlist) { hideAll(); currentLevel = 8; rvMusicList.visibility = View.VISIBLE; toolbarPageTitle.text = pl.name; currentAlbumSongs = pl.songs; musicAdapter.updateList(pl.songs); selectBottom(R.id.bottom_playlists); pl.songs.asSequence().filter { it.format == "YT_STREAM" }.take(1).forEach { YtStreamHelper.prefetch(it.contentUri.toString()) } }

    private fun refreshPlaylistList() { playlistAdapter.updateList(playlists.toList()) }
    private fun savePlaylists() { PlaylistStore.save(this, playlists) }
    private fun createPlaylist(name: String, song: MusicItem?) { val pl = Playlist(System.currentTimeMillis(), name, mutableListOf()); if (song != null) pl.songs.add(song); playlists.add(pl); savePlaylists(); refreshPlaylistList() }
    private fun addSongToPlaylist(pl: Playlist, song: MusicItem) { if (pl.songs.any { it.contentUri == song.contentUri }) { Toast.makeText(this, getString(R.string.playlist_already_in).replace("%s", pl.name), Toast.LENGTH_SHORT).show(); return }; pl.songs.add(song); savePlaylists(); refreshPlaylistList(); Toast.makeText(this, getString(R.string.playlist_added).replace("%s", pl.name), Toast.LENGTH_SHORT).show() }
    private fun showAddToPlaylistDialog(song: MusicItem) { if (playlists.isEmpty()) { showCreatePlaylistDialog(song); return }; val names = playlists.map { it.name }.toMutableList(); val n = names.size; names.add("＋ New Playlist"); AlertDialog.Builder(this).setTitle(getString(R.string.playlist_add_to)).setItems(names.toTypedArray()) { _, which -> if (which == n) showCreatePlaylistDialog(song) else addSongToPlaylist(playlists[which], song) }.show() }
    private fun showCreatePlaylistDialog(song: MusicItem?) { val i = EditText(this); i.hint = getString(R.string.playlist_create_hint); AlertDialog.Builder(this).setTitle(getString(R.string.playlist_create_title)).setView(i).setPositiveButton(getString(R.string.playlist_create)) { _, _ -> val name = i.text.toString().trim(); if (name.isNotEmpty()) createPlaylist(name, song) }.setNegativeButton("Cancel", null).show() }
    private fun showRenamePlaylistDialog(pl: Playlist) { val i = EditText(this); i.setText(pl.name); i.hint = getString(R.string.playlist_create_hint); AlertDialog.Builder(this).setTitle(getString(R.string.playlist_rename_title)).setView(i).setPositiveButton(getString(R.string.dialog_ok)) { _, _ -> val name = i.text.toString().trim(); if (name.isNotEmpty()) { pl.name = name; savePlaylists(); refreshPlaylistList() } }.setNegativeButton("Cancel", null).show() }
    private fun deletePlaylist(pl: Playlist) { AlertDialog.Builder(this).setTitle(getString(R.string.playlist_delete_title)).setMessage(getString(R.string.playlist_delete_message).replace("%s", pl.name)).setPositiveButton(getString(R.string.playlist_delete_confirm)) { _, _ -> playlists.remove(pl); savePlaylists(); refreshPlaylistList() }.setNegativeButton("Cancel", null).show() }

    private fun updateHomeNowPlaying() { val p = controller; val item = p?.currentMediaItem; if (item == null) { tvHomeTitle.text = getString(R.string.home_none); tvHomeArtist.text = "" } else { tvHomeTitle.text = item.mediaMetadata.title; tvHomeArtist.text = item.mediaMetadata.artist }; updateHomePlayIcon() }
    private fun updateHomePlayIcon() { val p = controller; btnHomePlayPause.setImageResource(if (p != null && p.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play) }
    private fun updateSignalDeck() {
        val p = controller
        val item = p?.currentMediaItem
        val fmt = findViewById<TextView>(R.id.tvDeckFormat) ?: return
        val bitsV = findViewById<TextView>(R.id.tvDeckBitDepth)
        val rateV = findViewById<TextView>(R.id.tvDeckSampleRate)
        val brV = findViewById<TextView>(R.id.tvDeckBitrate)
        val outV = findViewById<TextView>(R.id.tvDeckOutput)
        val outDetailV = findViewById<TextView>(R.id.tvDeckOutputDetail)
        val procV = findViewById<TextView>(R.id.tvDeckProcessing)
        val engineV = findViewById<TextView>(R.id.tvDeckEngine)
        val volV = findViewById<TextView>(R.id.tvDeckVolume)
        val stateV = findViewById<TextView>(R.id.tvDeckState)
        if (item == null) {
            listOf(fmt, bitsV, rateV, brV, outV, outDetailV, engineV, volV).forEach { it.text = "—" }
            procV.text = "OFF"
            stateV.text = "IDLE"
            stateV.setTextColor(uiColor(R.color.text_muted))
            return
        }
        val ex = item.mediaMetadata.extras
        val uri = item.localConfiguration?.uri
        val isStream = item.mediaId == "YT_STREAM"
        val isDsd = item.mediaId == "DSD_FILE"
        val secureId = if (uri?.scheme == "slm") uri.host.orEmpty() else ""
        val secureEntry = if (secureId.isNotEmpty()) SecureDownloadManager.list(this).firstOrNull { it.id == secureId } else null
        val tf = when {
            secureEntry != null -> secureFormatCache[secureId]
            else -> uri?.let { formatCache[it.toString()] }
        }
        if (secureEntry != null && tf == null && secureProbeStarted.putIfAbsent(secureId, true) == null) {
            backgroundExecutor.execute {
                val probed = runCatching {
                    val u = SecureDownloadManager.preparePlayback(this, secureEntry)
                    extractTrackFormat(u)
                }.getOrNull()
                if (probed != null) {
                    secureFormatCache[secureId] = probed
                    runOnUiThread { updateSignalDeck() }
                }
            }
        }
        val codec = when {
            isDsd -> ex?.getString("dsd_container") ?: "DSD"
            isStream -> ex?.getString("source_codec") ?: "STREAM"
            secureEntry != null -> if (secureEntry.video) "MP4/AAC" else "AAC"
            tf != null -> tf.codec
            else -> ex?.getString("source_format")?.uppercase() ?: "—"
        }
        val lossy = isStream || codec == "AAC" || codec == "MP3" || codec == "OPUS" || codec == "OGG" || codec == "MP4/AAC"
        val bits = when {
            isDsd -> "1 bit"
            lossy -> "—"
            tf?.bitDepth != null -> "${tf.bitDepth} bit"
            else -> "—"
        }
        val rate = when {
            isDsd -> ex?.getString("dsd_label") ?: "DSD"
            tf != null && tf.sampleRate > 0 -> formatSampleRate(tf.sampleRate)
            isStream -> getSystemService(AudioManager::class.java)?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
                ?.toIntOrNull()?.let { formatSampleRate(it) } ?: "—"
            else -> "—"
        }
        val br = when {
            secureEntry != null -> if (secureEntry.durationSeconds > 0 && secureEntry.bytes > 0)
                "${(secureEntry.bytes * 8 / secureEntry.durationSeconds / 1000)} kbps" else "—"
            isStream -> ex?.getInt("source_bitrate", 0)?.takeIf { it > 0 }?.let { "${it / 1000} kbps" } ?: "—"
            tf != null && tf.bitrateKbps > 0 -> "${tf.bitrateKbps} kbps"
            else -> "—"
        }
        val am = getSystemService(AudioManager::class.java)
        val devs = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
        val usb = devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
        val btA2dpDev = devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        val btScoDev = devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        val bt = btA2dpDev ?: btScoDev
        val wired = devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
        val speaker = devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        val output = when {
            usb != null -> if (usb.type == AudioDeviceInfo.TYPE_USB_HEADSET) "USB HEADSET" else "USB DAC"
            btA2dpDev != null -> "BLUETOOTH A2DP"
            btScoDev != null -> "BLUETOOTH SCO"
            wired != null -> "WIRED"
            speaker != null -> "SPEAKER"
            else -> "NO OUTPUT"
        }
        if (bt != null) ensureBluetoothCodec()
        val outputDetail = when {
            usb != null -> if (UsbOutputMode.load(this) == UsbOutputMode.DIRECT_USB && BitPerfectStatus.isActive)
                "${BitPerfectStatus.sampleRate / 1000} kHz / ${BitPerfectStatus.bitDepth} bit"
            else usb.productName?.toString() ?: "USB AUDIO"
            btA2dpDev != null -> "A2DP" + if (btCodecText.isNotBlank()) " · 対応 $btCodecText" else " · codec unknown"
            btScoDev != null -> "SCO · 8/16 kHz"
            speaker != null -> am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
                ?.let { "${it / 1000} kHz" } ?: "SYSTEM"
            wired != null -> wired.productName?.toString() ?: "WIRED"
            else -> "—"
        }
        val proc = when {
            DauControl.active -> "ACTIVE"
            StreamingDspRuntime.state.enabled -> "DSP ACTIVE"
            else -> "BYPASS"
        }
        val engine = when {
            DauControl.active -> "${DauControl.backend} · ${"%.1f".format(DauControl.processingMs)} ms"
            StreamingDspRuntime.state.enabled -> StreamingDspRuntime.backend
            else -> "DIRECT"
        }
        val volume = if (usb != null && UsbOutputMode.load(this) == UsbOutputMode.DIRECT_USB)
            if (UsbVolumeControl.loadMode(this) == UsbVolumeControl.SOFTWARE) "APP" else "DAC"
        else "SYSTEM"
        fmt.text = codec
        bitsV.text = bits
        rateV.text = rate
        brV.text = br
        outV.text = output
        outDetailV.text = outputDetail
        procV.text = proc
        procV.setTextColor(if (DauControl.active || StreamingDspRuntime.state.enabled) uiColor(R.color.accent) else uiColor(R.color.text_muted))
        engineV.text = engine
        volV.text = volume
        stateV.text = when {
            p?.playerError != null -> "ERROR"
            p?.isPlaying == true -> "PLAYING"
            else -> "READY"
        }
        stateV.setTextColor(if (p?.playerError != null) uiColor(R.color.error) else uiColor(R.color.accent))
    }

    private fun ensureBluetoothCodec() {
        if (btProxyRequested) return
        val adapter = (getSystemService(BluetoothManager::class.java))?.adapter ?: return
        if (!adapter.isEnabled) return
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            if (!btPermissionRequested) {
                btPermissionRequested = true
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 77)
            }
            return
        }
        btProxyRequested = true
        adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val a2dp = proxy as? BluetoothA2dp ?: return
                btA2dp = a2dp
                btCodecText = runCatching {
                    a2dp.supportedCodecTypes.map { it.codecName }.distinct().joinToString("/")
                }.getOrDefault("")
                updateSignalDeck()
            }
            override fun onServiceDisconnected(profile: Int) {
                btA2dp = null
                btCodecText = ""
                btProxyRequested = false
            }
        }, BluetoothProfile.A2DP)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 77) {
            btProxyRequested = false
            updateSignalDeck()
        }
    }

    private fun refreshDacInfo() {
        tvDacStatus.text = getString(R.string.dac_scanning)
        tvDacList.text = ""
        backgroundExecutor.execute {
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: run {
                runOnUiThread { tvDacStatus.text = getString(R.string.dac_audio_service_unavailable) }
                return@execute
            }
            val devs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
            if (devs.isEmpty()) {
                runOnUiThread { tvDacStatus.text = "No USB DAC detected" }
                return@execute
            }
            val sb = StringBuilder()
            devs.forEach { d ->
                sb.append("■ ").append(d.productName ?: getString(R.string.unknown)).append('\n')
                val mas = try { am.getSupportedMixerAttributes(d) } catch (_: Exception) { emptyList() }
                mas.forEach { m ->
                    sb.append("    ${m.format.sampleRate}Hz / ")
                    sb.append(when (m.format.encoding) {
                        AudioFormat.ENCODING_PCM_16BIT -> "16-bit"
                        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24-bit"
                        AudioFormat.ENCODING_PCM_32BIT -> "32-bit"
                        AudioFormat.ENCODING_PCM_FLOAT -> "Float"
                        else -> m.format.encoding.toString()
                    })
                    sb.append(" / ")
                    sb.append(if (m.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT) "BIT-PERFECT" else "Mixing")
                    sb.append('\n')
                }
                sb.append('\n')
            }
            // Raw UAC2 descriptors are the only reliable place to see the
            // alternate settings that the native USB path can select.
            try {
                val driver = UsbAudioDevice.getInstance(applicationContext)
                val dev = driver.findUsbAudioDevice()
                val info = if (dev != null && driver.hasPermission(dev)) driver.openDevice(dev) else null
                if (info != null) {
                    sb.append("UAC2 RAW ALT SETTINGS\n")
                    sb.append("best alt=${info.bestAltSetting} best bits=${info.bestBitDepth}\n")
                    val alts = UsbAltSettingParser.parse(info.connection.rawDescriptors)
                    if (alts.isEmpty()) sb.append("  (no parsed format descriptors)\n")
                    alts.sortedBy { it.alt }.forEach { a ->
                        sb.append("  alt=${a.alt} ${a.bitDepth}bit subframe=${a.subframeSize}")
                        if (a.sampleRates.isNotEmpty()) {
                            sb.append(" rates=").append(a.sampleRates.joinToString(","))
                        }
                        sb.append('\n')
                    }
                }
            } catch (t: Throwable) {
                sb.append("UAC2 RAW ALT SETTINGS: ").append(t.message).append('\n')
            }
            runOnUiThread {
                tvDacStatus.text = "${devs.size} USB DAC(s) detected"
                tvDacList.text = sb.toString().trim()
            }
        }
    }

    private fun loadAppInfo() {
        val info = try { packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L)) } catch (_: Exception) { null }
        tvAppVersion.text = "USSBAP  v${info?.versionName ?: "?"}"
        tvAppInfo.text = "USB Streaming BitPerfect Audio Player\n\n" +
            "Build: 2026.11.22\n" +
            "Minimum: Android 14 (API 34)\n" +
            "Audio Engine: FFmpegKit + Media3\n" +
            "© 2026 USSBAP Project\n" +
            "License: GPL-3.0\n" +
            "Third-party notices: Settings → Licenses & Notices"
    }

    private fun showLoading() { loadingContainer.visibility = View.VISIBLE }
    private fun hideLoading() { loadingContainer.visibility = View.GONE }
    private fun formatTime(ms: Long): String { if (ms <= 0L) return "0:00"; val ts = ms / 1000; return String.format("%d:%02d", ts / 60, ts % 60) }

    private fun doSearchTab() {
        val q = etSearchInput.text.toString().trim(); if (q.isEmpty()) return
        SearchRecommendationStore.recordSearch(this, q)
        searchResultList.removeAllViews(); searchResultList.addView(TextView(this).apply { text = getString(R.string.search_searching); setTextColor(uiColor(R.color.text_disabled)); setPadding(0, 24, 0, 0); gravity = Gravity.CENTER }); searchResultScroll.visibility = View.VISIBLE
        if (q.contains("youtube.com/watch") || q.contains("youtu.be/") || q.contains("music.youtube.com")) { showLoading(); YtStreamHelper.fetchAndPlay(url = q, onSuccess = { r -> runOnUiThread { hideLoading(); playYtStream(r, q) } }, onError = { m -> runOnUiThread { hideLoading(); Toast.makeText(this, m, Toast.LENGTH_LONG).show() } }); return }
        val onR: (List<YtSearchItem>) -> Unit = { r -> runOnUiThread { searchResultList.removeAllViews(); if (r.isEmpty()) searchResultList.addView(TextView(this).apply { text = getString(R.string.search_no_results).replace("%s", q); setTextColor(uiColor(R.color.text_disabled)); setPadding(0, 24, 0, 0); gravity = Gravity.CENTER }) else { r.forEach { searchResultList.addView(buildSearchResultRow(it)) } } } }
        val onE: (String) -> Unit = { m -> runOnUiThread { searchResultList.removeAllViews(); searchResultList.addView(TextView(this).apply { text = getString(R.string.search_error).replace("%s", m); setTextColor(0xFFFF4444.toInt()); setPadding(0, 24, 0, 0); gravity = Gravity.CENTER }); Toast.makeText(this, m, Toast.LENGTH_LONG).show() } }
        YtSearchHelper.search(q, listOf("videos"), onR, onE)
    }

    private fun showRecommendations() {
        val history = historyEntries
        if (SearchRecommendationStore.recommendationQuery(this) == null) {
            history.take(3).forEach { SearchRecommendationStore.recordPlay(this, it.title, it.uploader) }
        }
        val seed = SearchRecommendationStore.recommendationQuery(this) ?: return
        searchResultList.removeAllViews()
        searchResultList.addView(sectionLabel("あなたへのおすすめ", "検索・再生・保存の傾向から選択"))
        searchResultList.addView(TextView(this).apply { text = "おすすめを分析中…"; setTextColor(uiColor(R.color.text_muted)); setPadding(0, dp(18), 0, dp(18)); gravity = Gravity.CENTER })
        searchResultScroll.visibility = View.VISIBLE
        YtSearchHelper.search(seed, listOf("videos"), onResults = { results -> runOnUiThread {
            if (searchContainer.visibility != View.VISIBLE || etSearchInput.text.isNotBlank()) return@runOnUiThread
            searchResultList.removeAllViews()
            searchResultList.addView(sectionLabel("あなたへのおすすめ", "検索・再生・保存の傾向から選択"))
            val known = history.map { it.url }.toSet()
            val usable=results.filterNot{SearchRecommendationStore.isDisliked(this,it.url)}
            val ranked = (usable.filterNot { it.url in known } + usable.filter { it.url in known }).distinctBy { it.url }.take(8)
            if (ranked.isEmpty()) searchResultList.addView(TextView(this).apply { text = "履歴が増えるとおすすめが表示されます"; setTextColor(uiColor(R.color.text_muted)); gravity = Gravity.CENTER; setPadding(0, dp(24), 0, 0) })
            else { ranked.forEach { searchResultList.addView(buildSearchResultRow(it)) }; ranked.firstOrNull()?.let { YtStreamHelper.prefetch(it.url) } }
        } }, onError = { runOnUiThread {
            if (history.isNotEmpty()) {
                searchResultList.removeAllViews(); searchResultList.addView(sectionLabel("最近の傾向", "通信できないため再生履歴から表示"))
                history.take(6).forEach { entry -> searchResultList.addView(buildSearchResultRow(YtSearchItem(entry.url, entry.title, entry.uploader, 0, entry.thumbnailUrl))) }
            }
        } }, isRecommendation = true)
    }

    private fun sectionLabel(title: String, subtitle: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(2), dp(2), dp(2), dp(12))
        addView(TextView(this@MainActivity).apply { text = title; setTextColor(uiColor(R.color.accent)); textSize = 17f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        addView(TextView(this@MainActivity).apply { text = subtitle; setTextColor(uiColor(R.color.text_muted)); textSize = 11f; setPadding(0, dp(3), 0, 0) })
    }

    private fun buildSearchResultRow(item: YtSearchItem): View {
        val card = MaterialCardView(this).apply { radius = dp(16).toFloat(); setCardBackgroundColor(uiColor(R.color.surface)); strokeColor = uiColor(R.color.divider); strokeWidth = dp(1); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) } }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(10)) }
        val summary = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val art = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(dp(92), dp(68)); scaleType = ImageView.ScaleType.CENTER_CROP; setImageResource(R.drawable.ic_default_art) }
        if (item.thumbnailUrl.isNotEmpty()) searchImageExecutor.execute { try { val connection = java.net.URL(item.thumbnailUrl).openConnection(); connection.connectTimeout = 5000; connection.readTimeout = 5000; connection.getInputStream().use { BitmapFactory.decodeStream(it) }?.let { bitmap -> art.post { art.setImageBitmap(bitmap) } } } catch (_: Exception) {} }
        summary.addView(art)
        val textColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) } }
        textColumn.addView(TextView(this).apply { text = item.title; setTextColor(uiColor(R.color.text_primary)); textSize = 15f; setTypeface(typeface, android.graphics.Typeface.BOLD); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        textColumn.addView(TextView(this).apply { val parts = mutableListOf<String>(); if (item.uploader.isNotEmpty()) parts.add(item.uploader); if (item.durationSeconds > 0) parts.add(formatTime(item.durationSeconds * 1000)); text = parts.joinToString("  ·  "); setTextColor(uiColor(R.color.text_secondary)); textSize = 12f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(5), 0, 0) })
        summary.addView(textColumn); body.addView(summary)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        fun action(label: String, onClick: () -> Unit) = MaterialButton(this).apply { text = label; textSize = 11f; minWidth = 0; minimumWidth = 0; setPadding(dp(4), 0, dp(4), 0); layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) }; setOnClickListener { onClick() } }
        actions.addView(action("▶ 再生") { SearchRecommendationStore.recordPlay(this, item.title, item.uploader); playStreamUrl(item.url, item.title) })
        actions.addView(action("⏭ 次") { SearchRecommendationStore.recordQueue(this, item.title, item.uploader); enqueueSearchResult(item, true) })
        actions.addView(action("＋ キュー") { SearchRecommendationStore.recordQueue(this, item.title, item.uploader); enqueueSearchResult(item, false) })
        actions.addView(action("☆ 保存") { SearchRecommendationStore.recordSave(this, item.title, item.uploader); YtStreamHelper.prefetch(item.url); showAddYtToPlaylistDialog(StreamHistoryStore.Entry(item.url, item.title, item.uploader, item.thumbnailUrl)) })
        actions.addView(action("↓ DL") { requestSecureDownload(item.url, null) })
        body.addView(actions); card.addView(body); card.setOnClickListener { SearchRecommendationStore.recordPlay(this, item.title, item.uploader); playStreamUrl(item.url, item.title) }
        card.setOnLongClickListener{SearchRecommendationStore.dislike(this,item.url,item.title);(card.parent as? ViewGroup)?.removeView(card);Toast.makeText(this,"おすすめから除外しました",Toast.LENGTH_SHORT).show();true}
        return card
    }

    private fun enqueueSearchResult(item: YtSearchItem, playNext: Boolean) {
        val player = controller ?: return
        Toast.makeText(this, if (playNext) "次に再生へ追加中…" else "キューへ追加中…", Toast.LENGTH_SHORT).show()
        YtStreamHelper.fetchAndPlay(item.url, onSuccess = { result -> runOnUiThread { val media = buildStreamMediaItem(result); if (playNext) player.addMediaItem((player.currentMediaItemIndex + 1).coerceIn(0, player.mediaItemCount), media) else player.addMediaItem(media); Toast.makeText(this, if (playNext) "次に再生へ追加しました" else "キューへ追加しました", Toast.LENGTH_SHORT).show() } }, onError = { message -> runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() } })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()
    private fun uiColor(id: Int): Int = androidx.core.content.ContextCompat.getColor(this, id)

    private fun buildLegacySearchResultRow(item: YtSearchItem): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 10, 0, 10) }
        val art = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(64, 64); scaleType = ImageView.ScaleType.CENTER_CROP; setImageResource(R.drawable.ic_default_art) }
        if (item.thumbnailUrl.isNotEmpty()) { backgroundExecutor.execute { try { val c = java.net.URL(item.thumbnailUrl).openConnection(); c.connectTimeout = 5000; c.readTimeout = 5000; val s = c.getInputStream(); val b = BitmapFactory.decodeStream(s); s.close(); art.post { art.setImageBitmap(b) } } catch (_: Exception) {} } }
        row.addView(art)
        val tc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 10 } }
        tc.addView(TextView(this).apply { text = item.title; setTextColor(uiColor(R.color.text_primary)); textSize = 14f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        tc.addView(TextView(this).apply { val p = mutableListOf<String>(); if (item.uploader.isNotEmpty()) p.add(item.uploader); if (item.durationSeconds > 0) p.add(formatTime(item.durationSeconds * 1000)); text = p.joinToString(" · "); setTextColor(uiColor(R.color.text_disabled)); textSize = 12f; maxLines = 1 })
        row.addView(tc)
        row.setOnClickListener { playStreamUrl(item.url, item.title) }
        row.setOnLongClickListener { showAddYtToPlaylistDialog(StreamHistoryStore.Entry(item.url, item.title, item.uploader, item.thumbnailUrl)); true }
        return row
    }

    private fun playStreamUrl(url: String, cachedTitle: String? = null) { streamPlaybackRequestedAt=android.os.SystemClock.elapsedRealtime();showLoading(); YtStreamHelper.fetchAndPlay(url = url, onSuccess = { r -> runOnUiThread { hideLoading(); playYtStream(r, url) } }, onError = { m -> runOnUiThread { streamPlaybackRequestedAt=0;hideLoading(); Toast.makeText(this, m, Toast.LENGTH_LONG).show() } }) }

    private fun playYtStream(result: YtAudioResult, sourceUrl: String? = null) {
        val p = controller ?: return
        val requestCompletedAt = android.os.SystemClock.elapsedRealtime()
        currentStreamSourceUrl = sourceUrl ?: "https://www.youtube.com/watch?v=${result.videoId}"
        currentStreamResult = result
        p.setMediaItem(buildStreamMediaItem(result, currentStreamSourceUrl!!))
        currentAlbumSongs = emptyList()
        // Streaming playback is audio-only. Thumbnail artwork is the only
        // visual; no video renderer or MV surface is attached.
        p.prepare(); p.play()
        AppLogger.i("STREAM_PERF", "resolved-to-prepare video=${result.videoId} at=$requestCompletedAt")
        Toast.makeText(this, "${result.title}\n${result.bestBitrate}bps", Toast.LENGTH_SHORT).show()
        StreamHistoryStore.add(this, StreamHistoryStore.Entry(sourceUrl ?: result.audioUrl, result.title, result.uploader, result.thumbnailUrl))
        SearchRecommendationStore.recordPlay(this, result.title, result.uploader)
    }

    private fun playQueueFromVideoIds(videoIds: List<String>) {
        if (videoIds.isEmpty()) return
        val pl = Playlist(System.currentTimeMillis(), "Shared: ${videoIds.size} tracks", videoIds.map { vid -> MusicItem(id = vid.hashCode().toLong(), title = "Loading...", artist = "", album = "Shared", trackNumber = 0, duration = 0, contentUri = "https://www.youtube.com/watch?v=$vid".toUri(), albumArtUri = null, format = "YT_STREAM") }.toMutableList())
        playlists.add(0, pl); savePlaylists()
        if (pl.songs.isNotEmpty()) { currentAlbumSongs = pl.songs; playMusicInContext(pl.songs[0]) }
        Toast.makeText(this, "Added ${videoIds.size} tracks", Toast.LENGTH_SHORT).show()
    }

    private fun showAddYtToPlaylistDialog(entry: StreamHistoryStore.Entry) { if (playlists.isEmpty()) { showCreateYtPlaylistDialog(entry); return }; val names = playlists.map { it.name }.toMutableList(); val n = names.size; names.add("＋ New Playlist"); AlertDialog.Builder(this).setTitle("Add \"${entry.title}\" to Playlist").setItems(names.toTypedArray()) { _, which -> if (which == n) showCreateYtPlaylistDialog(entry) else addYtToPlaylist(playlists[which], entry) }.show() }
    private fun showCreateYtPlaylistDialog(entry: StreamHistoryStore.Entry) { val i = EditText(this); i.hint = getString(R.string.playlist_create_hint); AlertDialog.Builder(this).setTitle(getString(R.string.playlist_create_title)).setView(i).setPositiveButton(getString(R.string.playlist_create)) { _, _ -> val n = i.text.toString().trim(); if (n.isNotEmpty()) { createPlaylist(n, null); addYtToPlaylist(playlists.last(), entry) } }.setNegativeButton("Cancel", null).show() }
    private fun addYtToPlaylist(pl: Playlist, entry: StreamHistoryStore.Entry) { val item = MusicItem(id = entry.url.hashCode().toLong(), title = entry.title, artist = entry.uploader, album = "YouTube", trackNumber = 0, duration = 0, contentUri = entry.url.toUri(), albumArtUri = entry.thumbnailUrl.takeIf { it.isNotEmpty() }?.toUri(), format = "YT_STREAM"); if (pl.songs.any { it.contentUri == item.contentUri }) { Toast.makeText(this, getString(R.string.playlist_already_in).replace("%s", pl.name), Toast.LENGTH_SHORT).show(); return }; pl.songs.add(item); savePlaylists(); refreshPlaylistList(); YtStreamHelper.prefetch(entry.url); Toast.makeText(this, getString(R.string.playlist_added).replace("%s", pl.name), Toast.LENGTH_SHORT).show() }

    private fun updateFormatInfo(item: MediaItem) {
        if (item.mediaId == "YT_STREAM") {
            tvFullFormat.text = "Streaming"
            tvFullBitrate.text = ""
            return
        }
        if (item.mediaId == "DSD_FILE") {
            val extras = item.mediaMetadata.extras
            val rate = extras?.getInt("dsd_rate", 0) ?: 0
            val container = extras?.getString("dsd_container") ?: "DSD"
            val label = extras?.getString("dsd_label") ?: DsdInfo.dsdRateLabel(rate)
            tvFullFormat.text = "$container / $label / 1-bit"
            tvFullBitrate.text = buildString {
                if (rate > 0) append(String.format("%.3f MHz", rate / 1_000_000.0))
                if (isNotEmpty()) append(" · ")
                append("PCM変換再生")
            }
            return
        }
        val uri = item.localConfiguration?.uri ?: item.mediaMetadata.artworkUri ?: return
        val key = uri.toString()
        formatCache[key]?.let { applyFormat(it); return }
        if (key == lastFormatUri) return
        lastFormatUri = key
        backgroundExecutor.execute {
            val f = extractTrackFormat(uri) ?: return@execute
            formatCache[key] = f
            if (key == lastFormatUri) runOnUiThread { applyFormat(f) }
        }
    }
    private fun applyFormat(f: TrackFormat) { val p = mutableListOf<String>(); if (f.codec.isNotEmpty()) p.add(f.codec); if (f.bitDepth != null) p.add("${f.bitDepth}bit"); if (f.sampleRate > 0) p.add(formatSampleRate(f.sampleRate)); tvFullFormat.text = if (p.isEmpty()) getString(R.string.unknown) else p.joinToString(" / "); tvFullBitrate.text = if (f.bitrateKbps > 0) "${f.bitrateKbps} kbps" else "" }
    private fun formatSampleRate(hz: Int): String = if (hz % 1000 == 0) "${hz / 1000}kHz" else String.format("%.1fkHz", hz / 1000.0)
    private fun repeatLabel(mode: Int): String = when (mode) { Player.REPEAT_MODE_OFF -> "Repeat: Off"; Player.REPEAT_MODE_ALL -> "Repeat: All"; Player.REPEAT_MODE_ONE -> "Repeat: One"; else -> "Repeat: ?" }
    private fun updateRepeatButton() { val p = controller ?: return; val tv = findViewById<TextView>(R.id.btnFullRepeat); tv.text = when (p.repeatMode) { Player.REPEAT_MODE_OFF -> "Repeat"; Player.REPEAT_MODE_ALL -> "Repeat All"; else -> "Repeat One" }; tv.setTextColor(if (p.repeatMode == Player.REPEAT_MODE_OFF) uiColor(R.color.text_disabled) else uiColor(R.color.text_primary)) }
    private fun updateShuffleButton() { val p = controller ?: return; findViewById<TextView>(R.id.btnFullShuffle).setTextColor(if (p.shuffleModeEnabled) uiColor(R.color.text_primary) else uiColor(R.color.text_disabled)) }
    private fun hasUsbAudioOutput(): Boolean {
        val am = getSystemService(AudioManager::class.java)
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY }
    }
    private fun updateUsbModeAvailability() {
        val direct = findViewById<View>(R.id.radioUsbDirect) ?: return
        val hal = findViewById<View>(R.id.radioUsbHal) ?: return
        val group = findViewById<android.widget.RadioGroup>(R.id.radioUsbOutputMode) ?: return
        val dac = hasUsbAudioOutput()
        direct.isEnabled = dac; hal.isEnabled = dac
        direct.alpha = if (dac) 1f else .38f; hal.alpha = if (dac) 1f else .38f
        if (!dac) {
            if (group.checkedRadioButtonId != R.id.radioUsbOff) {
                updatingUsbModeUi = true
                group.check(R.id.radioUsbOff)
                AudioPlaybackService.usbSinkConfig.bitPerfectEnabled = false
                startService(Intent(this, AudioPlaybackService::class.java).setAction(AudioPlaybackService.ACTION_REFRESH_USB_EXCLUSIVE))
                findViewById<TextView>(R.id.tvUsbModeStatus)?.text = "USB DAC NOT CONNECTED · SYSTEM OUTPUT"
                updatingUsbModeUi = false
            }
            return
        }
        val saved = UsbOutputMode.load(this)
        if (group.checkedRadioButtonId == R.id.radioUsbOff && saved != UsbOutputMode.OFF) {
            updatingUsbModeUi = true
            when (saved) {
                UsbOutputMode.DIRECT_USB -> group.check(R.id.radioUsbDirect)
                UsbOutputMode.HAL_API -> group.check(R.id.radioUsbHal)
                UsbOutputMode.OFF -> group.check(R.id.radioUsbOff)
            }
            AudioPlaybackService.usbSinkConfig.bitPerfectEnabled = saved == UsbOutputMode.DIRECT_USB
            startService(Intent(this, AudioPlaybackService::class.java).setAction(AudioPlaybackService.ACTION_REFRESH_USB_EXCLUSIVE))
            findViewById<TextView>(R.id.tvUsbModeStatus)?.text = when (saved) {
                UsbOutputMode.DIRECT_USB -> "ACTIVE ROUTE: DIRECT UAC2 · ANDROID MIXER BYPASS"
                UsbOutputMode.HAL_API -> "ACTIVE ROUTE: ANDROID USB HAL · LOSSLESS API"
                UsbOutputMode.OFF -> "ACTIVE ROUTE: SYSTEM MIXER"
            }
            updatingUsbModeUi = false
        }
    }
    private fun updateBpIndicator() {
        val mode = UsbOutputMode.load(this)
        val dac = hasUsbAudioOutput()
        val route = when (mode) {
            UsbOutputMode.DIRECT_USB -> when {
                BitPerfectStatus.isActive -> "DIRECT USB · ACTIVE · ${BitPerfectStatus.sampleRate / 1000.0}k/${BitPerfectStatus.bitDepth}"
                dac -> "DIRECT USB · DAC FOUND · IDLE"
                else -> "DIRECT USB · NO DAC"
            }
            UsbOutputMode.HAL_API -> when {
                BitPerfectStatus.isActive -> "USB HAL · ACTIVE · ${BitPerfectStatus.sampleRate / 1000.0}k/${BitPerfectStatus.bitDepth}"
                dac -> "USB HAL · DAC FOUND · READY"
                else -> "USB HAL · NO DAC"
            }
            UsbOutputMode.OFF -> "SYSTEM OUTPUT"
        }
        val dsd = BitPerfectStatus.dsdTransport
        bpIndicator.text = buildString {
            append(route)
            if (dsd.isNotEmpty()) append(" · ").append(dsd)
            if (DauControl.active) append(" · DAU")
        }
        bpIndicator.setTextColor(if (BitPerfectStatus.isActive || DauControl.active) uiColor(R.color.text_primary) else uiColor(R.color.text_secondary))
    }
    private fun updateDauTelemetry() {
        val view = findViewById<TextView>(R.id.tvDauEffect) ?: return
        val effective = (DauControl.strength.coerceIn(0f, 1f) * DauControl.strength.coerceIn(0f, 1f) * 100).roundToInt()
        val prefix = if (DauControl.modelVersion == DauControl.MODEL_V1) "CLASSIC DAU" else "AI EFFECT"
        view.text = when {
            !DauControl.userEnabled.get() -> "$prefix: OFF"
            !DauControl.streamingActive.get() -> "$prefix: STREAM / COMPRESSED STANDBY · EFFECTIVE $effective%"
            !DauControl.active -> "$prefix: BYPASS (DSP ACTIVE)"
            DauControl.inferenceActive -> "$prefix: LIVE · EFFECTIVE $effective% · HF +${"%.2f".format(DauControl.highBandLiftDb)} dB · ${"%.1f".format(DauControl.processingMs)} ms\n" +
                "AI RES ${"%.4f".format(DauControl.neuralResidualPeak)} · GATE ${DauControl.openedGateBins} · CUTOFF ${"%.1f".format(DauControl.detectedCutoffHz / 1000f)}k · GEN ${"%.6f".format(DauControl.generatedCandidateRms)} > ${"%.6f".format(DauControl.generatedOutputRms)}"
            else -> "$prefix: WARMING UP"
        }
        findViewById<TextView>(R.id.tvDauLatency)?.text =
            "LOOKAHEAD ${DauControl.lookaheadHops} hop · +${"%.1f".format(DauControl.lookaheadLatencyMs)} ms · MISS ${DauControl.residualMisses} · UNDERRUN ${DauControl.audioUnderruns} · SMOOTH ${"%.2f".format(DauControl.residualSmoothing)}"
    }
    private fun updateLogDisplay(full: String) { val f = logFilter; tvAppLogs.text = if (f == null) full else full.split("\n---\n").filter { f(it) }.joinToString("\n---\n").trim().ifEmpty { "(no matching logs)" } }

    private fun extractTrackFormat(uri: Uri): TrackFormat? = try {
        val ip = FFmpegKitConfig.getSafParameterForRead(this, uri) ?: return null
        val info = FFprobeKit.getMediaInformation(ip).mediaInformation ?: return null
        val st = info.streams?.find { it.type == "audio" } ?: info.streams?.firstOrNull() ?: return null
        val c = st.codec ?: ""
        val sr = st.sampleRate?.toIntOrNull() ?: 0
        val ch = st.getNumberProperty("channels")?.toInt() ?: 0
        val rawBits = st.getStringProperty("bits_per_raw_sample")?.toIntOrNull()
            ?: st.getStringProperty("bits_per_sample")?.toIntOrNull()
        val bd = when {
            rawBits != null && rawBits in setOf(8, 16, 24, 32) -> rawBits
            st.getStringProperty("sample_fmt")?.startsWith("s16") == true -> 16
            st.getStringProperty("sample_fmt")?.startsWith("s32") == true -> 32
            else -> null
        }
        val bitrate = st.bitrate?.toLongOrNull()?.takeIf { it > 0 }
            ?: run {
                val durationSec = info.duration?.toDoubleOrNull() ?: 0.0
                val size = runCatching {
                    contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
                }.getOrDefault(0L)
                if (durationSec > 0 && size > 0) (size * 8 / durationSec).toLong() else 0L
            }
        val pcmRate = if (bd != null && sr > 0 && ch > 0) sr.toLong() * ch * bd else 0L
        val kbps = when {
            bitrate > 0 -> (bitrate / 1000).toInt()
            pcmRate > 0 -> (pcmRate / 1000).toInt()
            else -> 0
        }
        val cn = when (c.lowercase()) {
            "flac" -> "FLAC"; "alac" -> "ALAC"; "mp3" -> "MP3"; "aac" -> "AAC"
            "opus" -> "OPUS"; "vorbis" -> "OGG"; "wmav1", "wmav2" -> "WMA"
            "pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_f32le", "pcm_u8", "pcm_s8" -> "WAV"
            else -> c.uppercase()
        }
        TrackFormat(cn, sr, ch, bd, kbps)
    } catch (_: Exception) { null }

    private fun testYtDlp() {
        Thread {
            val sb = StringBuilder(); sb.appendLine("=== yt-dlp direct Python call ===")
            try {
                val py = Python.getInstance()
                py.getModule("sys")?.get("path")?.callAttr("insert", 0, cacheDir.absolutePath)
                val code = """
import yt_dlp, io, sys, json
captured = io.StringIO()
old_stdout = sys.stdout
sys.stdout = captured
try:
    opts = {
        'format': 'bestaudio',
        'noplaylist': True,
        'skip_download': True,
        'dump_single_json': True,
        'outtmpl': '$cacheDir/%(title)s.%(ext)s',
        'quiet': True,
        'no_warnings': True,
    }
    with yt_dlp.YoutubeDL(opts) as ydl:
        ydl.download(['https://music.youtube.com/watch?v=iC3pkl1nUVM'])
finally:
    sys.stdout = old_stdout
result = captured.getvalue()
""".trimIndent()
                val globals = py.getModule("builtins")?.callAttr("dict")
                py.getModule("builtins")?.callAttr("exec", code, globals)
                val captured = globals?.callAttr("get", "result", "")?.toString() ?: ""
                sb.appendLine("captured JSON (" + captured.length + " chars):")
                sb.appendLine(captured.take(2000))
            } catch (ex: Exception) {
                sb.appendLine("EXCEPTION: " + ex.javaClass.name + ": " + ex.message)
                sb.appendLine(ex.stackTraceToString().take(500))
            }
            File(cacheDir, "ytdlp_test.log").writeText(sb.toString())
            runOnUiThread { Toast.makeText(this, "Test done. " + sb.length + " chars.", Toast.LENGTH_LONG).show() }
        }.apply { name = "YtDlpTest"; start() }
        AlertDialog.Builder(this).setTitle("yt-dlp Direct Test").setMessage("Bypassing library...").setPositiveButton("OK", null).show()
    }

    private fun applyAccentColor() {
        val colorMap = mapOf(
            "purple" to uiColor(R.color.accent), "red" to 0xFFFF5D66.toInt(),
            "green" to 0xFF4CAF50.toInt(), "blue" to 0xFF1976D2.toInt(),
            "cyan" to 0xFF0097A7.toInt(), "magenta" to 0xFFC2185B.toInt(),
            "yellow" to 0xFFF9A825.toInt(), "gray" to uiColor(R.color.text_muted)
        )
        val accent = getSharedPreferences("BitPerfectPrefs", MODE_PRIVATE).getString("accent", "purple") ?: "purple"
        val color = colorMap[accent] ?: uiColor(R.color.accent)
        findViewById<Toolbar>(R.id.toolbar)?.setBackgroundColor(uiColor(R.color.app_bg_alt))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = uiColor(R.color.app_bg)
        }
        // Tint all buttons in the entire app
        val tintList = android.content.res.ColorStateList.valueOf(color)
        val rootView = findViewById<View>(android.R.id.content)
        tintButtonsRecursive(rootView, tintList)
    }

    private fun tintButtonsRecursive(view: View, tint: android.content.res.ColorStateList) {
        if (view is com.google.android.material.button.MaterialButton || view is android.widget.Button) {
            view.backgroundTintList = android.content.res.ColorStateList.valueOf(uiColor(R.color.surface_alt))
            view.setTextColor(tint.defaultColor)
        } else if (view is com.google.android.material.tabs.TabLayout) {
            view.setSelectedTabIndicatorColor(tint.defaultColor)
            view.setTabTextColors(uiColor(R.color.text_disabled), tint.defaultColor)
        } else if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                tintButtonsRecursive(view.getChildAt(i), tint)
            }
        }
    }

    private fun darken(color: Int, factor: Float): Int {
        val a = (color shr 24) and 0xFF
        val r = ((color shr 16) and 0xFF) * factor
        val g = ((color shr 8) and 0xFF) * factor
        val b = (color and 0xFF) * factor
        return (a shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
    }

    private fun showAccentDialog() {
        val accents = mapOf(
            "purple" to 0xFF6750A4.toInt(), "red" to 0xFFB3261E.toInt(),
            "green" to 0xFF4CAF50.toInt(), "blue" to 0xFF1976D2.toInt(),
            "cyan" to 0xFF0097A7.toInt(), "magenta" to 0xFFC2185B.toInt(),
            "yellow" to 0xFFF9A825.toInt(), "gray" to uiColor(R.color.text_muted)
        )
        val names = accents.keys.toTypedArray()
        val prefs = getSharedPreferences("BitPerfectPrefs", MODE_PRIVATE)
        val current = prefs.getString("accent", "purple") ?: "purple"
        val checked = names.indexOf(current)
        AlertDialog.Builder(this).setTitle(getString(R.string.accent_color))
            .setSingleChoiceItems(names.map { it.replaceFirstChar { it.uppercase() } }.toTypedArray(), checked) { d, which ->
                prefs.edit().putString("accent", names[which]).apply()
                d.dismiss()
                recreate()
            }.show()
    }

    private fun showLanguageDialog() {
        val items = arrayOf("System Default", "English", "日本語")
        val codes = arrayOf("", "en", "ja")
        AlertDialog.Builder(this).setTitle(getString(R.string.language_title))
            .setItems(items) { _, which ->
                val localeList = if (codes[which].isEmpty()) {
                    androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                } else {
                    androidx.core.os.LocaleListCompat.forLanguageTags(codes[which])
                }
                AppCompatDelegate.setApplicationLocales(localeList)
                recreate()
            }.show()
    }

    private fun showCreditsDialog() {
        val credits = listOf(
            "Audio Engine" to listOf(
                "FFmpegKit" to "Decoding / encoding / transcoding",
                "Media3 / ExoPlayer" to "Local and streaming playback",
                "Chaquopy / Python 3.13" to "Embedded Python runtime"
            ),
            "USB Audio" to listOf(
                "Decent USB Audio" to "UAC2 direct / USB HAL output",
                "Android AudioTrack" to "Low-latency system output",
                "libFLAC" to "Native FLAC decoding"
            ),
            "Audio Processing" to listOf(
                "Signal Lab DAU" to "Temporal dynamic audio processing",
                "Signal Lab DSP" to "Streaming EQ / compressor",
                "ONNX Runtime" to "DAU inference runtime"
            ),
            "YouTube Integration" to listOf(
                "yt-dlp" to "Stream extraction and playlist metadata",
                "NewPipe Extractor" to "YouTube search",
                "YouTube Music / Innertube" to "Album, playlist and mix metadata",
                "OkHttp" to "HTTP/2 networking"
            ),
            "Lyrics" to listOf(
                "LRCLIB" to "Plain and synchronized lyrics",
                "lyrics.ovh" to "Fallback lyrics"
            ),
            "Security" to listOf(
                "EncryptedSharedPreferences" to "Secure cookie storage",
                "Android Keystore" to "Key protection"
            ),
            "UI Framework" to listOf(
                "Material Design 3" to "Design system",
                "AppCompat" to "Backward compatibility",
                "Kotlin" to "Modern JVM language"
            ),
            "Build System" to listOf(
                "Gradle (Kotlin DSL)" to "Build automation",
                "Android SDK" to "Platform APIs and tooling"
            ),
            "License" to listOf("GPL-3.0" to "Free Software License")
        )
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 32) }
        scroll.addView(container)
        credits.forEach { (section, items) ->
            container.addView(TextView(this).apply { text = "■ $section"; textSize = 15f; setTextColor(uiColor(R.color.text_primary)); setPadding(0, 16, 0, 4); setTypeface(null, android.graphics.Typeface.BOLD) })
            items.forEach { (name, desc) ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 4, 0, 4) }
                row.addView(TextView(this).apply { text = name; textSize = 13f; setTextColor(uiColor(R.color.text_secondary)) })
                row.addView(TextView(this).apply { text = desc; textSize = 11f; setTextColor(uiColor(R.color.text_muted)) })
                container.addView(row)
            }
        }
        AlertDialog.Builder(this).setTitle(getString(R.string.settings_credits)).setView(scroll).setPositiveButton(getString(R.string.dialog_close), null).show()
    }

    private fun showLicensesDialog() {
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 16, 40, 32)
        }
        scroll.addView(container)
        container.addView(TextView(this).apply {
            text = getString(R.string.licenses_intro)
            textSize = 12f
            setTextColor(uiColor(R.color.text_muted))
            setPadding(0, 0, 0, 8)
        })

        addLicenseSection(container, getString(R.string.licenses_app_section))
        addLicenseRow(container, getString(R.string.licenses_app_license),
            getString(R.string.licenses_app_copyright)) {
            showLicenseAsset("GPL-3.0", "licenses/GPL-3.0.txt")
        }
        val sourceUrl = getString(R.string.app_source_url).trim()
        val sourceAvailable = sourceUrl.isNotBlank() && sourceUrl != "https://github.com/"
        addLicenseRow(container, getString(R.string.licenses_source),
            if (sourceAvailable) sourceUrl else getString(R.string.licenses_source_pending)) {
            if (sourceAvailable) openUrl(sourceUrl)
        }

        addLicenseSection(container, getString(R.string.licenses_third_party_section))
        addLicenseRow(container, "NewPipe Extractor 0.26.4",
            "GPL-3.0-or-later · NewPipe e.V. and contributors") {
            showLicenseAsset("GPL-3.0", "licenses/GPL-3.0.txt")
        }
        addLicenseRow(container, "FFmpegKit Maintained 8.1.7",
            "LGPL-3.0 · FFmpegKit contributors / FFmpeg developers") {
            showLicenseAsset("LGPL-3.0", "licenses/LGPL-3.0.txt")
        }
        addLicenseRow(container, "AndroidX / Media3 / Material / OkHttp / Kotlin",
            "Apache-2.0 · Android Open Source Project / Google / Square / JetBrains") {
            showLicenseAsset("Apache-2.0", "licenses/Apache-2.0.txt")
        }
        addLicenseRow(container, "ONNX Runtime 1.26.0", "MIT · Microsoft Corporation") {
            showLicenseAsset("MIT", "licenses/MIT.txt")
        }
        addLicenseRow(container, "yt-dlp-android 2.0.2 / Chaquopy 17.0.0",
            "MIT · ffmpegkit-maintained / Chaquo Ltd") {
            showLicenseAsset("MIT", "licenses/MIT.txt")
        }
        addLicenseRow(container, "yt-dlp", "Unlicense · public domain") {
            showLicenseAsset("Unlicense", "licenses/Unlicense.txt")
        }
        addLicenseRow(container, "Python 3.13", "PSF-2.0 · Python Software Foundation") {
            showLicenseAsset("PSF-2.0", "licenses/PSF-2.0.txt")
        }
        addLicenseRow(container, "Decent USB Audio / DecentPlayer", "MIT · Marcelo Silva") {
            showLicenseAsset("MIT", "licenses/MIT.txt")
        }
        addLicenseRow(container, "libFLAC / protobuf-javalite",
            "BSD-3-Clause · Xiph.Org Foundation / Google LLC") {
            showLicenseAsset("BSD-3-Clause", "licenses/BSD-3-Clause.txt")
        }
        addLicenseRow(container, "Rhino 1.8.1", "MPL-2.0 · Mozilla Foundation and contributors") {
            showLicenseAsset("MPL-2.0", "licenses/MPL-2.0.txt")
        }
        addLicenseRow(container, "zlib", "zlib License · Jean-loup Gailly and Mark Adler") {
            showLicenseAsset("zlib", "licenses/Zlib.txt")
        }
        addLicenseRow(container, getString(R.string.licenses_notice), "THIRD_PARTY_NOTICES.txt") {
            showLicenseAsset(getString(R.string.licenses_notice), "licenses/THIRD_PARTY_NOTICES.txt")
        }

        addLicenseSection(container, getString(R.string.licenses_ffmpeg_section))
        val ffmpegLicenseResources = listOf(
            "license", "license_cpu_features", "license_dav1d", "license_expat",
            "license_fontconfig", "license_freetype", "license_fribidi", "license_harfbuzz",
            "license_kvazaar", "license_lame", "license_libaom", "license_libass",
            "license_libiconv", "license_libilbc", "license_libogg", "license_libpng",
            "license_libsndfile", "license_libtheora", "license_libuuid",
            "license_libvorbis", "license_libvpx", "license_libxml2",
            "license_opencore_amr", "license_openh264", "license_opus", "license_sdl",
            "license_shine", "license_snappy", "license_soxr", "license_speex",
            "license_twolame", "license_vo_amrwbenc", "license_zimg"
        )
        var ffmpegLicenseCount = 0
        ffmpegLicenseResources.forEach { name ->
            val id = resources.getIdentifier(name, "raw", packageName)
            if (id == 0) return@forEach
            ffmpegLicenseCount++
            val label = if (name == "license") "FFmpegKit / FFmpeg"
            else name.removePrefix("license_").replace('_', ' ')
            addLicenseRow(container, label, "Bundled license text") {
                val text = runCatching {
                    resources.openRawResource(id).bufferedReader().use { it.readText() }
                }.getOrElse { "License text not found.\n${it.message}" }
                showLicenseText(label, text)
            }
        }
        if (ffmpegLicenseCount == 0) {
            container.addView(TextView(this).apply {
                text = "FFmpegKit license resources were not found in this build."
                textSize = 12f
                setTextColor(uiColor(R.color.text_muted))
                setPadding(12, 4, 12, 4)
            })
        }

        addLicenseSection(container, getString(R.string.licenses_services_section))
        addLicenseRow(container, "YouTube / YouTube Music", "https://www.youtube.com/t/terms") {
            openUrl("https://www.youtube.com/t/terms")
        }
        addLicenseRow(container, "LRCLIB", "https://lrclib.net/") {
            openUrl("https://lrclib.net/")
        }
        addLicenseRow(container, "lyrics.ovh", "https://lyrics.ovh/") {
            openUrl("https://lyrics.ovh/")
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_licenses))
            .setView(scroll)
            .setPositiveButton(getString(R.string.dialog_close), null)
            .show()
    }

    private fun addLicenseSection(parent: LinearLayout, title: String) {
        parent.addView(TextView(this).apply {
            text = "■ $title"
            textSize = 14f
            setTextColor(uiColor(R.color.text_primary))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 18, 0, 4)
        })
    }

    private fun addLicenseRow(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        onClick: (() -> Unit)? = null
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 8, 12, 8)
            isClickable = onClick != null
            isFocusable = onClick != null
            if (onClick != null) {
                val out = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
                setBackgroundResource(out.resourceId)
                setOnClickListener { onClick() }
            }
        }
        row.addView(TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(uiColor(R.color.text_secondary))
        })
        row.addView(TextView(this).apply {
            text = subtitle
            textSize = 11f
            setTextColor(uiColor(R.color.text_muted))
            setPadding(0, 2, 0, 0)
        })
        parent.addView(row)
    }

    private fun showLicenseAsset(title: String, path: String) {
        val text = runCatching {
            assets.open(path).bufferedReader().use { it.readText() }
        }.getOrElse { "License text not found: $path\n${it.message}" }
        showLicenseText(title, text)
    }

    private fun showLicenseText(title: String, text: String) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(40, 24, 40, 24)
        }
        val scroll = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(getString(R.string.dialog_close), null)
            .show()
    }

    private fun openUrl(url: String) {
        if (url.isBlank() || url == "https://github.com/") return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun showStreamUrlDialog() {
        val i = EditText(this).apply { hint = getString(R.string.paste_url_hint); setSingleLine(true) }
        AlertDialog.Builder(this).setTitle(getString(R.string.paste_url_title)).setView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0); addView(i) }).setPositiveButton(getString(R.string.paste_url_play)) { _, _ -> val u = i.text.toString().trim(); if (u.isNotEmpty()) playStreamUrl(u) }.setNegativeButton(getString(R.string.dialog_cancel), null).show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (UsbOutputMode.load(this) == UsbOutputMode.DIRECT_USB &&
            UsbVolumeControl.loadMode(this) == UsbVolumeControl.SOFTWARE) {
            val seek = usbSoftwareVolumeSeek
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> if (seek != null) { seek.progress = (seek.progress + 2).coerceAtMost(100); return true }
                KeyEvent.KEYCODE_VOLUME_DOWN -> if (seek != null) { seek.progress = (seek.progress - 2).coerceAtLeast(0); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStop() { usbSoftwareVolumeSeek = null; controllerFuture?.let { MediaController.releaseFuture(it) }; super.onStop() }

    /** Throttled log UI refresh; the full history stays in AppLogger. */
    private fun onLogUpdated(text: String) {
        if (!::logUiContainer.isInitialized || logUiContainer.visibility != View.VISIBLE) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastLogUiAt < 250L) return
        lastLogUiAt = now
        runOnUiThread { if (!isFinishing && !isDestroyed) updateLogDisplay(text) }
    }

    override fun onDestroy() {
        handler.removeCallbacks(updateProgressTask)
        handler.removeCallbacks(lyricsTicker)
        AppLogger.onLogUpdate = null
        super.onDestroy()
    }

}
