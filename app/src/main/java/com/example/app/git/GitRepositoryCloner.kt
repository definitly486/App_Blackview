package com.example.app.git

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ProgressMonitor
import java.io.File
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * Git clone service. Paths are derived from the app context instead of
 * hard-coded package/storage paths.
 *
 * First tries a normal full clone (preserving the complete history).
 * If GitHub/JGit repeatedly fails while streaming the pack, it falls back
 * to a depth-1 clone to reduce the amount of data transferred.
 */
class GitRepositoryCloner(context: Context) {

    companion object {
        private const val TAG = "GitClone"
        private const val FULL_CLONE_ATTEMPTS = 3
        private const val NETWORK_TIMEOUT_SECONDS = 120
    }

    private val appContext = context.applicationContext

    suspend fun clone(
        repositoryUrl: String,
        destination: File = defaultDestination(),
        onProgress: (task: String, completed: Int, total: Int) -> Unit = { _, _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        var destinationPreparedByThisCall = false

        try {
            require(repositoryUrl.isNotBlank()) { "Адрес репозитория не указан" }
            Log.i(TAG, "Clone started: url=$repositoryUrl destination=${destination.absolutePath}")

            destination.parentFile?.let { parent ->
                require(parent.exists() || parent.mkdirs()) {
                    "Не удалось создать родительскую папку ${parent.absolutePath}"
                }
            }

            val existedBefore = destination.exists()
            val initiallyEmpty = !existedBefore ||
                (destination.isDirectory && destination.listFiles()?.isEmpty() == true)
            require(initiallyEmpty) {
                "Папка назначения уже существует и не пуста: ${destination.absolutePath}"
            }
            require(destination.mkdirs() || destination.isDirectory) {
                "Не удалось создать папку ${destination.absolutePath}"
            }
            destinationPreparedByThisCall = true

            val monitor = createProgressMonitor(onProgress)
            var lastError: Exception? = null
            var fullCloneSucceeded = false

            for (attempt in 1..FULL_CLONE_ATTEMPTS) {
                if (attempt > 1) {
                    val pauseMs = 1_000L * (1L shl (attempt - 2))
                    Log.w(TAG, "Waiting ${pauseMs}ms before full-clone retry $attempt/$FULL_CLONE_ATTEMPTS")
                    delay(pauseMs)
                    resetDestination(destination)
                }

                try {
                    Log.i(TAG, "Starting full clone attempt $attempt/$FULL_CLONE_ATTEMPTS")
                    val command = Git.cloneRepository()
                        .setURI(repositoryUrl)
                        .setDirectory(destination)
                        .setTimeout(NETWORK_TIMEOUT_SECONDS)
                        .setProgressMonitor(monitor)

                    command.call().use {
                        Log.i(TAG, "Full clone command returned")
                    }

                    fullCloneSucceeded = true
                    break
                } catch (error: Exception) {
                    lastError = error
                    Log.w(TAG, "Full clone attempt $attempt/$FULL_CLONE_ATTEMPTS failed: ${error.message}", error)

                    if (!isTransientNetworkError(error) || attempt == FULL_CLONE_ATTEMPTS) {
                        if (!isTransientNetworkError(error)) throw error
                        break
                    }
                }
            }

            if (!fullCloneSucceeded) {
                Log.w(TAG, "Full clone failed after retries; trying depth-1 clone. Last error: ${lastError?.message}")
                resetDestination(destination)

                try {
                    val shallowCommand = Git.cloneRepository()
                        .setURI(repositoryUrl)
                        .setDirectory(destination)
                        .setDepth(1)
                        .setTimeout(NETWORK_TIMEOUT_SECONDS)
                        .setProgressMonitor(monitor)

                    shallowCommand.call().use {
                        Log.i(TAG, "Depth-1 clone command returned")
                    }
                    Log.i(TAG, "Clone succeeded using depth-1 fallback; repository history is shallow")
                } catch (fallbackError: Exception) {
                    lastError?.let { fallbackError.addSuppressed(it) }
                    throw IOException(
                        "Не удалось клонировать репозиторий даже с глубиной 1: ${fallbackError.message}",
                        fallbackError
                    )
                }
            } else {
                Log.i(TAG, "Full clone succeeded; complete repository history is preserved")
            }

            Log.i(TAG, "Clone succeeded: ${destination.absolutePath}")
            Result.success(destination)
        } catch (error: Exception) {
            if (destinationPreparedByThisCall && destination.exists()) {
                val removed = destination.deleteRecursively()
                if (!removed) {
                    Log.w(TAG, "Could not completely remove partial clone directory: ${destination.absolutePath}")
                }
            }
            Log.e(
                TAG,
                "Clone failed: url=$repositoryUrl destination=${destination.absolutePath}: ${error.message}",
                error
            )
            Result.failure(error)
        }
    }

    private fun createProgressMonitor(
        onProgress: (task: String, completed: Int, total: Int) -> Unit
    ): ProgressMonitor = object : ProgressMonitor {
        private var taskName = "Подготовка клонирования"
        private var total = 0
        private var completed = 0
        private var lastLoggedPercent = -1

        override fun start(totalTasks: Int) {
            Log.i(TAG, "Clone tasks started: count=$totalTasks")
            onProgress(taskName, 0, 0)
        }

        override fun beginTask(title: String, totalWork: Int) {
            taskName = title
            total = totalWork
            completed = 0
            lastLoggedPercent = -1
            Log.i(TAG, "Task started: '$taskName' totalWork=$totalWork")
            onProgress(taskName, completed, total)
        }

        override fun update(completedWork: Int) {
            completed += completedWork
            if (total > 0) {
                val percent = (completed.toLong() * 100 / total)
                    .toInt()
                    .coerceIn(0, 100)
                if (percent != lastLoggedPercent &&
                    (percent == 100 || percent / 10 > lastLoggedPercent / 10)
                ) {
                    Log.i(TAG, "Task progress: '$taskName' $percent% ($completed/$total)")
                    lastLoggedPercent = percent
                }
            }
            onProgress(taskName, completed, total)
        }

        override fun endTask() {
            if (total > 0) completed = total
            Log.i(TAG, "Task finished: '$taskName'")
            onProgress(taskName, completed, total)
        }

        override fun isCancelled(): Boolean = false

        override fun showDuration(enabled: Boolean) {
            // Duration is displayed by the fragment progress label.
        }
    }

    private fun resetDestination(destination: File) {
        if (destination.exists()) {
            require(destination.deleteRecursively()) {
                "Не удалось очистить частичный клон: ${destination.absolutePath}"
            }
        }
        require(destination.mkdirs() || destination.isDirectory) {
            "Не удалось повторно создать папку ${destination.absolutePath}"
        }
    }

    private fun isTransientNetworkError(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val message = current.message.orEmpty().lowercase()
            if (current is SocketException ||
                current is SocketTimeoutException ||
                message.contains("connection abort") ||
                message.contains("connection reset") ||
                message.contains("timed out") ||
                message.contains("timeout") ||
                message.contains("unexpected end of stream") ||
                message.contains("cannot open git-upload-pack")
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun defaultDestination(): File =
        File(
            appContext.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                ?: appContext.filesDir,
            "DCIM"
        )
}
