package com.tomtom.installer

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.system.Os

/** One-shot, separate UserService. It never changes the UID of the Shizuku server. */
class RootNativeInstallerService : Binder() {
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == INTERFACE_TRANSACTION) { reply?.writeString(DESCRIPTOR); return true }
        if (code == 0x00FFFFFE) { System.exit(0); return true }
        if (code != LAUNCH) return super.onTransact(code, data, reply, flags)
        data.enforceInterface(DESCRIPTOR)
        val libraryPath = data.readString() ?: error("Bibliothèque de lancement absente")
        val intent = Intent.CREATOR.createFromParcel(data)
        val userId = data.readInt()
        val identity = Binder.clearCallingIdentity()
        try {
            NativeShellCredentials.load(libraryPath)
            ShellLaunchIdentity.prepare(object : ShellLaunchIdentity.Credentials {
                override fun uid(): Int = Os.getuid()
                override fun clearGroups() {
                    NativeShellCredentials.clearGroups()
                }
                override fun gid(value: Int) { Os.setgid(value) }
                override fun uid(value: Int) { Os.setuid(value) }
            })
            check(Os.geteuid() == 2000 && Os.getgid() == 2000) { "Identité Shell non appliquée" }
            // Do not use ShizukuBinderWrapper here: it would forward as root again.
            val result = NativeInstallerLauncher.launchFromShellProcess(intent, userId)
            reply?.writeNoException()
            reply?.writeInt(result)
        } catch (e: Exception) {
            val cause = e.cause ?: e
            reply?.writeException(IllegalStateException("Lancement root : ${cause.message}"))
        } finally { Binder.restoreCallingIdentity(identity) }
        return true
    }

    companion object {
        private const val DESCRIPTOR = "com.tomtom.installer.RootNativeInstallerService"
        private const val LAUNCH = IBinder.FIRST_CALL_TRANSACTION
        fun launch(remote: IBinder, intent: Intent, userId: Int, libraryPath: String): Int {
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeString(libraryPath)
                intent.writeToParcel(data, 0)
                data.writeInt(userId)
                check(remote.transact(LAUNCH, data, reply, 0)) { "Service de lancement incompatible" }
                reply.readException()
                return reply.readInt()
            } finally { data.recycle(); reply.recycle() }
        }
    }
}
