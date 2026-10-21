package com.example.app.git

import android.content.Context
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
 * Full clone (complete history, real .git) implemented as init + fetch + checkout,
 * tuned for repositories with very large objects, with retries on network errors.
 */
class GitRepositoryCloner(context: Context) {

    companion object {
        private const val CLONE_ATTEMPTS = 5
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

            tuneJGitMemory()
            val monitor = createProgressMonitor(onProgress)
            var lastError: Throwable? = null
            var succeeded = false

            for (attempt in 1..CLONE_ATTEMPTS) {
                if (attempt > 1) {
                    val pauseMs = 2_000L * attempt
                    delay(pauseMs)
                    resetDestination(destination)
                }
                try {
                    cloneViaFetch(repositoryUrl, destination, monitor)
                    succeeded = true
                    break
                } catch (error: Exception) {
                    lastError = error
                    if (!isTransientNetworkError(error)) throw error
                } catch (oom: OutOfMemoryError) {
                    // OOM - это Error, а не Exception: без этого приложение падало бы целиком
                    lastError = oom
                    System.gc()
                }
            }

            if (!succeeded) {
                throw IOException(
                    "Не удалось клонировать репозиторий за $CLONE_ATTEMPTS попыток: ${lastError?.message}",
                    lastError
                )
            }

            Result.success(destination)
        } catch (error: Exception) {
            if (destinationPreparedByThisCall && destination.exists()) {
                destination.deleteRecursively()
            }
            Result.failure(error)
        }
    }

    /**
     * Эквивалент `git clone`, но с тонкой настройкой приёма пакета.
     *
     * Почему не Git.cloneRepository(): в репозитории всего 59 объектов, а по умолчанию
     * JGit при < 100 объектов (transfer.unpackLimit) распаковывает каждый объект в loose-файл,
     * то есть целиком разворачивает в памяти и заново жмёт GPX на 45 и 93 МБ. Это долго,
     * съедает кучу и приводит к паузам чтения из сокета. При unpackLimit=1 пакет
     * сохраняется как есть, без пересжатия.
     */
    private fun cloneViaFetch(url: String, destination: File, monitor: ProgressMonitor) {
        Git.init().setDirectory(destination).call().use { git ->
            val config = git.repository.config
            config.setInt("transfer", null, "unpackLimit", 1)
            config.setInt("fetch", null, "unpackLimit", 1)
            config.setString("remote", "origin", "url", url)
            config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*")
            config.save()

            val result = git.fetch()
                .setRemote("origin")
                .setTimeout(NETWORK_TIMEOUT_SECONDS)
                .setProgressMonitor(monitor)
                .setTagOpt(org.eclipse.jgit.transport.TagOpt.FETCH_TAGS)
                .call()

            val headRef = result.getAdvertisedRef("HEAD")
            val branch = (headRef?.target?.name ?: headRef?.name)
                ?.takeIf { it.startsWith("refs/heads/") }
                ?.removePrefix("refs/heads/")
                ?: listOf("main", "master").firstOrNull {
                    git.repository.exactRef("refs/remotes/origin/$it") != null
                }
                ?: throw IOException("Не удалось определить ветку по умолчанию")

            git.checkout()
                .setCreateBranch(true)
                .setName(branch)
                .setStartPoint("refs/remotes/origin/$branch")
                .setUpstreamMode(org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode.TRACK)
                .call()
        }
    }

    /**
     * Настройка кэша JGit. Потоковое чтение больших объектов (LargePackedWholeObject)
     * на Android падает при checkout с "Inflater has been closed", поэтому порог
     * потокового чтения поднят выше размера самого тяжёлого файла (93 МБ): такие объекты
     * читаются целиком в память (для этого нужен android:largeHeap="true").
     */
    private fun tuneJGitMemory() {
        try {
            org.eclipse.jgit.storage.file.WindowCacheConfig().apply {
                streamFileThreshold = 200 * 1024 * 1024
                deltaBaseCacheLimit = 64 * 1024 * 1024
            }.install()
        } catch (e: Exception) {
            // настройка необязательна, клонирование продолжится с параметрами по умолчанию
        }
    }

    private fun createProgressMonitor(
        onProgress: (task: String, completed: Int, total: Int) -> Unit
    ): ProgressMonitor = object : ProgressMonitor {
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
                message.contains("cannot open git-upload-pack") ||
                message.contains("premature eof") ||
                message.contains("early eof")
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
