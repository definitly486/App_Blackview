package com.example.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandRegistryTest {

    private val registry = CommandRegistry()

    @Test
    fun echoJoinsArguments() {
        assertEquals("hello world", registry.execute("echo", listOf("hello", "world")))
    }

    @Test
    fun unknownCommandReturnsDiagnostic() {
        assertEquals("Command not found: missing", registry.execute("missing", emptyList()))
    }

    @Test
    fun clearReturnsTerminalControlMarker() {
        assertEquals("\u000C", registry.execute("clear", emptyList()))
    }
}
