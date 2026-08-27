package com.tomtom.installer

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.content.FileProvider
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import rikka.shizuku.Shizuku

class MainActivity : FlutterActivity() {

    private val CHANNEL = "com.tomtom.installer/install"
    private val REQUEST_INSTALL = 1001
    private val SHIZUKU_CODE = 1002
    private var pendingResult: MethodChannel.Result? = null
    private var pendingShizukuResult: MethodChannel.Result? = null
    private var pendingShizukuPath: String? = null
    private lateinit var splitInstaller: SplitApkInstaller

    private val logFile: File by lazy {
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "installer_log.txt")
    }

    private fun log(tag: String, msg: String) {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        try { FileWriter(logFile, true).use { it.write("[$ts] [$tag] $msg\n") } } catch (_: Exception) {}
        android.util.Log.d("Installer_$tag", msg)
    }

    private fun logDevice() {
        log("DEVICE", "Brand:${Build.BRAND} Model:${Build.MODEL} SDK:${Build.VERSION.SDK_INT} ColorOS:${isColorOS()} HyperOS:${isHyperOS()} Root:${isRooted()} Shizuku:${isShizukuAvailable()}")
    }

    // ─── Shizuku listener ───────────────────────────────────────────────────

    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == SHIZUKU_CODE) {
            if (result == PackageManager.PERMISSION_GRANTED) {
                log("SHIZUKU", "Permission accordée")
                val path = pendingShizukuPath
                val res = pendingShizukuResult
                pendingShizukuPath = null; pendingShizukuResult = null
                if (path != null && res != null) doInstallShizuku(path, res)
            } else {
                log("SHIZUKU", "Permission refusée")
                pendingShizukuResult?.error("SHIZUKU_DENIED", "Permission Shizuku refusée", null)
                pendingShizukuPath = null; pendingShizukuResult = null
            }
        }
    }

    private fun isShizukuAvailable(): Boolean = try { Shizuku.pingBinder() } catch (_: Exception) { false }

    private fun isShizukuGranted(): Boolean = try {
        !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) { false }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        splitInstaller = SplitApkInstaller(this)
        try { Shizuku.addRequestPermissionResultListener(shizukuListener) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        try { Shizuku.removeRequestPermissionResultListener(shizukuListener) } catch (_: Exception) {}
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_INSTALL) {
            val result = pendingResult; pendingResult = null
            when (resultCode) {
                Activity.RESULT_OK -> { log("RESULT", "Succès ✓"); result?.success("install_success") }
                Activity.RESULT_CANCELED -> { log("RESULT", "Annulée"); result?.success("install_cancelled") }
                Activity.RESULT_FIRST_USER -> { log("RESULT", "Échec"); result?.success("install_failed") }
                else -> { log("RESULT", "Code:$resultCode"); result?.success("install_unknown") }
            }
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "installApk" -> doInstall(call.argument("path"), result, "STANDARD") { installApk(it) }
                "installApkOppo" -> doInstall(call.argument("path"), result, "OPPO") { installOppo(it) }
                "installApkHyperOS" -> doInstall(call.argument("path"), result, "HYPEROS") { installHyperOS(it) }
                "installApkShizuku" -> {
                    val path = call.argument<String>("path")
                    if (path != null) {
                        log("SHIZUKU", "Début: $path"); logDevice()
                        if (!isShizukuAvailable()) {
                            result.error("SHIZUKU_UNAVAILABLE", "Shizuku non disponible", null)
                            return@setMethodCallHandler
                        }
                        if (!isShizukuGranted()) {
                            pendingShizukuPath = path; pendingShizukuResult = result
                            try { Shizuku.requestPermission(SHIZUKU_CODE) }
                            catch (e: Exception) { result.error("SHIZUKU_ERROR", e.message, null) }
                        } else { doInstallShizuku(path, result) }
                    } else result.error("INVALID_PATH", "null", null)
                }
                "installApkRoot" -> {
                    val path = call.argument<String>("path")
                    if (path != null) {
                        try { log("ROOT", "Début"); logDevice(); installRoot(path); result.success("install_success") }
                        catch (e: Exception) { log("ROOT", "ERREUR:${e.message}"); result.error("ROOT_ERROR", e.message, null) }
                    } else result.error("INVALID_PATH", "null", null)
                }
                "installSplitApk" -> {
                    val path = call.argument<String>("path")
                    if (path != null) {
                        log("SPLIT", "Début: $path"); logDevice()
                        splitInstaller.install(path, object : SplitApkInstaller.InstallCallback {
                            override fun onSuccess() { log("SPLIT", "OK"); result.success("install_started") }
                            override fun onError(msg: String) {
                                if (msg.startsWith("SINGLE:")) { pendingResult = result; installApk(msg.removePrefix("SINGLE:")) }
                                else { log("SPLIT", "ERREUR:$msg"); result.error("SPLIT_ERROR", msg, null) }
                            }
                        })
                    } else result.error("INVALID_PATH", "null", null)
                }
                "isRooted" -> result.success(isRooted())
                "isColorOS" -> result.success(isColorOS())
                "isHyperOS" -> result.success(isHyperOS())
                "isShizukuAvailable" -> result.success(isShizukuAvailable())
                "isShizukuGranted" -> result.success(isShizukuGranted())
                "getLogPath" -> result.success(logFile.absolutePath)
                "clearLog" -> { try { logFile.writeText(""); result.success("ok") } catch (e: Exception) { result.error("ERR", e.message, null) } }
                else -> result.notImplemented()
            }
        }
    }

    private fun doInstall(path: String?, result: MethodChannel.Result, tag: String, action: (String) -> Unit) {
        if (path != null) {
            try { log(tag, "Début:$path"); logDevice(); pendingResult = result; action(path) }
            catch (e: Exception) { log(tag, "ERREUR:${e.message}"); pendingResult = null; result.error("INSTALL_ERROR", e.message, null) }
        } else result.error("INVALID_PATH", "null", null)
    }

    private fun uri(f: File): Uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", f)

    private fun installApk(path: String) {
        val f = File(path); if (!f.exists()) throw IOException("Introuvable:$path")
        log("STANDARD", "${f.length()} octets")
        startActivityForResult(Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri(f); flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
            putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, "com.android.vending")
        }, REQUEST_INSTALL)
    }

    private fun installOppo(path: String) {
        val f = File(path); if (!f.exists()) throw IOException("Introuvable:$path")
        log("OPPO", "${f.length()} octets")
        packageManager.setComponentEnabledSetting(
            ComponentName(packageName, "$packageName.OppoTrick"),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP
        )
        startActivityForResult(Intent(Intent.ACTION_VIEW).apply {
            setClassName(packageName, "$packageName.OppoTrick")
            setDataAndType(uri(f), "application/vnd.android.package-archive")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }, REQUEST_INSTALL)
    }

    private fun installHyperOS(path: String) {
        val f = File(path); if (!f.exists()) throw IOException("Introuvable:$path")
        try {
            packageManager.setComponentEnabledSetting(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.install.InstallPackageActivity"),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP
            )
            log("HYPEROS", "SecurityCenter désactivé")
        } catch (e: Exception) { log("HYPEROS", "SecurityCenter non désactivable:${e.message}") }
        startActivityForResult(Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri(f); flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
            putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, "com.android.vending")
            putExtra("miui_extra_install_enable_notification", false)
            putExtra("miui.intent.extra.INSTALLER_PACKAGE_NAME", "com.android.vending")
        }, REQUEST_INSTALL)
    }

    private fun doInstallShizuku(path: String, result: MethodChannel.Result) {
        try {
            val f = File(path)
            if (!f.exists()) throw IOException("Introuvable:$path")

            val archiveInfo = packageManager.getPackageArchiveInfo(path, PackageManager.GET_META_DATA)
            val packageName = archiveInfo?.packageName
                ?: throw IOException("Impossible de lire le nom du package de l'APK")

            log("SHIZUKU", "Lancement du Package Installer via Shizuku: $packageName (${f.length()} octets)")

            // Donner explicitement l'accès au content:// URI aux Package Installers système.
            val fileUri = uri(f)
            val targets = listOf(
                "com.android.shell",
                "com.google.android.packageinstaller",
                "com.android.packageinstaller"
            )
            targets.forEach { pkg ->
                try { grantUriPermission(pkg, fileUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                catch (_: Exception) {}
            }

            pendingShizukuResult = result

            // IMPORTANT : on ne fait pas pm install directement.
            // On ouvre le Package Installer système afin que l'installation soit
            // réellement initiée par celui-ci, puis on force ensuite l'installer
            // de record à com.android.vending avec cmd package set-installer.
            val uriString = fileUri.toString().replace("'", "\\'")
            val command = "am start -a android.intent.action.INSTALL_PACKAGE " +
                "-d '$uriString' " +
                "-t 'application/vnd.android.package-archive' " +
                "-f 0x00000001 " +
                "--es android.intent.extra.INSTALLER_PACKAGE_NAME 'com.android.vending' " +
                "--es android.intent.extra.REFERRER_NAME 'android-app://com.android.vending' " +
                "--ez android.intent.extra.NOT_UNKNOWN_SOURCE true"

            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exit = process.waitFor()
            process.destroy()
            log("SHIZUKU", "am start exit=$exit stdout=$stdout stderr=$stderr")

            if (exit != 0) {
                pendingShizukuResult = null
                throw IOException(stderr.ifEmpty { stdout.ifEmpty { "am start a échoué ($exit)" } })
            }

            // L'interface d'installation est maintenant gérée par le Package Installer.
            // On surveille ensuite l'application pour appliquer la correction installer-of-record
            // dès que l'installation est effectivement terminée.
            result.success("install_started")
            pendingShizukuResult = null
            watchAndFixInstaller(packageName)
        } catch (e: Exception) {
            pendingShizukuResult = null
            log("SHIZUKU", "ERREUR:${e.message}")
            result.error("SHIZUKU_ERROR", e.message, null)
        }
    }

    private fun watchAndFixInstaller(packageName: String) {
        Thread {
            try {
                // Le Package Installer affiche une confirmation utilisateur.
                // On attend jusqu'à 90 secondes que le package soit effectivement présent
                // avant d'appliquer la correction privilégiée.
                repeat(90) {
                    Thread.sleep(1000)
                    try {
                        packageManager.getPackageInfo(packageName, 0)
                        setInstallerViaShizuku(packageName)
                        return@Thread
                    } catch (_: PackageManager.NameNotFoundException) {
                        // Pas encore installé : on continue d'attendre.
                    }
                }
                log("SHIZUKU", "Timeout: installation de $packageName non détectée")
            } catch (e: Exception) {
                log("SHIZUKU", "Surveillance installation ERREUR:${e.message}")
            }
        }.start()
    }

    private fun setInstallerViaShizuku(packageName: String) {
        try {
            val command = "cmd package set-installer $packageName com.android.vending"
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exit = process.waitFor()
            process.destroy()
            log("SHIZUKU", "set-installer $packageName -> com.android.vending exit=$exit stdout=$stdout stderr=$stderr")
        } catch (e: Exception) {
            log("SHIZUKU", "set-installer ERREUR:${e.message}")
        }
    }

    private fun installRoot(path: String) {
        val f = File(path); if (!f.exists()) throw IOException("Introuvable:$path")
        val p = Runtime.getRuntime().exec("su")
        DataOutputStream(p.outputStream).use { os ->
            os.writeBytes("pm install -t -i com.android.vending -r \"$path\"\n")
            os.writeBytes("exit\n"); os.flush()
        }
        val code = p.waitFor()
        val err = p.errorStream.bufferedReader().readText()
        p.destroy()
        log("ROOT", "Exit:$code${if (err.isNotEmpty()) " err:$err" else ""}")
        if (code != 0 && err.isNotEmpty()) throw Exception(err)

        val archiveInfo = packageManager.getPackageArchiveInfo(path, PackageManager.GET_META_DATA)
        archiveInfo?.packageName?.let { pkg ->
            try {
                val fix = Runtime.getRuntime().exec(arrayOf("su", "-c", "cmd package set-installer $pkg com.android.vending"))
                val fixErr = fix.errorStream.bufferedReader().readText()
                val fixCode = fix.waitFor()
                log("ROOT", "set-installer $pkg -> com.android.vending exit=$fixCode${if (fixErr.isNotEmpty()) " err:$fixErr" else ""}")
            } catch (e: Exception) { log("ROOT", "set-installer ERREUR:${e.message}") }
        }
    }

    private fun isRooted(): Boolean {
        val su = arrayOf("/sbin/su", "/system/bin/su", "/system/xbin/su", "/data/local/bin/su")
        return su.any { File(it).exists() } || try {
            Runtime.getRuntime().exec(arrayOf("/system/xbin/which", "su")).inputStream.bufferedReader().readLine() != null
        } catch (_: Exception) { false }
    }

    private fun isColorOS(): Boolean {
        val m = Build.MANUFACTURER.lowercase(); val b = Build.BRAND.lowercase()
        return m.contains("oppo") || m.contains("realme") || b.contains("oppo") ||
               b.contains("realme") || b.contains("oneplus") ||
               System.getProperty("ro.build.version.opporom") != null
    }

    private fun isHyperOS(): Boolean {
        val m = Build.MANUFACTURER.lowercase(); val b = Build.BRAND.lowercase()
        return m.contains("xiaomi") || b.contains("xiaomi") || b.contains("redmi") ||
               b.contains("poco") || System.getProperty("ro.miui.ui.version.name") != null
    }
}
