package com.tomtom.installer

import android.os.RemoteException
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Shizuku UserService.
 *
 * This process is started by Shizuku with shell UID (2000) when Shizuku is
 * backed by ADB, or root UID (0) when backed by Sui/root.
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
