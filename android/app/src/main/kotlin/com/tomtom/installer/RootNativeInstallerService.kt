package com.tomtom.installer

import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.system.Os
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One-shot UserService. Shizuku's server keeps its original identity. */
class RootNativeInstallerService : Binder() {
    private val submitted = AtomicBoolean(false)

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == INTERFACE_TRANSACTION) { reply?.writeString(DESCRIPTOR); return true }
        if (code == 0x00FFFFFE) { System.exit(0); return true }
        if (code != LAUNCH) return super.onTransact(code, data, reply, flags)
        data.enforceInterface(DESCRIPTOR)
        val libraryPath = data.readString() ?: error("Bibliothèque de lancement absente")
        val intent = Intent.CREATOR.createFromParcel(data)
        val userId = data.readInt()
        try {
            check(submitted.compareAndSet(false, true)) { "Ce lanceur a déjà été utilisé" }
            val looper = Looper.getMainLooper() ?: error("Thread principal Shizuku indisponible")
            val ready = CountDownLatch(1)
            val output = AtomicReference<LaunchResult?>()
            val failure = AtomicReference<Throwable?>()
            check(Handler(looper).post {
                try {
                    // Android's setuid applies to the calling thread. Binder uses the
                    // process leader's credentials, so never do this on a Binder worker.
                    check(Process.myTid() == Process.myPid()) { "Le lanceur n’est pas sur le thread principal" }
                    NativeShellCredentials.load(libraryPath)
                    ShellLaunchIdentity.prepare(object : ShellLaunchIdentity.Credentials {
                        override fun uid(): Int = Os.getuid()
                        override fun clearGroups() { NativeShellCredentials.clearGroups() }
                        override fun gid(value: Int) { Os.setgid(value) }
                        override fun uid(value: Int) { Os.setuid(value) }
                    })
                    check(Os.geteuid() == 2000 && Os.getgid() == 2000) { "Identité Shell non appliquée" }
                    ShellLaunchIdentity.verifyLeader(File("/proc/self/status").readText(), Process.myPid(), Process.myTid())
                    val identity = Binder.clearCallingIdentity()
                    try {
                        // Forwarding through ShizukuBinderWrapper would send as root again.
                        val result = NativeInstallerLauncher.launchFromShellProcess(intent, userId)
                        output.set(LaunchResult(result,
                            "pid=${Process.myPid()}, tid=${Process.myTid()}, uid=${Os.getuid()}, euid=${Os.geteuid()}, leader vérifié"))
                    } finally { Binder.restoreCallingIdentity(identity) }
                } catch (e: Throwable) { failure.set(e) }
                finally { ready.countDown() }
            }) { "Impossible de programmer le lancement" }
            check(ready.await(20, TimeUnit.SECONDS)) { "Le thread principal du lanceur ne répond pas" }
            failure.get()?.let { throw IllegalStateException(it.cause?.message ?: it.message, it) }
            val result = output.get() ?: error("Résultat du lanceur absent")
            reply?.writeNoException()
            reply?.writeInt(result.code)
            reply?.writeString(result.identity)
        } catch (e: Exception) {
            reply?.writeException(IllegalStateException("Lancement root : ${e.message}"))
        }
        return true
    }

    data class LaunchResult(val code: Int, val identity: String)

    companion object {
        private const val DESCRIPTOR = "com.tomtom.installer.RootNativeInstallerService"
        private const val LAUNCH = IBinder.FIRST_CALL_TRANSACTION
        fun launch(remote: IBinder, intent: Intent, userId: Int, libraryPath: String): LaunchResult {
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeString(libraryPath)
                intent.writeToParcel(data, 0)
                data.writeInt(userId)
                check(remote.transact(LAUNCH, data, reply, 0)) { "Service de lancement incompatible" }
                reply.readException()
                return LaunchResult(reply.readInt(), reply.readString() ?: "Identité absente")
            } finally { data.recycle(); reply.recycle() }
        }
    }
}
