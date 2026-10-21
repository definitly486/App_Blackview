package com.example.app.git

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ProgressMonitor
import java.io.File

/**
 * Git clone service. Paths are derived from the app context instead of
 * hard-coded package/storage paths.
 */
class GitRepositoryCloner(context: Context) {

    companion object {
        private const val TAG = "GitClone"
    }

    private val appContext = context.applicationContext

    suspend fun clone(
        repositoryUrl: String,
        destination: File = defaultDestination(),
        onProgress: (task: String, completed: Int, total: Int) -> Unit = { _, _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(repositoryUrl.isNotBlank()) { "Адрес репозитория не указан" }
            Log.i(TAG, "Clone started: url=$repositoryUrl destination=${destination.absolutePath}")

            destination.parentFile?.mkdirs()
            require(destination.mkdirs() || destination.isDirectory) {
                "Не удалось создать папку ${destination.absolutePath}"
            }

            val monitor = object : ProgressMonitor {
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
                        val percent = (completed.toLong() * 100 / total).toInt().coerceIn(0, 100)
                        if (percent != lastLoggedPercent && (percent == 100 || percent / 10 > lastLoggedPercent / 10)) {
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
                    // Duration display is handled by the fragment progress label.
                }
            }

            // Git.use closes the returned Git instance exactly once.
            // Do not call close() inside the use block: that would close it twice.
            Git.cloneRepository()
                .setURI(repositoryUrl)
                .setDirectory(destination)
                .setProgressMonitor(monitor)
                .call()
                .use { clonedGit ->
                    Log.i(TAG, "Clone command returned; closing Git resources")
                }

            Log.i(TAG, "Clone succeeded: ${destination.absolutePath}")
            destination
        }.onFailure { error ->
            Log.e(TAG, "Clone failed: url=$repositoryUrl destination=${destination.absolutePath}: ${error.message}", error)
        }
    }

    private fun defaultDestination(): File =
        File(
            appContext.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                ?: appContext.filesDir,
            "DCIM"
        )
}
