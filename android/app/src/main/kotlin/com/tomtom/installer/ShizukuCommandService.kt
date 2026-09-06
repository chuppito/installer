package com.tomtom.installer

import android.os.RemoteException
import java.io.BufferedReader
import java.io.InputStreamReader
import rikka.shizuku.Shizuku

/**
 * Shizuku UserService: executes commands as shell (UID 2000) with Shizuku,
 * or as root when Shizuku is backed by Sui/root.
 *
 * This deliberately does not use Shizuku.newProcess(), which is private in
 * recent Shizuku API versions and is scheduled for removal.
 */
class ShizukuCommandService : IShizukuCommandService.Stub() {
    override fun execute(command: String): String {
        return try {
            val process = ProcessBuilder("/system/bin/sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val code = process.waitFor()
            "exit=$code ${output.trim()}".trim()
        } catch (e: Exception) {
            "ERROR:${e.message}"
        }
    }

    override fun destroy() {
        System.exit(0)
    }
}
