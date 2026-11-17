@file:Suppress("UnstableApiUsage", "UnsafeOptInUsageError")

package app.leiflix

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.*
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import java.io.IOException
import java.util.Locale

class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private val stopBtnId = View.generateViewId()
    private val audioBtnId = View.generateViewId()
    private val dlnaBtnId = View.generateViewId()

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"

    override fun onCreate(savedInstanceState: Bundle?) {
        // Ocultar barras ANTES de crear la vista para evitar flash/fade
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        window.setBackgroundDrawableResource(android.R.color.black)
        
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView)
            .hide(WindowInsetsCompat.Type.systemBars())

        // Ajustar top_info_bar para safe areas (TV, tablet, notch)
        val topInfoBar = findViewById<ViewGroup>(R.id.top_info_bar)
        ViewCompat.setOnApplyWindowInsetsListener(topInfoBar) { view, insets ->
            val topInset = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()).top
            if (topInset > 0) {
                view.setPadding(view.paddingStart, topInset + 20, view.paddingEnd, view.paddingBottom)
            }
            insets
        }

        playerView = findViewById(R.id.player_view)
        initializePlayer()

        onBackPressedDispatcher.addCallback(this) {
            releasePlayer()
            finish()
        }
    }

    private fun recogerHeaders(): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        intent.extras?.keySet()?.forEach { key ->
            if (key.startsWith("header_")) {
                val headerName = key.removePrefix("header_")
                val headerValue = intent.getStringExtra(key)
                if (!headerValue.isNullOrEmpty()) {
                    headers[headerName] = headerValue
                }
            }
        }
        return headers
    }

    private fun esStreamHls(url: String) =
        url.contains(".m3u8") || url.contains(".m3u") ||
                url.contains("playlist") || url.contains("/live/") ||
                url.contains("/movie/") || url.contains("/series/")

    private fun esStreamDash(url: String) =
        url.contains(".mpd") || url.contains("manifest") || url.contains("%2e%6d%70%64")

    private fun hexToBase64Url(hex: String): String {
        val bytes = ByteArray(hex.length / 2) {
            hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE).trimEnd('=')
    }

    private fun buildClearKeyDrmManager(drmPairs: List<Pair<String, String>>): DefaultDrmSessionManager {
        val keyEntries = drmPairs.joinToString(",") { (kid, key) ->
            """{"kty":"oct","kid":"${hexToBase64Url(kid)}","k":"${hexToBase64Url(key)}"}"""
        }
        val psshJson = """{"keys":[$keyEntries],"type":"temporary"}"""
        val drmCallback = LocalMediaDrmCallback(psshJson.toByteArray(Charsets.UTF_8))
        return DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
            .setMultiSession(true)
            .setPlayClearSamplesWithoutKeys(true)
            .build(drmCallback)
    }

    private fun initializePlayer() {
        val url = intent.getStringExtra("videoUrl") ?: return

        val count = intent.getIntExtra("drm_count", 0)
        val drmPairs = (0 until count).mapNotNull { i ->
            val kid = intent.getStringExtra("kid_$i") ?: return@mapNotNull null
            val key = intent.getStringExtra("key_$i") ?: return@mapNotNull null
            if (kid.isNotEmpty() && key.isNotEmpty()) kid to key else null
        }
        val tieneDrm = esStreamDash(url) && drmPairs.isNotEmpty()

        val headersCapturados = recogerHeaders()
        val channelName = intent.getStringExtra("channelName")?.trim().orEmpty()
        val epgTitle = intent.getStringExtra("epgTitle")?.trim().orEmpty()
        val trackSelector = DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true))
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 60_000, 1_000, 1_500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val dataSourceFactory: HttpDataSource.Factory =
            DefaultHttpDataSource.Factory()
                .setUserAgent(headersCapturados["User-Agent"] ?: userAgent)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(15_000)
                .setDefaultRequestProperties(headersCapturados)

        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setRenderersFactory(
                androidx.media3.exoplayer.DefaultRenderersFactory(this)
                    .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                    .setEnableDecoderFallback(true)
            )
            .build()

        playerView.player = player
        playerView.keepScreenOn = true
        playerView.setShutterBackgroundColor(Color.BLACK) // shutter negro hasta que llegue el primer frame

        // Configurar título/EPG en el layout (no en PlayerView)
        val tvChannel = findViewById<TextView>(R.id.tv_channel_name)
        val tvEpg = findViewById<TextView>(R.id.tv_epg_title)
        if (channelName.isNotEmpty()) {
            tvChannel.text = channelName
            tvChannel.isSelected = true
            tvChannel.visibility = View.VISIBLE
        } else {
            tvChannel.visibility = View.GONE
        }
        if (epgTitle.isNotEmpty()) {
            tvEpg.text = epgTitle
            tvEpg.isSelected = true
            tvEpg.visibility = View.VISIBLE
        } else {
            tvEpg.visibility = View.GONE
        }

        // Handler para ocultar todo (título/EPG + controller) tras 3s sin tocar
        val hideHandler = Handler(Looper.getMainLooper())

        fun hideAll() {
            tvChannel.visibility = View.GONE
            tvEpg.visibility = View.GONE
            playerView.hideController()
        }

        fun showAll() {
            tvChannel.visibility = View.VISIBLE
            tvEpg.visibility = View.VISIBLE
            playerView.showController()
            hideHandler.removeCallbacksAndMessages(null)
            hideHandler.postDelayed({ hideAll() }, 5000)
        }

        // Al entrar: SOLO título/EPG visible, controller oculto
        playerView.controllerAutoShow = false
        playerView.hideController()
        hideHandler.postDelayed({ hideAll() }, 5000)

        // Touch en PlayerView: muestra TODO (título/EPG + controller)
        playerView.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                showAll()
            }
            false
        }

        // ──────────────────────────────────────────────
        // INYECCIÓN DINÁMICA DE BOTONES (DLNA, Stop, Audio, Calidad)
        // ──────────────────────────────────────────────
        val btnSettings = playerView.findViewById<ImageButton>(androidx.media3.ui.R.id.exo_settings)
        btnSettings?.setImageResource(R.drawable.ic_hd_outline_24dp)
        btnSettings?.imageTintList = ColorStateList.valueOf(Color.WHITE)
        val controlsContainer = btnSettings?.parent as? ViewGroup

        if (controlsContainer != null) {
            val settingsIdx = controlsContainer.indexOfChild(btnSettings)

            // Insertar en orden INVERSO para que visualmente queden: DLNA, Stop, Audio, Calidad
            // 3. Audio (se inserta primero → queda más a la derecha, junto a Calidad)
            if (controlsContainer.findViewById<View>(audioBtnId) == null) {
                val btnAudio = ImageButton(this).apply {
                    id = audioBtnId
                    setImageResource(android.R.drawable.ic_btn_speak_now)
                    background = btnSettings.background.constantState?.newDrawable()?.mutate()
                    imageTintList = ColorStateList.valueOf(Color.WHITE)
                    contentDescription = "Seleccionar Audio"
                    scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                    layoutParams = LinearLayout.LayoutParams(
                        btnSettings.layoutParams.width,
                        btnSettings.layoutParams.height
                    )
                    isFocusable = true
                }
                controlsContainer.addView(btnAudio, settingsIdx)

                btnAudio.setOnClickListener { view ->
                    view.isPressed = true
                    btnSettings.isPressed = false

                    player?.let { exoPlayer ->
                        TrackSelectionDialogBuilder(this, "Seleccionar audio:", exoPlayer, C.TRACK_TYPE_AUDIO)
                            .apply {
                                setShowDisableOption(false)
                                setAllowAdaptiveSelections(false)
                            }.build().apply {
                                setOnDismissListener { view.isPressed = false }
                            }.show()
                    }
                }
            }

            // 2. Stop
            if (controlsContainer.findViewById<View>(stopBtnId) == null) {
                val btnStop = ImageButton(this).apply {
                    id = stopBtnId
                    setImageResource(R.drawable.ic_stop_white_24dp)
                    background = btnSettings.background.constantState?.newDrawable()?.mutate()
                    imageTintList = ColorStateList.valueOf(Color.WHITE)
                    contentDescription = "Detener y salir"
                    scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                    layoutParams = LinearLayout.LayoutParams(
                        btnSettings.layoutParams.width,
                        btnSettings.layoutParams.height
                    )
                    isFocusable = true
                }
                controlsContainer.addView(btnStop, settingsIdx)

                btnStop.setOnClickListener {
                    releasePlayer()
                    finish()
                }
            }

            // 1. DLNA (se inserta último → queda más a la izquierda)
            if (controlsContainer.findViewById<View>(dlnaBtnId) == null) {
                val btnDlna = ImageButton(this).apply {
                    id = dlnaBtnId
                    setImageResource(R.drawable.ic_cast_white_24dp)
                    background = btnSettings.background.constantState?.newDrawable()?.mutate()
                    imageTintList = ColorStateList.valueOf(Color.WHITE)
                    contentDescription = "Transmitir por DLNA"
                    scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                    layoutParams = LinearLayout.LayoutParams(
                        btnSettings.layoutParams.width,
                        btnSettings.layoutParams.height
                    )
                    isFocusable = true
                }
                controlsContainer.addView(btnDlna, settingsIdx)

                val castTitle = when {
                    channelName.isNotEmpty() && epgTitle.isNotEmpty() -> "$channelName - $epgTitle"
                    channelName.isNotEmpty() -> channelName
                    else -> "LEIFLIX Live"
                }

                btnDlna.setOnClickListener {
                    DlnaManager.showCastDialog(
                        context = this@PlayerActivity,
                        streamUrl = url,
                        headers = headersCapturados,
                        hasDrm = tieneDrm,
                        title = castTitle
                    ) {
                        player?.pause()
                    }
                }
            }
        }

        btnSettings.setOnClickListener { view ->
            val audioBtn = controlsContainer?.findViewById<ImageButton>(audioBtnId)
            audioBtn?.isPressed = false
            view.isPressed = true

            player?.let { exoPlayer ->
                val dialogBuilder = TrackSelectionDialogBuilder(this, "Seleccionar calidad:", exoPlayer, C.TRACK_TYPE_VIDEO)

                dialogBuilder.setTrackFormatComparator { f1, f2 ->
                    f2.bitrate.compareTo(f1.bitrate)
                }

                dialogBuilder.setTrackNameProvider { format ->
                    val height = format.height
                    val bitrate = format.bitrate
                    val resText = if (height != Format.NO_VALUE) "${height}p" else "Calidad auto"
                    val mbpsText = if (bitrate != Format.NO_VALUE) {
                        val mbps = bitrate.toFloat() / 1_000_000
                        String.format(Locale.US, " (%.1f Mbps)", mbps)
                    } else ""
                    "$resText$mbpsText"
                }

                dialogBuilder.apply {
                    setShowDisableOption(false)
                    setAllowAdaptiveSelections(true)
                }.build().apply {
                    setOnDismissListener { view.isPressed = false }
                }.show()
            }
        }

        // ── Configuración de MediaSource ───────────────────────────────────
        val mediaSource = when {
            tieneDrm -> {
                val drmManager = ClearKeyCbcsDecryptor.wrapDrmSessionManager(buildClearKeyDrmManager(drmPairs))
                val chunkSourceFactory = ClearKeyCbcsDecryptor.createDashChunkSourceFactory(dataSourceFactory, drmPairs)
                DashMediaSource.Factory(chunkSourceFactory, dataSourceFactory)
                    .setManifestParser(ClearKeyCbcsDecryptor.CbcsDashManifestParser())
                    .setDrmSessionManagerProvider { drmManager }
                    .createMediaSource(MediaItem.fromUri(url))
            }
            esStreamDash(url) -> {
                DashMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(url))
            }
            esStreamHls(url) -> {
                HlsMediaSource.Factory(dataSourceFactory)
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(MediaItem.fromUri(url))
            }
            else -> {
                ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(url))
            }
        }

        player?.apply {
            setMediaSource(mediaSource)
            prepare()
            playWhenReady = true

            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    android.util.Log.e("PlayerActivity", "ExoPlayer onPlayerError: ${error.errorCodeName} (${error.errorCode})", error)
                    if (tieneDrm) return
                    val fallback = when {
                        esStreamDash(url) -> HlsMediaSource.Factory(dataSourceFactory)
                            .setAllowChunklessPreparation(true)
                            .createMediaSource(MediaItem.fromUri(url))
                        esStreamHls(url) -> ProgressiveMediaSource.Factory(dataSourceFactory)
                            .createMediaSource(MediaItem.fromUri(url))
                        else -> HlsMediaSource.Factory(dataSourceFactory)
                            .setAllowChunklessPreparation(true)
                            .createMediaSource(MediaItem.fromUri(url))
                    }
                    val delay = if (error.cause is IOException) 2000L else 0L
                    playerView.postDelayed({
                        if (player != null) {
                            setMediaSource(fallback)
                            prepare()
                            play()
                        }
                    }, delay)
                }

                override fun onPlaybackStateChanged(state: Int) {
                    android.util.Log.d("PlayerActivity", "onPlaybackStateChanged: state=$state")
                    if (state == Player.STATE_READY) {
                        playerView.requestFocus()
                    }
                }

                override fun onRenderedFirstFrame() {
                    android.util.Log.d("PlayerActivity", "onRenderedFirstFrame! First frame ready!")
                    // Primer frame listo → quitamos el shutter negro
                    playerView.setShutterBackgroundColor(Color.TRANSPARENT)
                }

                override fun onTracksChanged(tracks: Tracks) {
                    android.util.Log.d("PlayerActivity", "onTracksChanged: groups=${tracks.groups.size}")
                    for (g in tracks.groups) {
                        for (i in 0 until g.length) {
                            val f = g.getTrackFormat(i)
                            android.util.Log.d("PlayerActivity", "  track: ${f.sampleMimeType}, drmInit=${f.drmInitData?.schemeType}, selected=${g.isTrackSelected(i)}, supported=${g.isTrackSupported(i)}")
                        }
                    }
                }
            })
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            WindowInsetsControllerCompat(window, window.decorView)
                .hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onResume() {
        super.onResume()
        WindowInsetsControllerCompat(window, window.decorView)
            .hide(WindowInsetsCompat.Type.systemBars())
        player?.play()
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
    }

    private fun releasePlayer() {
        player?.release()
        player = null
    }
}