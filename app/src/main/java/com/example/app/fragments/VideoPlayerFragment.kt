@file:Suppress("SpellCheckingInspection")

package com.example.app.fragments

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.fragment.app.Fragment
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.app.R

/**
 * Простой видеоплеер на Media3 / ExoPlayer.
 * Только базовые функции: выбор файла, URL, play/pause (контроллер), стоп.
 * Без фонового воспроизведения и MediaSession.
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

    private var currentUri: Uri? = null

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
                it.pause()
                it.seekTo(0)
            }
        }

        btnRelease.setOnClickListener {
            releasePlayer()
            tvSelectedVideo.text = "Файл не выбран"
            currentUri = null
        }

        return root
    }

    override fun onStart() {
        super.onStart()
        currentUri?.let { playUri(it) }
    }

    override fun onStop() {
        super.onStop()
        // Без фонового воспроизведения — полностью останавливаем
        releasePlayer()
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
        exo.playWhenReady = true
    }

    private fun releasePlayer() {
        playerView.player = null
        player?.release()
        player = null
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
}