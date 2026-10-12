package com.tomtom.installer

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal object RootNativeInstallerLauncher {
    /** Called from MainActivity's installation worker, never blocking the UI thread. */
    fun launch(activity: Activity, intent: Intent, log: (String) -> Unit): Int {
        val ready = CountDownLatch(1)
        val remote = AtomicReference<IBinder?>()
        val failure = AtomicReference<Exception?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                remote.set(service); ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { remote.set(null) }
        }
        @Suppress("DEPRECATION")
        val version = activity.packageManager.getPackageInfo(activity.packageName, 0).versionCode
        val args = Shizuku.UserServiceArgs(ComponentName(activity, RootNativeInstallerService::class.java))
            .daemon(false).processNameSuffix("native_installer").version(version)
        activity.runOnUiThread {
            try {
                check(!activity.isFinishing && !activity.isDestroyed) { "Installer a été fermé" }
                Shizuku.bindUserService(args, connection)
            } catch (e: Exception) { failure.set(e); ready.countDown() }
        }
        try {
            check(ready.await(20, TimeUnit.SECONDS)) { "Le lanceur Shizuku ne répond pas" }
            failure.get()?.let { throw it }
            val service = remote.get() ?: error("Lanceur Shizuku déconnecté")
            val library = java.io.File(activity.applicationInfo.nativeLibraryDir, "libinstaller_launch.so")
            check(library.isFile) { "Bibliothèque native de lancement absente" }
            val result = RootNativeInstallerService.launch(service, intent, Process.myUid() / 100000, library.absolutePath)
            log("Identité réelle du lanceur : ${result.identity}")
            return result.code
        } finally {
            activity.runOnUiThread {
                try { Shizuku.unbindUserService(args, connection, true) } catch (_: Exception) {}
            }
        }
    }
}
