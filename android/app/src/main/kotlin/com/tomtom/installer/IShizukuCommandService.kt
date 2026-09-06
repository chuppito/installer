package com.tomtom.installer

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException

/**
 * Small Binder interface used by the Shizuku UserService.
 *
 * This is intentionally written in Kotlin instead of relying on Gradle's AIDL
 * source generation. That keeps the project compatible with the Flutter/AGP
 * setup used by Installer while preserving the normal AIDL/Binder contract.
 */
interface IShizukuCommandService : IInterface {

    @Throws(RemoteException::class)
    fun execute(command: String): String

    @Throws(RemoteException::class)
    fun destroy()

    abstract class Stub : Binder(), IShizukuCommandService {
        companion object {
            private const val DESCRIPTOR = "com.tomtom.installer.IShizukuCommandService"
            private const val TRANSACTION_EXECUTE = IBinder.FIRST_CALL_TRANSACTION + 0
            private const val TRANSACTION_DESTROY = 0x00FFFFFE

            fun asInterface(obj: IBinder?): IShizukuCommandService? {
                if (obj == null) return null
                val local = obj.queryLocalInterface(DESCRIPTOR)
                if (local is IShizukuCommandService) return local
                return Proxy(obj)
            }
        }

        init {
            attachInterface(this, DESCRIPTOR)
        }

        override fun asBinder(): IBinder = this

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return when (code) {
                INTERFACE_TRANSACTION -> {
                    reply?.writeString(DESCRIPTOR)
                    true
                }
                TRANSACTION_EXECUTE -> {
                    data.enforceInterface(DESCRIPTOR)
                    val command = data.readString() ?: ""
                    val result = execute(command)
                    reply?.writeNoException()
                    reply?.writeString(result)
                    true
                }
                TRANSACTION_DESTROY -> {
                    data.enforceInterface(DESCRIPTOR)
                    destroy()
                    reply?.writeNoException()
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
        }

        private class Proxy(private val remote: IBinder) : IShizukuCommandService {
            override fun asBinder(): IBinder = remote

            override fun execute(command: String): String {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                return try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeString(command)
                    remote.transact(TRANSACTION_EXECUTE, data, reply, 0)
                    reply.readException()
                    reply.readString() ?: ""
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            }

            override fun destroy() {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    remote.transact(TRANSACTION_DESTROY, data, reply, 0)
                    reply.readException()
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            }
        }
    }
}
