package com.tomtom.installer

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.IBinder
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.IOException

/**
 * Starts Android's real Package Installer through Shizuku rather than silently
 * committing a package-manager session. URI access is granted by MainActivity.
 */
internal object NativeInstallerLauncher {
    private const val SHELL_PACKAGE = "com.android.shell"

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    fun launchWithShizuku(intent: Intent): Int {
        val useTaskManager = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val serviceName = if (useTaskManager) "activity_task" else "activity"
        val stubName = if (useTaskManager) {
            "android.app.IActivityTaskManager\$Stub"
        } else {
            "android.app.IActivityManager\$Stub"
        }
        val service = SystemServiceHelper.getSystemService(serviceName)
            ?: throw IOException("Service Android $serviceName indisponible")
        val stub = Class.forName(stubName)
        val remote = stub.getDeclaredMethod("asInterface", IBinder::class.java)
            .invoke(null, ShizukuBinderWrapper(service))
            ?: throw IOException("Interface Android indisponible")
        val method = remote.javaClass.methods.firstOrNull {
            it.name == "startActivityAsUser" && it.parameterTypes.any { type -> type == Intent::class.java }
        } ?: throw IOException("Android ne propose pas startActivityAsUser")
        method.isAccessible = true
        val types = method.parameterTypes
        val intentIndex = types.indexOf(Intent::class.java)
        // Calling package precedes the Intent. Newer Android versions also
        // have an optional callingFeatureId: it is left null.
        val callingPackageIndex = types.indices.firstOrNull {
            it < intentIndex && types[it] == String::class.java
        } ?: throw IOException("Signature Android non reconnue")
        val userIdIndex = types.indices.lastOrNull { types[it] == Int::class.javaPrimitiveType }
            ?: throw IOException("Utilisateur Android introuvable dans la signature")
        val args = arrayOfNulls<Any>(types.size)
        types.indices.forEach { index ->
            args[index] = when {
                index == intentIndex -> intent
                index == callingPackageIndex -> SHELL_PACKAGE
                index == userIdIndex -> android.os.Process.myUid() / 100000
                types[index] == Int::class.javaPrimitiveType -> 0
                types[index] == Boolean::class.javaPrimitiveType -> false
                else -> null
            }
        }
        val code = method.invoke(remote, *args) as? Int
            ?: throw IOException("Réponse Android non reconnue")
        // 0: started, 1: intent returned, 2: task brought forward, 3: delivered.
        if (code !in 0..3) throw IOException("Programme d’installation refusé par Android: code=$code")
        return code
    }
}
