@file:Suppress("SameParameterValue", "SpellCheckingInspection")

package com.example.app.fragments

import DownloadHelper
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
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class ThirdFragment : Fragment() {

    private lateinit var downloadHelper: DownloadHelper
    private lateinit var downloadHelper2: DownloadHelper2

    private lateinit var editTextPasswordgnucash: EditText


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_third, container, false)
        downloadHelper = DownloadHelper(requireContext())
        downloadHelper2 = DownloadHelper2(requireContext())
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
        val installButton = view.findViewById<Button>(R.id.decryptgnucashgpg)

        installButton.setOnClickListener { _ ->

            //проверка существоания gnupg

            //   isGnupgBinaryExists ()

            // Получаем введённый пароль из EditText поля
            val enteredPassword = editTextPasswordgnucash.text.toString().trim() // trim удалит лишние пробелы

            // Проверяем наличие пароля
            if (enteredPassword.isBlank()) {
                showToast("Пароль не введен. Пожалуйста, введите пароль.")
                return@setOnClickListener
            }

            // Создаем экземпляр помощника для работы с PGP-шифрованием
            val helper = GPGHelper()

            try {
                // Расшифровка файла с использованием переданного пароля
                //   val isDecryptedSuccessfully = helper.decryptFile(
                //        "/storage/emulated/0/Download/definitly.gnucash.gpg",
                //       "/storage/emulated/0/Download/definitly.gnucash",
                //        enteredPassword
                //   )

                val isDecryptedSuccessfully = helper.decryptGpgSymmetric(
                    "/storage/emulated/0/Download/definitly.gnucash.gpg",
                    "/storage/emulated/0/Download/definitly.gnucash",
                    enteredPassword
                )


                if (isDecryptedSuccessfully) {
                    showToast("Файл успешно расшифрован.")
                } else {
                    showToast("Ошибка при расшифровке файла.")
                }
            } catch (exception: Exception) {
                // Обрабатываем возможные исключения, возникающие при шифровании
                showToast("Ошибка при обработке файла: ${exception.localizedMessage}")
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
                val gitCloneJob = async(Dispatchers.IO) { handleGitCloneOperation() }
                val success = gitCloneJob.await() // Ждём завершения клонирования и получаем результат

                if (!success) {
                    Toast.makeText(context, "Ошибка клонирования.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }










    private suspend fun handleGitCloneOperation(): Boolean {
        val gitClone = GitClone()
        return withContext(Dispatchers.IO) {
            val result = gitClone.cloneRepository()
            if (result.isSuccess) {
                showToast("Репозиторий успешно клонирован.")
                return@withContext true
            } else {
                val errorMessage = result.exceptionOrNull()?.message ?: "Неизвестная ошибка"
                showToast("Ошибка клонирования: $errorMessage")
                return@withContext false
            }
        }
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