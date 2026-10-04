package com.example.app.terminal

class CommandRegistry {

    private val commands: Map<String, (List<String>) -> String> = mapOf(

        "help" to {
            """
            Available commands:

            Built-in:
              help
              clear
              echo <text>

            Shell:
              uname
              uname -a
              whoami
              id
              pwd
              ls
              ls -la
              date
              uptime
              df
              du
              free
              ps
              env
              printenv
              getprop
              mount
              which <command>
              cat <file>
              head <file>
              tail <file>

            Advanced:
              shell <command> - Run arbitrary shell command
              adb <command>   - Run command as adb shell (needs Shizuku)
            """.trimIndent()
        },

        "echo" to { args ->
            args.joinToString(" ")
        },

        "clear" to {
            "\u000C"
        }
    )

    fun execute(
        command: String,
        args: List<String>
    ): String =
        commands[command]?.invoke(args)
            ?: "Command not found: $command"
}