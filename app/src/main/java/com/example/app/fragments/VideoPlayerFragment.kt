@file:Suppress("SpellCheckingInspection")

package com.example.app.fragments

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.app.R

/**
 * Простой видеоплеер на Media3 / ExoPlayer.
 * Выбор файла, URL, play/pause, стоп, fullscreen, запоминание позиции.
 * Видео не останавливается при повороте экрана (configChanges в манифесте).
 */
class VideoPlayerFragment : Fragment() {

    private var player: ExoPlayer? = null

    private lateinit var playerView: PlayerView
    private lateinit var tvSelectedVideo: TextView
    private lateinit var btnSelectVideo: Button
    private lateinit var etVideoUrl: EditText
    private lateinit var btnPlayUrl: Button
    private lateinit var btnStop: Button
    private lateinit var btnRelease: Button
    private lateinit var headerLayout: LinearLayout
    private lateinit var controlsLayout: LinearLayout

    private var currentUri: Uri? = null
    private var isFullscreen = false
    private var savedOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    private val prefs by lazy {
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val selectVideoLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Не все провайдеры поддерживают persistable — игнорируем
            }
            currentUri = uri
            tvSelectedVideo.text = "Файл: ${queryDisplayName(uri)}"
            playUri(uri)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_video_player, container, false)

        playerView = root.findViewById(R.id.playerView)
        tvSelectedVideo = root.findViewById(R.id.tvSelectedVideo)
        btnSelectVideo = root.findViewById(R.id.btnSelectVideo)
        etVideoUrl = root.findViewById(R.id.etVideoUrl)
        btnPlayUrl = root.findViewById(R.id.btnPlayUrl)
        btnStop = root.findViewById(R.id.btnStop)
        btnRelease = root.findViewById(R.id.btnRelease)
        headerLayout = root.findViewById(R.id.headerLayout)
        controlsLayout = root.findViewById(R.id.controlsLayout)

        btnSelectVideo.setOnClickListener {
            selectVideoLauncher.launch(arrayOf("video/*"))
        }

        btnPlayUrl.setOnClickListener {
            val url = etVideoUrl.text?.toString()?.trim().orEmpty()
            if (url.isEmpty()) {
                Toast.makeText(requireContext(), "Вставь URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val uri = Uri.parse(url)
            currentUri = uri
            tvSelectedVideo.text = "URL: $url"
            playUri(uri)
        }

        btnStop.setOnClickListener {
            player?.let {
                savePosition()
                it.pause()
                it.seekTo(0)
                // После стопа на начало — сбрасываем сохранённую позицию
                clearSavedPosition()
            }
        }

        btnRelease.setOnClickListener {
            savePosition()
            releasePlayer()
            tvSelectedVideo.text = "Файл не выбран"
            currentUri = null
        }

        setupFullscreen()

        return root
    }

    @OptIn(UnstableApi::class)
    private fun setupFullscreen() {
        playerView.setFullscreenButtonClickListener { goingFullscreen ->
            toggleFullscreen(goingFullscreen)
        }
    }

    @OptIn(UnstableApi::class)
    private fun toggleFullscreen(enable: Boolean) {
        val activity = activity ?: return
        val window = activity.window
        val decorView = window.decorView
        val controller = WindowInsetsControllerCompat(window, decorView)

        isFullscreen = enable

        // Прячем/показываем боковую панель и заголовок Activity
        val titleView = activity.findViewById<View>(R.id.title)
        val scrollButtons = activity.findViewById<View>(R.id.scrollViewButtons)
        val fragmentContainer = activity.findViewById<View>(R.id.fragmentContainer)

        if (enable) {
            savedOrientation = activity.requestedOrientation
            // Разрешаем свободный поворот в полноэкранном режиме
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR

            // Скрываем системные панели
            WindowCompat.setDecorFitsSystemWindows(window, false)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            // Прячем UI фрагмента
            headerLayout.visibility = View.GONE
            controlsLayout.visibility = View.GONE

            // Прячем боковую панель и заголовок — видео на весь экран
            titleView?.visibility = View.GONE
            scrollButtons?.visibility = View.GONE

            // Растягиваем контейнер фрагмента на весь экран
            (fragmentContainer?.layoutParams as? ConstraintLayout.LayoutParams)?.let { lp ->
                lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                lp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                lp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                lp.horizontalWeight = 0f
                fragmentContainer.layoutParams = lp
            }

            // Видео заполняет весь экран
            playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            // Убираем padding у корневого layout фрагмента
            (view as? ViewGroup)?.setPadding(0, 0, 0, 0)
        } else {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            controller.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            headerLayout.visibility = View.VISIBLE
            controlsLayout.visibility = View.VISIBLE

            titleView?.visibility = View.VISIBLE
            scrollButtons?.visibility = View.VISIBLE

            // Возвращаем контейнер фрагмента в исходное положение (справа от кнопок)
            (fragmentContainer?.layoutParams as? ConstraintLayout.LayoutParams)?.let { lp ->
                lp.startToEnd = R.id.scrollViewButtons
                lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                lp.topToBottom = R.id.title
                lp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                lp.horizontalWeight = 3f
                fragmentContainer.layoutParams = lp
            }

            playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            // Восстанавливаем padding
            val pad = (12 * resources.displayMetrics.density).toInt()
            (view as? ViewGroup)?.setPadding(pad, pad, pad, pad)

            if (savedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                activity.requestedOrientation = savedOrientation
            } else {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // При повороте ничего не делаем с плеером — он продолжает играть
        // (благодаря android:configChanges в манифесте Activity не пересоздаётся)
        if (isFullscreen) {
            // Убеждаемся, что системные панели остаются скрытыми
            val activity = activity ?: return
            val window = activity.window
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onStart() {
        super.onStart()
        // Восстанавливаем плеер только если его ещё нет (не при повороте)
        if (player == null) {
            currentUri?.let { playUri(it) }
        }
    }

    override fun onStop() {
        super.onStop()
        // Не останавливаем при простом уходе в фон во время полноэкранного просмотра —
        // но при реальном закрытии вкладки/фрагмента освобождаем ресурсы
        savePosition()
        if (!isFullscreen) {
            releasePlayer()
        }
    }

    override fun onDestroyView() {
        // На всякий случай выходим из fullscreen и освобождаем плеер
        if (isFullscreen) {
            toggleFullscreen(false)
        }
        releasePlayer()
        super.onDestroyView()
    }

    @OptIn(UnstableApi::class)
    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }

        val exo = ExoPlayer.Builder(requireContext()).build().also { p ->
            p.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Toast.makeText(
                        requireContext(),
                        "Ошибка: ${error.message ?: error.errorCodeName}",
                        Toast.LENGTH_LONG
                    ).show()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        clearSavedPosition()
                    }
                }
            })
            playerView.player = p
        }
        player = exo
        return exo
    }

    private fun playUri(uri: Uri) {
        val exo = ensurePlayer()
        val mediaItem = MediaItem.fromUri(uri)
        exo.setMediaItem(mediaItem)
        exo.prepare()

        // Восстанавливаем позицию, если есть
        val savedPos = getSavedPosition(uri)
        if (savedPos > 0L) {
            exo.seekTo(savedPos)
        }

        exo.playWhenReady = true
    }

    private fun releasePlayer() {
        playerView.player = null
        player?.release()
        player = null
    }

    private fun positionKey(uri: Uri): String = "pos_${uri}"

    private fun savePosition() {
        val uri = currentUri ?: return
        val p = player ?: return
        val pos = p.currentPosition
        // Не сохраняем если почти в конце или в начале
        if (pos <= 1_000L) return
        val duration = p.duration
        if (duration > 0 && pos >= duration - 2_000L) {
            clearSavedPosition()
            return
        }
        prefs.edit().putLong(positionKey(uri), pos).apply()
    }

    private fun getSavedPosition(uri: Uri): Long {
        return prefs.getLong(positionKey(uri), 0L)
    }

    private fun clearSavedPosition() {
        val uri = currentUri ?: return
        prefs.edit().remove(positionKey(uri)).apply()
    }

    private fun queryDisplayName(uri: Uri): String {
        val cr = requireContext().contentResolver
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return c.getString(idx) ?: uri.lastPathSegment ?: uri.toString()
            }
        }
        return uri.lastPathSegment ?: uri.toString()
    }

    companion object {
        private const val PREFS_NAME = "video_player_positions"
    }
}