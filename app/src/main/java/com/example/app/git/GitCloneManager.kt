package com.example.app.git

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Состояние клонирования. Живёт в процессе приложения, а не во фрагменте. */
sealed interface GitCloneState {
    data object Idle : GitCloneState

    data class Running(
        val url: String,
        val repoName: String,
        val task: String,
        val completed: Int,
        val total: Int
    ) : GitCloneState

    data class Success(val repoName: String, val path: String) : GitCloneState

    data class Failed(val repoName: String, val message: String) : GitCloneState
}

/**
 * Единая точка управления клонированием.
 *
 * Фрагмент только запускает клонирование и подписывается на [state];
 * сама работа выполняется в [GitCloneService] (foreground-сервис), поэтому
 * переключение вкладок (замена фрагмента) её не прерывает.
 */
object GitCloneManager {

    private val _state = MutableStateFlow<GitCloneState>(GitCloneState.Idle)
    val state: StateFlow<GitCloneState> = _state.asStateFlow()

    @Volatile
    var cancelRequested: Boolean = false
        private set

    fun isRunning(): Boolean = _state.value is GitCloneState.Running

    /** @return false, если клонирование уже идёт. */
    @Synchronized
    fun start(context: Context, url: String, repoName: String, destination: File): Boolean {
        if (isRunning()) return false

        cancelRequested = false
        _state.value = GitCloneState.Running(url, repoName, "Подключение к репозиторию…", 0, 0)

        val intent = Intent(context, GitCloneService::class.java).apply {
            action = GitCloneService.ACTION_START
            putExtra(GitCloneService.EXTRA_URL, url)
            putExtra(GitCloneService.EXTRA_REPO_NAME, repoName)
            putExtra(GitCloneService.EXTRA_DESTINATION, destination.absolutePath)
        }
        try {
            ContextCompat.startForegroundService(context.applicationContext, intent)
        } catch (e: Exception) {
            _state.value = GitCloneState.Failed(repoName, e.message ?: e.javaClass.simpleName)
            return false
        }
        return true
    }

    fun cancel() {
        if (isRunning()) cancelRequested = true
    }

    internal fun publishProgress(task: String, completed: Int, total: Int) {
        val current = _state.value as? GitCloneState.Running ?: return
        _state.value = current.copy(task = task, completed = completed, total = total)
    }

    internal fun publishSuccess(repoName: String, path: String) {
        _state.value = GitCloneState.Success(repoName, path)
    }

    internal fun publishFailure(repoName: String, message: String) {
        _state.value = GitCloneState.Failed(repoName, message)
    }
}
