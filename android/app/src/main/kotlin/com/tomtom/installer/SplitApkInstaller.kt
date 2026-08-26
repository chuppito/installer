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
            val apks = extractApks(file)
            when {
                apks.isEmpty() -> callback.onError("Aucun APK trouvé")
                apks.size == 1 -> callback.onError("SINGLE:${apks[0].absolutePath}")
                else -> installSplits(apks, callback)
            }
        } catch (e: Exception) { callback.onError("Erreur: ${e.message}") }
    }

    private fun extractApks(archive: File): List<File> {
        val dir = File(activity.cacheDir, "splits_${System.currentTimeMillis()}").also { it.mkdirs() }
        val result = mutableListOf<File>()
        ZipFile(archive).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".apk", true) && !it.name.contains("__MACOSX") }
                .forEach { entry ->
                    val out = File(dir, File(entry.name).name)
                    zip.getInputStream(entry).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                    result.add(out)
                }
        }
        return result.sortedWith(compareBy { if (it.name == "base.apk") 0 else if (it.name.startsWith("base")) 1 else 2 })
    }

    private fun installSplits(apks: List<File>, callback: InstallCallback) {
        val pi = activity.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).also {
            it.setInstallerPackageName("com.android.vending")
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
