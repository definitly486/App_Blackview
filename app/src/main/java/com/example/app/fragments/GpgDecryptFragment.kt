@file:Suppress("SpellCheckingInspection")

package com.example.app.fragments

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.app.R
import com.example.app.crypto.GpgDecryptor
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bouncycastle.openpgp.PGPDataValidationException

/**
 * Вкладка "GPG Decryptor": расшифровка файла, зашифрованного GPG с паролем
 * (gpg --symmetric). Файл выбирается на устройстве через системный выбор файлов,
 * результат сохраняется в папку «Загрузки».
 */
class GpgDecryptFragment : Fragment() {

    private lateinit var tvSelectedFile: TextView
    private lateinit var btnSelectFile: Button
    private lateinit var etPassword: EditText
    private lateinit var btnDecrypt: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView

    private var selectedFileUri: Uri? = null
    private var selectedFileName: String? = null
    private var outputUri: Uri? = null

    private val selectFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { onFileSelected(it) }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_gpg_decrypt, container, false)

        tvSelectedFile = root.findViewById(R.id.gpgTvSelectedFile)
        btnSelectFile = root.findViewById(R.id.gpgBtnSelectFile)
        etPassword = root.findViewById(R.id.gpgEtPassword)
        btnDecrypt = root.findViewById(R.id.gpgBtnDecrypt)
        progressBar = root.findViewById(R.id.gpgProgress)
        tvStatus = root.findViewById(R.id.gpgTvStatus)

        btnSelectFile.setOnClickListener { selectFileLauncher.launch(arrayOf("*/*")) }
        btnDecrypt.setOnClickListener { decrypt() }
        etPassword.doAfterTextChanged { updateButton() }

        // Восстановление состояния после пересоздания view
        selectedFileUri?.let {
            tvSelectedFile.text = getString(R.string.gpg_selected, selectedFileName ?: "")
        }
        updateButton()
        return root
    }

    private fun onFileSelected(uri: Uri) {
        selectedFileUri = uri
        selectedFileName = queryName(uri)
        tvSelectedFile.text = getString(R.string.gpg_selected, selectedFileName ?: "файл")
        tvStatus.text = ""
        outputUri = null
        updateButton()
    }

    private fun updateButton() {
        btnDecrypt.isEnabled =
            selectedFileUri != null && etPassword.text.isNotEmpty() && progressBar.visibility != View.VISIBLE
    }

    private fun queryName(uri: Uri): String = try {
        requireContext().contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            } else null
        } ?: (uri.lastPathSegment ?: "file.gpg")
    } catch (_: Exception) {
        uri.lastPathSegment ?: "file.gpg"
    }

    /** definitly.gnucash.gpg -> definitly.gnucash ; прочее -> имя + ".dec" */
    private fun outputName(input: String): String {
        val lower = input.lowercase()
        val stripped = when {
            lower.endsWith(".gpg") || lower.endsWith(".pgp") || lower.endsWith(".asc") ->
                input.dropLast(4)
            else -> ""
        }
        return if (stripped.isNotBlank()) stripped else "$input.dec"
    }

    private fun decrypt() {
        val uri = selectedFileUri ?: return
        // Пароль берём как есть (без trim): пробелы в начале/конце могут быть частью пароля
        val password = etPassword.text.toString().toCharArray()
        val outName = outputName(selectedFileName ?: "file.gpg")
        val ctx = requireContext().applicationContext

        progressBar.visibility = View.VISIBLE
        btnDecrypt.isEnabled = false
        tvStatus.text = getString(R.string.gpg_decrypting)

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val tmp = File(ctx.cacheDir, "gpg_dec_${System.currentTimeMillis()}.tmp")
                try {
                    // 1. Расшифровываем во временный файл: при неверном пароле
                    //    в «Загрузки» не попадёт пустой/битый файл.
                    val input = ctx.contentResolver.openInputStream(uri)
                        ?: throw IOException("Не удалось открыть выбранный файл")
                    input.use { ins ->
                        tmp.outputStream().use { outs ->
                            GpgDecryptor().decryptGpgSymmetric(ins.buffered(), outs, password)
                        }
                    }
                    // 2. Успех — копируем в Загрузки
                    Result.success(saveToDownloads(ctx, tmp, outName))
                } catch (e: Exception) {
                    Result.failure(e)
                } finally {
                    tmp.delete()
                    password.fill('\u0000')
                }
            }

            progressBar.visibility = View.GONE
            updateButton()

            result.onSuccess { saved ->
                outputUri = saved
                tvStatus.text = getString(R.string.gpg_success, outName)
                Toast.makeText(requireContext(), "Расшифровано: $outName", Toast.LENGTH_LONG).show()
                tvStatus.setOnClickListener { openResult() }
            }.onFailure { e ->
                val msg = when (e) {
                    is PGPDataValidationException -> "Неверный пароль"
                    else -> if (e.message?.contains("checksum", true) == true ||
                        e.message?.contains("exception decrypting", true) == true
                    ) "Неверный пароль или повреждённый файл"
                    else e.message ?: e.javaClass.simpleName
                }
                tvStatus.text = "Ошибка: $msg"
                tvStatus.setOnClickListener(null)
            }
        }
    }

    private fun saveToDownloads(ctx: android.content.Context, src: File, name: String): Uri {
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Не удалось создать файл в Загрузках")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            } ?: throw IOException("Не удалось записать файл в Загрузки")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    private fun openResult() {
        val uri = outputUri ?: return
        val type = requireContext().contentResolver.getType(uri) ?: "*/*"
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, type)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        } catch (_: Exception) {
            Toast.makeText(requireContext(), "Нет приложения для открытия", Toast.LENGTH_SHORT).show()
        }
    }
}
