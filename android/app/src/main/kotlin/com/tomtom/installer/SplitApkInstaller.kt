package com.tomtom.installer

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipFile

class SplitApkInstaller(private val activity: Activity) {

    interface InstallCallback {
        fun onSuccess()
        fun onError(message: String)
    }

    fun install(archivePath: String, callback: InstallCallback) {
        val file = File(archivePath)
        if (!file.exists()) { callback.onError("Fichier introuvable"); return }
        try {
            val apks = extractApks(file, activity.cacheDir).apks
            when {
                apks.isEmpty() -> callback.onError("Aucun APK trouvé")
                apks.size == 1 -> callback.onError("SINGLE:" + apks[0].absolutePath)
                else -> installSplits(apks, callback)
            }
        } catch (e: Exception) { callback.onError("Erreur: " + e.message) }
    }

    class ExtractedApks(val directory: File, val apks: List<File>) : java.io.Closeable {
        override fun close() { directory.deleteRecursively() }
    }

    companion object {
        fun extractApks(archive: File, cache: File): ExtractedApks {
            require(archive.isFile) { "Archive introuvable" }
            val dir = File.createTempFile("splits_", "", cache).also {
                check(it.delete() && it.mkdir()) { "Impossible de préparer les fichiers temporaires" }
            }
            try {
                val result = mutableListOf<File>()
                var total = 0L
                ZipFile(archive).use { zip ->
                    val entries = zip.entries().asSequence()
                        .filter { !it.isDirectory && it.name.endsWith(".apk", true) && !it.name.contains("__MACOSX") }
                        .take(257).toList()
                    require(entries.isNotEmpty() && entries.size <= 256) { "L’archive doit contenir entre 1 et 256 APK" }
                    entries.sortedWith(compareBy {
                        val name = File(it.name).name
                        if (name.equals("base.apk", true)) 0 else if (name.startsWith("base", true)) 1 else 2
                    }).forEachIndexed { index, entry ->
                        // Unique flat names prevent traversal and basename collisions.
                        val out = File(dir, "component$index.apk")
                        zip.getInputStream(entry).use { input -> out.outputStream().use { output ->
                            val buffer = ByteArray(65536)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                total += read
                                require(total <= 2L * 1024 * 1024 * 1024) { "Archive APK trop volumineuse" }
                                output.write(buffer, 0, read)
                            }
                        } }
                        require(out.length() > 0) { "Composant APK vide" }
                        result.add(out)
                    }
                }
                return ExtractedApks(dir, result)
            } catch (e: Exception) { dir.deleteRecursively(); throw e }
        }
    }

    private fun installSplits(apks: List<File>, callback: InstallCallback) {
        val pi = activity.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).also {
            it.setSize(apks.sumOf { f -> f.length() })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                it.setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
        }
        val sessionId = pi.createSession(params)
        val session = pi.openSession(sessionId)
        try {
            apks.forEach { apk ->
                session.openWrite(apk.name, 0, apk.length()).use { out ->
                    FileInputStream(apk).use { inp ->
                        val buf = ByteArray(65536); var n: Int
                        while (inp.read(buf).also { n = it } != -1) out.write(buf, 0, n)
                        session.fsync(out)
                    }
                }
            }
            val intent = Intent(activity, MainActivity::class.java).apply { action = "SPLIT_DONE" }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            else android.app.PendingIntent.FLAG_UPDATE_CURRENT
            session.commit(android.app.PendingIntent.getActivity(activity, sessionId, intent, flags).intentSender)
            callback.onSuccess()
        } catch (e: Exception) { session.abandon(); callback.onError(e.message ?: "Erreur") }
        finally { session.close() }
    }
}
