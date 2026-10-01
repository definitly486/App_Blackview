@file:Suppress("SameParameterValue", "SpellCheckingInspection")

package com.example.app.fragments

import com.example.app.crypto.GpgDecryptor
import com.example.app.git.GitRepositoryCloner

import com.example.app.download.DownloadHelper
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
import com.example.app.shell.AdbShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ThirdFragment : Fragment() {

    private lateinit var downloadHelper: DownloadHelper

    private lateinit var editTextPasswordgnucash: EditText


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_third, container, false)
        downloadHelper = DownloadHelper(requireContext())
        editTextPasswordgnucash = view.findViewById(R.id.editTextPasswordgnucash)
        setupInstallButton(view)
        setupDownloadNoteButton(view)
        setupDownloadToxButton(view)
        setupGitCloneButton(view)
        decryptGnucasgpgpButton(view)
        rebootButton(view)
        powerofButton(view)
        deletedefinitlygnucahButton(view)
        return view
    }

    override fun onDestroyView() {
        downloadHelper.cleanup()
        super.onDestroyView()
    }

    private fun setupInstallButton(view: View) {
        val installButton = view.findViewById<Button>(R.id.downloadgnucashgpg)
        installButton.setOnClickListener {
            val apkUrl1 = "https://github.com/xinitronix/gnucash/raw/refs/heads/main/definitly.gnucash.gpg"
            downloadHelper.downloadToPublic(apkUrl1)

        }
    }

    private fun deletedefinitlygnucahButton(view:View) {

        val deleteDefinitlygnucahButton = view.findViewById<Button>(R.id.deletegnucashgpg)
        deleteDefinitlygnucahButton.setOnClickListener {

            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

// Определяем файлы для удаления
            val firstFile = downloadsDir.resolve("definitly.gnucash")
            val secondFile = downloadsDir.resolve("definitly.gnucash.gpg") // второй файл




// Удаляем первый файл
            if (firstFile.exists()) {
                firstFile.delete()

                showToast("Файл definitly.gnucash  успешно удалён! ")
            } else {
                showToast("Файл  definitly.gnucash не найден.")
            }

// Удаляем второй файл
            if (secondFile.exists()) {
                secondFile.delete()
                showToast("Файл definitly.gnucash.gpg  успешно удалён! ")

            } else {
                showToast("Файл definitly.gnucash.gpg  не найден ! ")
            }
        }
    }


    private fun decryptGnucasgpgpButton(view: View) {
        view.findViewById<Button>(R.id.decryptgnucashgpg).setOnClickListener {
            val password = editTextPasswordgnucash.text.toString()
            if (password.isBlank()) {
                showToast("Пароль не введен. Пожалуйста, введите пароль.")
                return@setOnClickListener
            }

            val downloads = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            val input = File(downloads, "definitly.gnucash.gpg")
            val output = File(downloads, "definitly.gnucash")

            lifecycleScope.launch {
                val success = withContext(Dispatchers.IO) {
                    val chars = password.toCharArray()
                    try {
                        GpgDecryptor().decrypt(input, output, chars)
                        true
                    } catch (_: Exception) {
                        output.delete()
                        false
                    } finally {
                        chars.fill('\u0000')
                    }
                }

                showToast(
                    if (success) {
                        "Файл успешно расшифрован."
                    } else {
                        "Ошибка при расшифровке файла."
                    }
                )
            }
        }
    }

    private fun setupDownloadNoteButton(view: View) {
        val downloadnote = view.findViewById<Button>(R.id.downloadnote)
        downloadnote.setOnClickListener {
            val apkUrl1 = "https://raw.githubusercontent.com/definitly486/definitly486/refs/heads/main/note"
            downloadHelper.downloadToPublic(apkUrl1)
        }
    }

    private fun setupDownloadToxButton(view: View) {
        val downloadtox = view.findViewById<Button>(R.id.downloadtoxenc)
        downloadtox.setOnClickListener {
            val apkUrl1 = "https://github.com/definitly486/definitly486/releases/download/tox/profile.tox.enc"
            downloadHelper.downloadToPublic(apkUrl1)
        }
    }


    private fun setupGitCloneButton(view: View) {
        val gitCloneButton = view.findViewById<Button>(R.id.gitclonedcim)
        gitCloneButton.setOnClickListener {
            lifecycleScope.launch {
                handleGitCloneOperation()
            }
        }
    }










    private suspend fun handleGitCloneOperation(): Boolean {
        val result = GitRepositoryCloner(requireContext()).clone(
            repositoryUrl = "https://github.com/definitly486/DCIM"
        )

        return result.fold(
            onSuccess = {
                showToast("Репозиторий успешно клонирован.")
                true
            },
            onFailure = {
                showToast("Ошибка клонирования: ${it.message ?: "Неизвестная ошибка"}")
                false
            }
        )
    }

    private fun rebootButton(view: View) {
        view.findViewById<Button>(R.id.reboot).setOnClickListener {
            runPowerCommand("reboot", "Перезагрузка…")
        }
    }

    private fun powerofButton(view: View) {
        view.findViewById<Button>(R.id.poweroff).setOnClickListener {
            runPowerCommand("reboot -p", "Выключение…")
        }
    }

    // Команды shell через Shizuku (без root)
    private fun runPowerCommand(command: String, message: String) {
        if (!AdbShell.isRunning() || !AdbShell.hasPermission()) {
            showToast("Shizuku не запущен или нет разрешения (вкладка «Настройка»)")
            return
        }
        showToast(message)
        AdbShell.execAsync(requireContext(), command)
    }

    private fun showToast(message: String) {
        activity?.runOnUiThread {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }



}
