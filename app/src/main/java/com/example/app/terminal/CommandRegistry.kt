package com.example.app.terminal

class CommandRegistry {

    private val commands: Map<String, (List<String>) -> String> = mapOf(
        "help" to {
            """
            Available commands:
            help
            clear
            echo <text>
            shell <command> - Run shell command
            adb <command>   - Run command as adb shell (needs Shizuku)
            """.trimIndent()
        },
        "echo" to { args -> args.joinToString(" ") },
        "clear" to { "\u000C" }
    )

    fun execute(command: String, args: List<String>): String =
        commands[command]?.invoke(args) ?: "Command not found: $command"
}
