package com.example.app.terminal

import android.content.Context
import android.os.Environment
import com.example.app.shell.AdbShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Coordinates terminal commands without blocking the UI thread.
 *
 * The controller owns its coroutine scope and can be closed when the screen is
 * destroyed, preventing callbacks from outliving the Fragment view.
 */
class TerminalController(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val registry = CommandRegistry()

    var onOutput: ((String) -> Unit)? = null

    /**
     * Simple shell commands that can be entered directly,
     * without the "shell" prefix.
     */
    private val directShellCommands = setOf(
        "uname",
        "whoami",
        "id",
        "pwd",
        "ls",
        "date",
        "uptime",
        "df",
        "du",
        "free",
        "ps",
        "env",
        "printenv",
        "getprop",
        "mount",
        "id",
        "which",
        "cat",
        "head",
        "tail"
    )

    fun printWelcome() {
        emit("Android Shell Terminal v1.0")
        emit("Type 'help' for available commands")

        appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let {
            emit("Working directory: ${it.absolutePath}")
        }
    }

    fun execute(input: String) {
        val commandLine = input.trim()
        if (commandLine.isEmpty()) return

        emit("> $commandLine")

        when {
            commandLine.startsWith("shell ") ->
                executeShellCommand(
                    commandLine.removePrefix("shell ").trim()
                )

            commandLine.startsWith("adb ") ->
                executeAdbCommand(
                    commandLine.removePrefix("adb ").trim()
                )

            else -> {
                val parts = commandLine.split(Regex("\\s+"))
                val command = parts.first()
                val args = parts.drop(1)

                if (command in directShellCommands) {
                    executeShellCommand(commandLine)
                } else {
                    emit(registry.execute(command, args))
                }
            }
        }
    }

    private fun executeShellCommand(command: String) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val directory = appContext
                        .getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                        ?: return@withContext "Error: download directory unavailable"

                    ProcessBuilder("sh", "-c", command)
                        .directory(File(directory.absolutePath))
                        .redirectErrorStream(true)
                        .start()
                        .let { process ->
                            val output = process.inputStream
                                .bufferedReader()
                                .use { it.readText() }

                            val exitCode = process.waitFor()

                            buildString {
                                if (output.isNotBlank()) {
                                    append(output.trimEnd())
                                }

                                if (exitCode != 0) {
                                    if (isNotEmpty()) {
                                        append('\n')
                                    }

                                    append("(код возврата: $exitCode)")
                                }
                            }
                        }
                }.getOrElse {
                    "Error executing command: " +
                        (it.message ?: it.javaClass.simpleName)
                }
            }

            emit(result)
        }
    }

    private fun executeAdbCommand(command: String) {
        scope.launch {
            if (!AdbShell.isRunning()) {
                emit("Shizuku не запущен.")
                return@launch
            }

            if (!AdbShell.hasPermission()) {
                emit(
                    "Нет разрешения Shizuku. " +
                        "Выдайте его на вкладке «Настройка»."
                )
                return@launch
            }

            val result = AdbShell.exec(appContext, command)

            if (result.output.isNotBlank()) {
                emit(result.output)
            }

            if (!result.ok) {
                emit("(код возврата: ${result.exitCode})")
            }
        }
    }

    private fun emit(text: String) {
        onOutput?.invoke(text)
    }

    fun close() {
        onOutput = null
        scope.cancel()
    }
}