package com.example.app.git

import android.content.Context
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

    private val appContext = context.applicationContext

    suspend fun clone(
        repositoryUrl: String,
        destination: File = defaultDestination(),
        onProgress: (task: String, completed: Int, total: Int) -> Unit = { _, _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(repositoryUrl.isNotBlank()) { "Адрес репозитория не указан" }

            destination.parentFile?.mkdirs()
            require(destination.mkdirs() || destination.isDirectory) {
                "Не удалось создать папку ${destination.absolutePath}"
            }

            val monitor = object : ProgressMonitor {
                private var taskName = "Подготовка клонирования"
                private var total = 0
                private var completed = 0

                override fun start(totalTasks: Int) {
                    onProgress(taskName, 0, 0)
                }

                override fun beginTask(title: String, totalWork: Int) {
                    taskName = title
                    total = totalWork
                    completed = 0
                    onProgress(taskName, completed, total)
                }

                override fun update(completedWork: Int) {
                    completed += completedWork
                    onProgress(taskName, completed, total)
                }

                override fun endTask() {
                    if (total > 0) completed = total
                    onProgress(taskName, completed, total)
                }

                override fun isCancelled(): Boolean = false
            }

            Git.cloneRepository()
                .setURI(repositoryUrl)
                .setDirectory(destination)
                .setProgressMonitor(monitor)
                .call()
                .use { it.close() }

            destination
        }
    }

    private fun defaultDestination(): File =
        File(
            appContext.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                ?: appContext.filesDir,
            "DCIM"
        )
}
