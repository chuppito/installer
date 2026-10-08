package com.tomtom.installer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class PrivilegedApkInstallerTest {
    private class FakeProcess(private val output: String, private val code: Int = 0, private val error: String = "") : Process() {
        val received = ByteArrayOutputStream()
        override fun getOutputStream() = received
        override fun getInputStream() = ByteArrayInputStream(output.toByteArray())
        override fun getErrorStream() = ByteArrayInputStream(error.toByteArray())
        override fun waitFor() = code
        override fun exitValue() = code
        override fun destroy() {}
    }

    private fun withApk(block: (File, ByteArray) -> Unit) {
        val file = File.createTempFile("private apk '", ".apk")
        val bytes = ByteArray(200003) { (it % 256).toByte() }
        try {
            file.writeBytes(bytes)
            block(file, bytes)
        } finally { file.delete() }
    }

    @Test fun transfersPrivateApkBytesAndCommitsForCurrentUser() = withApk { apk, bytes ->
        val commands = mutableListOf<String>()
        val write = FakeProcess("Success: streamed ${bytes.size} bytes")
        val replies = ArrayDeque(listOf(
            FakeProcess("Success: created install session [703529308]"), write, FakeProcess("Success")
        ))
        PrivilegedApkInstaller { command -> commands.add(command); replies.removeFirst() }
            .install(apk, 10) {}
        assertArrayEquals(bytes, write.received.toByteArray())
        assertTrue(commands.first().contains("--user 10"))
        assertTrue(commands.first().contains("-i com.android.vending"))
        assertEquals("cmd package install-write -S ${bytes.size} 703529308 base.apk -", commands[1])
        assertEquals("cmd package install-commit 703529308", commands[2])
        assertTrue(commands.none { it.contains(apk.absolutePath) || it.contains("set-installer") })
        assertTrue(replies.isEmpty())
    }

    @Test fun writeFailureAbandonsSessionWithoutCommitting() = withApk { apk, _ ->
        val commands = mutableListOf<String>()
        val replies = ArrayDeque(listOf(
            FakeProcess("Success: created install session [42]"),
            FakeProcess("", 255, "Error: denied"), FakeProcess("Success")
        ))
        try {
            PrivilegedApkInstaller { command -> commands.add(command); replies.removeFirst() }.install(apk, 0) {}
            fail("Expected failure")
        } catch (e: IOException) { assertTrue(e.message!!.contains("denied")) }
        assertEquals("cmd package install-abandon 42", commands.last())
        assertTrue(commands.none { it.contains("install-commit") })
    }

    @Test fun failedCommitIsNotReportedAsSuccess() = withApk { apk, _ ->
        val commands = mutableListOf<String>()
        val replies = ArrayDeque(listOf(
            FakeProcess("Success: created install session [42]"), FakeProcess("Success"),
            FakeProcess("Success", 1, "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]"), FakeProcess("Success")
        ))
        try {
            PrivilegedApkInstaller { command -> commands.add(command); replies.removeFirst() }.install(apk, 0) {}
            fail("Expected failure despite the word Success")
        } catch (e: IOException) { assertTrue(e.message!!.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE")) }
        assertEquals("cmd package install-abandon 42", commands.last())
    }

    @Test fun emptyApkDoesNotCreateSession() {
        val apk = File.createTempFile("empty", ".apk")
        try {
            try {
                PrivilegedApkInstaller { throw AssertionError("Must not execute") }.install(apk, 0) {}
                fail("Expected empty APK failure")
            } catch (e: IOException) { assertTrue(e.message!!.contains("vide")) }
        } finally { apk.delete() }
    }

    @Test fun invalidCreationOutputDoesNotWriteOrCommit() = withApk { apk, _ ->
        var calls = 0
        try {
            PrivilegedApkInstaller { calls++; FakeProcess("Failure [INSTALL_FAILED_INTERNAL_ERROR]", 1) }
                .install(apk, 0) {}
            fail("Expected session creation failure")
        } catch (e: IOException) { assertEquals(1, calls) }
    }
}
