package com.example.app.git

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import java.io.File

/**
 * Git clone service. Paths are derived from the app context instead of
 * hard-coded package/storage paths.
 */
class GitRepositoryCloner(context: Context) {

    private val appContext = context.applicationContext

    suspend fun clone(
        repositoryUrl: String,
        destination: File = defaultDestination()
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(repositoryUrl.isNotBlank()) { "Адрес репозитория не указан" }

            destination.parentFile?.mkdirs()
            require(destination.mkdirs() || destination.isDirectory) {
                "Не удалось создать папку ${destination.absolutePath}"
            }

            Git.cloneRepository()
                .setURI(repositoryUrl)
                .setDirectory(destination)
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
