package com.tomtom.installer

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import java.io.DataOutputStream
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.content.FileProvider
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val CHANNEL = "com.tomtom.installer/install"
    private val REQUEST_INSTALL = 1001
    private var pendingResult: MethodChannel.Result? = null
    private lateinit var splitInstaller: SplitApkInstaller

    private val logFile: File by lazy {
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "installer_log.txt"
        )
    }

    private fun log(tag: String, msg: String) {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        try { FileWriter(logFile, true).use { it.write("[$ts] [$tag] $msg\n") } } catch (_: Exception) {}
        android.util.Log.d("Installer_$tag", msg)
    }

    private fun logDevice() {
        log("DEVICE", "Brand: ${Build.BRAND} | Model: ${Build.MODEL} | SDK: ${Build.VERSION.SDK_INT} | ColorOS: ${isColorOS()} | HyperOS: ${isHyperOS()} | Root: ${isRooted()}")
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        splitInstaller = SplitApkInstaller(this)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_INSTALL) {
            val result = pendingResult; pendingResult = null
            when (resultCode) {
                Activity.RESULT_OK -> { log("RESULT", "Succès"); result?.success("install_success") }
                Activity.RESULT_CANCELED -> { log("RESULT", "Annulée"); result?.success("install_cancelled") }
                Activity.RESULT_FIRST_USER -> { log("RESULT", "Échec"); result?.success("install_failed") }
                else -> { log("RESULT", "Code: $resultCode"); result?.success("install_unknown") }
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
                "installApkRoot" -> {
                    val path = call.argument<String>("path")
                    if (path != null) {
                        try { log("ROOT", "Début"); logDevice(); installRoot(path); result.success("install_success") }
                        catch (e: Exception) { log("ROOT", "ERREUR: ${e.message}"); result.error("ROOT_ERROR", e.message, null) }
                    } else result.error("INVALID_PATH", "null", null)
                }
                "installSplitApk" -> {
                    val path = call.argument<String>("path")
                    if (path != null) {
                        log("SPLIT", "Début: $path"); logDevice()
                        splitInstaller.install(path, object : SplitApkInstaller.InstallCallback {
                            override fun onSuccess() { log("SPLIT", "Session OK"); result.success("install_started") }
                            override fun onError(msg: String) {
                                if (msg.startsWith("SINGLE:")) { pendingResult = result; installApk(msg.removePrefix("SINGLE:")) }
                                else { log("SPLIT", "ERREUR: $msg"); result.error("SPLIT_ERROR", msg, null) }
                            }
                        })
                    } else result.error("INVALID_PATH", "null", null)
                }
                "isRooted" -> result.success(isRooted())
                "isColorOS" -> result.success(isColorOS())
                "isHyperOS" -> result.success(isHyperOS())
                "getLogPath" -> result.success(logFile.absolutePath)
                "clearLog" -> { try { logFile.writeText(""); result.success("ok") } catch (e: Exception) { result.error("ERR", e.message, null) } }
                else -> result.notImplemented()
            }
        }
    }

    private fun doInstall(path: String?, result: MethodChannel.Result, tag: String, action: (String) -> Unit) {
        if (path != null) {
            try { log(tag, "Début: $path"); logDevice(); pendingResult = result; action(path) }
            catch (e: Exception) { log(tag, "ERREUR: ${e.message}"); pendingResult = null; result.error("INSTALL_ERROR", e.message, null) }
        } else result.error("INVALID_PATH", "null", null)
    }

    private fun uri(f: File): Uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", f)

    private fun installApk(path: String) {
        val f = File(path); if (!f.exists()) throw IOException("Introuvable: $path")
        log("STANDARD", "${f.length()} octets")
        startActivityForResult(Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri(f); flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
            putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, "com.android.vending")
        }, REQUEST_INSTALL)
    }

    private fun installOppo(path: String) {
        val f = File(path); if (!f.exists()) throw IOException("Introuvable: $path")
        log("OPPO", "${f.length()} octets")
        packageManager.setComponentEnabledSetting(
            ComponentName(packageName, "$packageName.OppoTrick"),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP
        )
        log("OPPO", "OppoTrick activé")
        startActivityForResult(Intent(Intent.ACTION_VIEW).apply {
            setClassName(packageName, "$packageName.OppoTrick")
            setDataAndType(uri(f), "application/vnd.android.package-archive")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }, REQUEST_INSTALL)
    }

    private fun installHyperOS(path: String) {
        val f = File(path); if (!f.exists()) throw IOException("Introuvable: $path")
        log("HYPEROS", "${f.length()} octets")
        try {
            packageManager.setComponentEnabledSetting(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.install.InstallPackageActivity"),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP
            )
            log("HYPEROS", "SecurityCenter désactivé")
        } catch (e: Exception) { log("HYPEROS", "SecurityCenter non désactivable: ${e.message}") }
        startActivityForResult(Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri(f); flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
            putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, "com.android.vending")
            putExtra("miui_extra_install_enable_notification", false)
            putExtra("miui.intent.extra.INSTALLER_PACKAGE_NAME", "com.android.vending")
        }, REQUEST_INSTALL)
    }

    private fun installRoot(path: String) {
        val f = File(path); if (!f.exists()) throw IOException("Introuvable: $path")
        val p = Runtime.getRuntime().exec("su")
        DataOutputStream(p.outputStream).use { os ->
            os.writeBytes("pm install -t -i \"com.android.vending\" -r \"$path\"\n")
            os.writeBytes("exit\n"); os.flush()
        }
        val code = p.waitFor()
        val err = p.errorStream.bufferedReader().readText()
        p.destroy()
        log("ROOT", "Exit: $code${if (err.isNotEmpty()) " | $err" else ""}")
        if (code != 0 && err.isNotEmpty()) throw Exception(err)
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
