package com.tomtom.installer

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ShizukuSplitInstaller(private val activity: Activity, private val log: (String) -> Unit) {
    fun install(path: String, complete: (String?, String?) -> Unit) {
        Thread {
            var extracted: SplitApkInstaller.ExtractedApks? = null
            try {
                val bundle = SplitApkInstaller.extractApks(java.io.File(path), activity.cacheDir)
                extracted = bundle
                @Suppress("DEPRECATION")
                val info = bundle.apks.mapNotNull { activity.packageManager.getPackageArchiveInfo(it.absolutePath, 0) }.firstOrNull()
                    ?: error("APK de base introuvable ou invalide")
                info.applicationInfo?.apply {
                    sourceDir = bundle.apks.first().absolutePath
                    publicSourceDir = sourceDir
                }
                val name = info.applicationInfo?.loadLabel(activity.packageManager)?.toString() ?: info.packageName
                val updated = try { activity.packageManager.getPackageInfo(info.packageName, 0); true } catch (_: Exception) { false }
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) {
                        bundle.close(); complete(null, "Installation interrompue"); return@runOnUiThread
                    }
                    AlertDialog.Builder(activity)
                        .setTitle(if (updated) "Mettre à jour cette application ?" else "Installer cette application ?")
                        .setMessage("$name\n${bundle.apks.size} composant(s) APK — Shizuku")
                        .setNegativeButton("Annuler") { _, _ -> bundle.close(); complete("install_cancelled", null) }
                        .setOnCancelListener { bundle.close(); complete("install_cancelled", null) }
                        .setPositiveButton(if (updated) "Mettre à jour" else "Installer") { _, _ ->
                            runInstall(bundle, info.packageName, name, updated, complete)
                        }.show()
                }
            } catch (e: Exception) {
                extracted?.close()
                activity.runOnUiThread { complete(null, e.message ?: "Archive invalide") }
            }
        }.start()
    }

    private fun runInstall(bundle: SplitApkInstaller.ExtractedApks, packageName: String, name: String,
                           updated: Boolean, complete: (String?, String?) -> Unit) {
        val ready = CountDownLatch(1)
        var remote: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { remote = service; ready.countDown() }
            override fun onServiceDisconnected(name: ComponentName) { remote = null }
        }
        @Suppress("DEPRECATION")
        val version = activity.packageManager.getPackageInfo(activity.packageName, 0).versionCode
        val args = Shizuku.UserServiceArgs(ComponentName(activity, SplitShizukuService::class.java))
            .daemon(false).processNameSuffix("split_installer").version(version)
        try { Shizuku.bindUserService(args, connection) }
        catch (e: Exception) { bundle.close(); complete(null, e.message); return }
        Thread {
            val descriptors = mutableListOf<ParcelFileDescriptor>()
            try {
                check(ready.await(20, TimeUnit.SECONDS)) { "Le service Shizuku ne répond pas" }
                val service = remote ?: error("Service Shizuku déconnecté")
                bundle.apks.forEach { descriptors.add(ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY)) }
                log("Installation de ${bundle.apks.size} composants pour $packageName")
                val output = SplitShizukuService.install(service, descriptors, bundle.apks.map { it.length() }.toLongArray(), Process.myUid() / 100000)
                log(output)
                check(output == "Success") { output }
                activity.runOnUiThread {
                    complete("install_success", null)
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        val launch = activity.packageManager.getLaunchIntentForPackage(packageName)
                        AlertDialog.Builder(activity).apply {
                            setTitle(if (updated) "Application mise à jour" else "Application installée")
                            setMessage(name)
                            setNegativeButton("OK") { _, _ -> }
                            if (launch != null) setPositiveButton("Ouvrir") { _, _ -> activity.startActivity(launch) }
                        }.show()
                    }
                }
            } catch (e: Exception) {
                log("ERREUR: ${e.message}")
                activity.runOnUiThread { complete(null, e.message ?: "Installation échouée") }
            } finally {
                descriptors.forEach { try { it.close() } catch (_: Exception) {} }
                bundle.close()
                activity.runOnUiThread { try { Shizuku.unbindUserService(args, connection, true) } catch (_: Exception) {} }
            }
        }.start()
    }
}
