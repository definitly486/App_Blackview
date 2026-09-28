package com.example.app.shell

import kotlin.system.exitProcess

/**
 * Запускается Shizuku в отдельном процессе с правами shell (как `adb shell`).
 * Root не используется.
 */
class ShellService : IShellService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun exec(command: String): String {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            "$code\n$output"
        } catch (e: Exception) {
            "-1\n${e.message ?: e.javaClass.simpleName}"
        }
    }
}
