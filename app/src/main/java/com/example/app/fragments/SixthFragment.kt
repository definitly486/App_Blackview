@file:Suppress("SpellCheckingInspection")

package com.example.app.fragments

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.os.Build
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.app.R
import com.example.app.git.GitCloneManager
import com.example.app.git.GitCloneState
import kotlinx.coroutines.launch
import java.io.File

/**
 * Вкладка Git Clone. Сама клонирование не выполняет — этим занимается
 * [com.example.app.git.GitCloneService]. Фрагмент лишь отображает [GitCloneManager.state],
 * поэтому при переходе на другую вкладку клонирование продолжается, а при возврате
 * прогресс показывается заново.
 */
class SixthFragment : Fragment() {

    private var pendingStart: (() -> Unit)? = null
    private var pendingStorageStart: (() -> Unit)? = null

    // Разрешение на уведомления нужно только для показа прогресса в шторке;
    // клонирование запускаем в любом случае.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            pendingStart?.invoke()
            pendingStart = null
        }

    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingStorageStart
            pendingStorageStart = null
            if (granted) action?.invoke()
            else Toast.makeText(requireContext(), "Нужно разрешение на запись в Загрузки", Toast.LENGTH_LONG).show()
        }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            Environment.isExternalStorageManager()
        ) {
            pendingStorageStart?.let { action ->
                pendingStorageStart = null
                action()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_sixth, container, false)
        val repoUrlField = view.findViewById<EditText>(R.id.repo_url_field)
        val cloneButton = view.findViewById<Button>(R.id.button_clone)
        val progressBar = view.findViewById<ProgressBar>(R.id.clone_progress)
        val progressText = view.findViewById<TextView>(R.id.clone_progress_text)
        val cloneButtonText = cloneButton.text

        cloneButton.setOnClickListener {
            if (GitCloneManager.isRunning()) {
                GitCloneManager.cancel()
                cloneButton.isEnabled = false
                progressText.text = "Отмена…"
            } else {
                startClone(repoUrlField.text.toString())
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                GitCloneManager.state.collect { state ->
                    render(state, repoUrlField, cloneButton, cloneButtonText, progressBar, progressText)
                }
            }
        }

        return view
    }

    private fun startClone(repositoryUrl: String) {
        val url = repositoryUrl.trim()
        if (url.isBlank()) {
            Toast.makeText(requireContext(), "Введите URL репозитория", Toast.LENGTH_SHORT).show()
            return
        }

        val repoName = extractRepoNameFromUrl(url)
        if (repoName.isBlank()) {
            Toast.makeText(requireContext(), "Не удалось определить имя репозитория", Toast.LENGTH_SHORT).show()
            return
        }

        val destination = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            repoName
        )
        val appContext = requireContext().applicationContext
        val launch = { GitCloneManager.start(appContext, url, repoName, destination) }

        // Для записи в общую папку Downloads нужны разные разрешения на Android 10 и 11+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                pendingStorageStart = { startCloneWithNotificationPermission(launch) }
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${requireContext().packageName}")
                        )
                    )
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
                Toast.makeText(requireContext(), "Разрешите доступ ко всем файлам и вернитесь в приложение", Toast.LENGTH_LONG).show()
                return
            }
        } else if (ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingStorageStart = { startCloneWithNotificationPermission(launch) }
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }

        startCloneWithNotificationPermission(launch)
    }

    private fun startCloneWithNotificationPermission(launch: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingStart = launch
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launch()
        }
    }

    private fun render(
        state: GitCloneState,
        urlField: EditText,
        button: Button,
        idleButtonText: CharSequence,
        progressBar: ProgressBar,
        progressText: TextView
    ) {
        progressText.setTextColor(Color.WHITE)
        when (state) {
            is GitCloneState.Idle -> {
                button.isEnabled = true
                button.text = idleButtonText
                progressBar.visibility = View.GONE
                progressText.visibility = View.GONE
            }

            is GitCloneState.Running -> {
                if (urlField.text.isNullOrBlank()) urlField.setText(state.url)
                button.isEnabled = !GitCloneManager.cancelRequested
                button.text = "Отменить"
                progressBar.visibility = View.VISIBLE
                progressText.visibility = View.VISIBLE
                if (state.total > 0) {
                    progressBar.isIndeterminate = false
                    progressBar.max = state.total
                    progressBar.progress = state.completed.coerceIn(0, state.total)
                    val percent = (state.completed * 100L / state.total).toInt()
                    progressText.text = "${state.task} — $percent%"
                } else {
                    progressBar.isIndeterminate = true
                    progressText.text = state.task
                }
            }

            is GitCloneState.Success -> {
                button.isEnabled = true
                button.text = idleButtonText
                progressBar.visibility = View.VISIBLE
                progressText.visibility = View.VISIBLE
                progressBar.isIndeterminate = false
                progressBar.progress = progressBar.max
                progressText.text = "КЛОНИРОВАНИЕ ЗАВЕРШЕНО: 100%\nПапка: ${state.path}"
            }

            is GitCloneState.Failed -> {
                button.isEnabled = true
                button.text = idleButtonText
                progressBar.visibility = View.VISIBLE
                progressText.visibility = View.VISIBLE
                progressBar.isIndeterminate = false
                progressBar.progress = 0
                progressText.text = "ОШИБКА КЛОНИРОВАНИЯ\n${state.message}"
            }
        }
    }

    private fun extractRepoNameFromUrl(url: String): String =
        url.substringBeforeLast(".git")
            .substringAfterLast("/")
            .trim()
}
