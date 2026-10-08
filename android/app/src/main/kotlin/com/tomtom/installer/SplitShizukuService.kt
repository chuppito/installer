package com.tomtom.installer

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Build
import java.util.Timer
import kotlin.concurrent.schedule

/** Runs in Shizuku's shell process. File descriptors avoid private-cache access restrictions. */
class SplitShizukuService : Binder() {
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == INTERFACE_TRANSACTION) { reply?.writeString(DESCRIPTOR); return true }
        if (code == 0x00FFFFFE) { System.exit(0); return true }
        if (code != INSTALL) return super.onTransact(code, data, reply, flags)
        data.enforceInterface(DESCRIPTOR)
        val files = data.createTypedArrayList(ParcelFileDescriptor.CREATOR) ?: arrayListOf()
        val sizes = data.createLongArray() ?: longArrayOf()
        val user = data.readInt()
        val output = try { install(files, sizes, user) }
            catch (e: Exception) { "ERROR: ${e.message}" }
        finally { files.forEach { try { it.close() } catch (_: Exception) {} } }
        reply?.writeNoException()
        reply?.writeString(output)
        return true
    }

    private fun command(arguments: List<String>, input: ParcelFileDescriptor? = null): String {
        val process = ProcessBuilder(listOf("/system/bin/cmd", "package") + arguments)
            .redirectErrorStream(true).start()
        val timer = Timer(true)
        val timeout = timer.schedule(180000L) { process.destroy() }
        try {
            process.outputStream.use { output ->
                if (input != null) ParcelFileDescriptor.AutoCloseInputStream(input).use { it.copyTo(output) }
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            check(process.waitFor() == 0 && output.startsWith("Success")) { output.ifEmpty { "Commande interrompue" } }
            return output
        } finally { timeout.cancel(); timer.cancel(); process.destroy() }
    }

    private fun install(files: List<ParcelFileDescriptor>, sizes: LongArray, user: Int): String {
        require(files.isNotEmpty() && files.size <= 256 && files.size == sizes.size && sizes.all { it > 0 }) {
            "Composants APK invalides"
        }
        return SplitInstallSession { arguments, index ->
            command(arguments, index?.let { files[it] })
        }.install(sizes, user, Build.VERSION.SDK_INT >= 33)
    }

    companion object {
        private const val DESCRIPTOR = "com.tomtom.installer.SplitShizukuService"
        private const val INSTALL = IBinder.FIRST_CALL_TRANSACTION
        fun install(remote: IBinder, files: List<ParcelFileDescriptor>, sizes: LongArray, user: Int): String {
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeTypedList(files)
                data.writeLongArray(sizes)
                data.writeInt(user)
                check(remote.transact(INSTALL, data, reply, 0)) { "Service Shizuku incompatible" }
                reply.readException()
                return reply.readString() ?: "ERROR: Réponse Shizuku absente"
            } finally { data.recycle(); reply.recycle() }
        }
    }
}
