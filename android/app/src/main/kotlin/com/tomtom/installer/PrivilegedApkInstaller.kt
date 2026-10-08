package com.tomtom.installer

import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.concurrent.thread

/** The app opens its private APK; the privileged process receives only bytes. */
internal class PrivilegedApkInstaller(private val startProcess: (String) -> Process) {
    private data class CommandResult(val code: Int, val output: String)

    private fun execute(command: String, input: InputStream? = null): CommandResult {
        val process = startProcess(command)
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        var stdoutFailure: Exception? = null
        var stderrFailure: Exception? = null
        val outReader = thread {
            try { stdout.append(process.inputStream.bufferedReader().use { it.readText() }) }
            catch (e: Exception) { stdoutFailure = e }
        }
        val errReader = thread {
            try { stderr.append(process.errorStream.bufferedReader().use { it.readText() }) }
            catch (e: Exception) { stderrFailure = e }
        }
        try {
            process.outputStream.use { output -> input?.copyTo(output, 65536) }
            val code = process.waitFor()
            outReader.join()
            errReader.join()
            stdoutFailure?.let { throw it }
            stderrFailure?.let { throw it }
            return CommandResult(code, listOf(stdout.toString().trim(), stderr.toString().trim())
                .filter { it.isNotEmpty() }.joinToString("\n"))
        } finally {
            process.destroy()
        }
    }

    private fun requireSuccess(result: CommandResult, step: String): String {
        if (result.code != 0 || !result.output.lineSequence().any { it.startsWith("Success") }) {
            throw IOException("$step: exit=${result.code}\n${result.output}")
        }
        return result.output
    }

    fun install(apk: File, userId: Int, log: (String) -> Unit) {
        if (!apk.isFile || !apk.canRead()) throw IOException("APK introuvable ou illisible: ${apk.name}")
        val size = apk.length()
        if (size <= 0) throw IOException("APK vide: ${apk.name}")
        var sessionId: String? = null
        var committed = false
        try {
            val created = requireSuccess(execute(
                "cmd package install-create --user $userId -r -t -i com.android.vending -S $size"
            ), "Création de session")
            sessionId = Regex("Success: created install session \\[([0-9]+)]")
                .find(created)?.groupValues?.get(1)
                ?: throw IOException("ID de session absent: $created")
            log("install-create -> $created")
            val written = apk.inputStream().use { input ->
                requireSuccess(execute(
                    "cmd package install-write -S $size $sessionId base.apk -", input
                ), "Transfert APK")
            }
            log("install-write session=$sessionId -> $written")
            val installed = requireSuccess(execute(
                "cmd package install-commit $sessionId"
            ), "Validation de session")
            committed = true
            log("install-commit session=$sessionId -> $installed")
        } finally {
            if (!committed && sessionId != null) {
                try {
                    val abandoned = execute("cmd package install-abandon $sessionId")
                    log("install-abandon session=$sessionId -> exit=${abandoned.code} ${abandoned.output}")
                } catch (e: Exception) {
                    log("Impossible d'abandonner la session $sessionId: ${e.message}")
                }
            }
        }
    }
}
