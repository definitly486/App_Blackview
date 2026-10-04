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

        cloneButton.setOnClickListener {
            cloneRepository(repoUrlField.text.toString())
        }

        return view
    }

    private fun cloneRepository(repositoryUrl: String) {
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

        viewLifecycleOwner.lifecycleScope.launch {
            val result = GitRepositoryCloner(requireContext()).clone(url, destination)
            result.fold(
                onSuccess = {
                    Toast.makeText(
                        requireContext(),
                        "Репозиторий '$repoName' успешно клонирован.",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onFailure = {
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
