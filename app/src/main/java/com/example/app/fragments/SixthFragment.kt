@file:Suppress("SpellCheckingInspection")

package com.example.app.fragments

import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.LinearLayout
import android.graphics.Color
import android.view.Gravity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.app.R
import com.example.app.git.GitRepositoryCloner
import kotlinx.coroutines.launch
import java.io.File

class SixthFragment : Fragment() {

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

        cloneButton.setOnClickListener {
            cloneRepository(repoUrlField.text.toString(), cloneButton, progressBar, progressText)
        }

        return view
    }

    private fun cloneRepository(
        repositoryUrl: String,
        cloneButton: Button,
        progressBar: ProgressBar,
        progressText: TextView
    ) {
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

        cloneButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressText.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        progressText.text = "Подключение к репозиторию…"

        viewLifecycleOwner.lifecycleScope.launch {
            val result = GitRepositoryCloner(requireContext()).clone(url, destination) { task, completed, total ->
                activity?.runOnUiThread {
                    if (total > 0) {
                        progressBar.isIndeterminate = false
                        progressBar.max = total
                        progressBar.progress = completed.coerceIn(0, total)
                        val percent = (completed * 100L / total).toInt()
                        progressText.text = "$task — $percent%"
                    } else {
                        progressBar.isIndeterminate = true
                        progressText.text = task
                    }
                }
            }
            cloneButton.isEnabled = true
            result.fold(
                onSuccess = {
                    progressBar.isIndeterminate = false
                    progressBar.progress = progressBar.max
                    progressText.text = "Клонирование завершено: 100%"
                    Toast.makeText(
                        requireContext(),
                        "Репозиторий '$repoName' успешно клонирован.",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onFailure = {
                    progressBar.visibility = View.GONE
                    progressText.visibility = View.GONE
                    Toast.makeText(
                        requireContext(),
                        "Ошибка клонирования: ${it.message ?: "неизвестная ошибка"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    private fun extractRepoNameFromUrl(url: String): String =
        url.substringBeforeLast(".git")
            .substringAfterLast("/")
            .trim()
}
