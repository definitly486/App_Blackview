import android.content.Context
import android.os.Environment
import com.example.app.shell.AdbShell
import com.example.app.terminal.CommandRegistry
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.File
import kotlin.concurrent.thread

class TerminalController(private val context: Context) {

    var onOutput: ((String) -> Unit)? = null
    private val registry = CommandRegistry()

    fun printWelcome() {
        onOutput?.invoke("Android Shell Terminal v1.0")
        onOutput?.invoke("Type 'help' for available commands")

        // Переход в директорию приложения (Downloads)
        val appDirectory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        appDirectory?.let {
            onOutput?.invoke("Changing to directory: ${it.absolutePath}")
        }
    }

    fun execute(input: String) {
        if (input.isBlank()) return

        onOutput?.invoke("> $input")

        // shell — обычный shell приложения, adb — команды с правами shell (через Shizuku, без root)
        if (input.startsWith("shell ")) {
            val command = input.removePrefix("shell ").trim()
            executeShellCommand(command)
        } else if (input.startsWith("adb ")) {
            val command = input.removePrefix("adb ").trim()
            executeAdbCommand(command)
        } else {
            // Обработка зарегистрированных команд
            val parts = input.split(" ")
            val command = parts.first()
            val args = parts.drop(1)

            val result = registry.execute(command, args)
            onOutput?.invoke(result)
        }
    }


    // Выполнение обычной shell команды
    private fun executeShellCommand(command: String) {
        thread {
            try {
                // Получаем путь к директории приложения
                val appDirectory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                appDirectory?.let {
                    // Запускаем команду в этой директории
                    val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "cd ${it.absolutePath} && $command"))

                    val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
                    val errorOutput = BufferedReader(InputStreamReader(process.errorStream)).readText()

                    if (output.isNotBlank()) {
                        onOutput?.invoke(output)
                    }
                    if (errorOutput.isNotBlank()) {
                        onOutput?.invoke(errorOutput)
                    }
                }
            } catch (e: Exception) {
                onOutput?.invoke("Error executing command: ${e.message}")
            }
        }
    }

    // Выполнение команды с правами shell (как `adb shell`) через Shizuku
    private fun executeAdbCommand(command: String) {
        thread {
            if (!AdbShell.isRunning()) {
                onOutput?.invoke("Shizuku не запущен.")
                return@thread
            }
            if (!AdbShell.hasPermission()) {
                onOutput?.invoke("Нет разрешения Shizuku. Выдайте его на вкладке «Настройка».")
                return@thread
            }
            val result = runBlocking { AdbShell.exec(context, command) }
            if (result.output.isNotBlank()) onOutput?.invoke(result.output)
            if (!result.ok) onOutput?.invoke("(код возврата: ${result.exitCode})")
        }
    }
}
