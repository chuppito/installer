package com.tomtom.installer

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.os.Handler
import android.os.Looper
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
import rikka.shizuku.Shizuku

class MainActivity : FlutterActivity() {

    private val CHANNEL = "com.tomtom.installer/install"
    private val REQUEST_INSTALL = 1001
    private val SHIZUKU_CODE = 1002
    private val VENDING = "com.android.vending"
    private val VERIFY_DELAY_MS = 900L

    private var pendingResult: MethodChannel.Result? = null
    private var pendingShizukuResult: MethodChannel.Result? = null
    private var pendingShizukuPath: String? = null
    private lateinit var splitInstaller: SplitApkInstaller
    private val handler = Handler(Looper.getMainLooper())
    private val logFile: File by lazy {
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "installer_log.txt")
    }

    private fun log(tag: String, msg: String) {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        try { FileWriter(logFile, true).use { it.write("[$ts] [$tag] $msg\n") } } catch (_: Exception) {}
        android.util.Log.d("Installer_$tag", msg)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Installation attribution / verification
    // ─────────────────────────────────────────────────────────────────────────
    private val packageAddedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_PACKAGE_ADDED && intent.action != Intent.ACTION_PACKAGE_REPLACED) return
            val packageName = intent.data?.schemeSpecificPart ?: return
            if (packageName == this@MainActivity.packageName) return

            log("VERIFY", "Événement installation: $packageName")
            handler.postDelayed({
                verifyAndCorrectAttribution(packageName, "POST_INSTALL")
            }, VERIFY_DELAY_MS)
        }
    }

    private fun getPackageNameFromApk(path: String): String? {
        return try {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(path, 0)?.packageName
        } catch (_: Exception) { null }
    }

    /**
     * Reads Android's real InstallSourceInfo. Installing and initiating are
     * deliberately logged separately because Android can change the former
     * without changing the latter.
     */
    private fun queryInstallSource(packageName: String): Map<String, Any?>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val info = packageManager.getInstallSourceInfo(packageName)
            val source = info.packageSource
            mapOf(
                "packageName" to packageName,
                "installingPackageName" to info.installingPackageName,
                "initiatingPackageName" to info.initiatingPackageName,
                "originatingPackageName" to info.originatingPackageName,
                "packageSource" to source,
                "isPlayStore" to (info.installingPackageName == VENDING),
                "isInitiatedByPlayStore" to (info.initiatingPackageName == VENDING)
            )
        } catch (e: Exception) {
            log("VERIFY", "getInstallSourceInfo($packageName) échoué: ${e.message}")
            null
        }
    }

    private fun logInstallSource(packageName: String, tag: String): Map<String, Any?>? {
        val info = queryInstallSource(packageName) ?: return null
        log(
            tag,
            "InstallSourceInfo package=$packageName installing=${info["installingPackageName"]} " +
                "initiating=${info["initiatingPackageName"]} originating=${info["originatingPackageName"]} " +
                "source=${info["packageSource"]} playStore=${info["isPlayStore"]}"
        )
        return info
    }

    /**
     * Root can always use the package-manager command directly. If the OEM
     * ignored the installer hint, we correct the installer-of-record and
     * immediately verify again.
     */
    private fun verifyAndCorrectAttribution(packageName: String, tag: String, callback: ((Map<String, Any?>?) -> Unit)? = null) {
        Thread {
            try {
                var info = logInstallSource(packageName, tag)
                if (info?.get("installingPackageName") != VENDING && isRooted()) {
                    log("VERIFY", "Installer != Play Store -> correction root pour $packageName")
                    val output = runRootCommand("cmd package set-installer $packageName $VENDING")
                    log("ROOT", "set-installer output=$output")
                    Thread.sleep(250)
                    info = logInstallSource(packageName, "VERIFY_AFTER_ROOT_FIX")
                }
                runOnUiThread { callback?.invoke(info) }
            } catch (e: Exception) {
                log("VERIFY", "Erreur correction $packageName: ${e.message}")
                runOnUiThread { callback?.invoke(null) }
            }
        }.start()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shizuku: public newProcess API from Shizuku 12.2.0.
    // We deliberately keep this path simple: execute pm directly and return
    // the real shell output instead of binding a custom UserService.
    // ─────────────────────────────────────────────────────────────────────────
    private fun isShizukuAvailable(): Boolean = try { Shizuku.pingBinder() } catch (_: Exception) { false }

    private fun isShizukuGranted(): Boolean = try {
        !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) { false }

    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code != SHIZUKU_CODE) return@OnRequestPermissionResultListener
        val path = pendingShizukuPath
        val res = pendingShizukuResult
        pendingShizukuPath = null
        pendingShizukuResult = null
        if (result == PackageManager.PERMISSION_GRANTED && path != null && res != null) {
            doInstallShizuku(path, res)
        } else if (res != null) {
            res.error("SHIZUKU_DENIED", "Permission Shizuku refusée", null)
        }
    }

    private fun shizukuExec(command: String): String {
        val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val code = process.waitFor()
        return "exit=$code\n" + (stdout.ifEmpty { stderr }).trim()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        splitInstaller = SplitApkInstaller(this)
        try { Shizuku.addRequestPermissionResultListener(shizukuListener) } catch (_: Exception) {}

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        registerReceiver(packageAddedReceiver, filter)

    }

    override fun onDestroy() {
        super.onDestroy()
        try { Shizuku.removeRequestPermissionResultListener(shizukuListener) } catch (_: Exception) {}
        try { unregisterReceiver(packageAddedReceiver) } catch (_: Exception) {}
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_INSTALL) {
            val result = pendingResult
            pendingResult = null
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
                    if (path == null) {
                        result.error("INVALID_PATH", "null", null)
                    } else if (!isShizukuAvailable()) {
                        result.error("SHIZUKU_UNAVAILABLE", "Shizuku non disponible", null)
                    } else if (!isShizukuGranted()) {
                        pendingShizukuPath = path
                        pendingShizukuResult = result
                        try { Shizuku.requestPermission(SHIZUKU_CODE) }
                        catch (e: Exception) { result.error("SHIZUKU_ERROR", e.message, null) }
                    } else {
                        doInstallShizuku(path, result)
                    }
                }
                "installApkRoot" -> {
                    val path = call.argument<String>("path")
                    if (path == null) {
                        result.error("INVALID_PATH", "null", null)
                    } else {
                        try {
                            log("ROOT", "Début: $path")
                            installRoot(path)
                            val pkg = getPackageNameFromApk(path)
                            if (pkg != null) {
                                verifyAndCorrectAttribution(pkg, "ROOT_INSTALL") { info ->
                                    result.success(info ?: "install_success")
                                }
                            } else result.success("install_success")
                        } catch (e: Exception) {
                            log("ROOT", "ERREUR: ${e.message}")
                            result.error("ROOT_ERROR", e.message, null)
                        }
                    }
                }
                "installSplitApk" -> {
                    val path = call.argument<String>("path")
                    if (path != null) {
                        log("SPLIT", "Début: $path")
                        splitInstaller.install(path, object : SplitApkInstaller.InstallCallback {
                            override fun onSuccess() { log("SPLIT", "OK"); result.success("install_started") }
                            override fun onError(msg: String) {
                                if (msg.startsWith("SINGLE:")) { pendingResult = result; installApk(msg.removePrefix("SINGLE:")) }
                                else { log("SPLIT", "ERREUR:$msg"); result.error("SPLIT_ERROR", msg, null) }
                            }
                        })
                    } else result.error("INVALID_PATH", "null", null)
                }
                "getInstallSourceInfo" -> {
                    val pkg = call.argument<String>("packageName")
                    if (pkg == null) result.error("INVALID_PACKAGE", "packageName null", null)
                    else result.success(queryInstallSource(pkg))
                }
                "verifyInstallSource" -> {
                    val pkg = call.argument<String>("packageName")
                    if (pkg == null) result.error("INVALID_PACKAGE", "packageName null", null)
                    else verifyAndCorrectAttribution(pkg, "MANUAL_VERIFY") { result.success(it) }
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
            try { log(tag, "Début:$path"); pendingResult = result; action(path) }
            catch (e: Exception) { log(tag, "ERREUR:${e.message}"); pendingResult = null; result.error("INSTALL_ERROR", e.message, null) }
        } else result.error("INVALID_PATH", "null", null)
    }

    private fun uri(f: File): Uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", f)

    // KingInstaller-compatible standard method. Keep this path as close as
    // possible to the proven KingInstaller flow.
    private fun buildKingIntent(apkUri: Uri): Intent {
        return Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = apkUri
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
            putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, VENDING)
        }
    }

    private fun installApk(path: String) {
        val f = File(path)
        if (!f.exists()) throw IOException("Introuvable:$path")
        log("STANDARD", "${f.length()} octets")
        startActivityForResult(buildKingIntent(uri(f)), REQUEST_INSTALL)
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
        startActivityForResult(buildKingIntent(uri(f)).also {
            it.putExtra("miui_extra_install_enable_notification", false)
            it.putExtra("miui.intent.extra.INSTALLER_PACKAGE_NAME", VENDING)
        }, REQUEST_INSTALL)
    }

    // Shizuku installs directly through pm. This avoids the crashing custom
    // UserService and gives us the shell's real exit code/output.
    private fun doInstallShizuku(path: String, result: MethodChannel.Result) {
        Thread {
            try {
                val f = File(path)
                if (!f.exists()) throw IOException("Introuvable:$path")
                log("SHIZUKU", "Installation directe: $path")
                val output = shizukuExec("pm install -t -i $VENDING -r ${shellQuote(path)}")
                log("SHIZUKU", "pm install -> $output")
                if (!output.contains("Success", ignoreCase = true)) {
                    throw IOException(output)
                }
                val pkg = getPackageNameFromApk(path)
                if (pkg != null) {
                    verifyAndCorrectAttribution(pkg, "SHIZUKU_INSTALL") { info ->
                        result.success(info ?: "install_success")
                    }
                } else {
                    result.success("install_success")
                }
            } catch (e: Exception) {
                log("SHIZUKU", "ERREUR: ${e.message}")
                runOnUiThread { result.error("SHIZUKU_ERROR", e.message ?: "Erreur Shizuku", null) }
            }
        }.start()
    }

    private fun runRootCommand(command: String): String {
        val p = Runtime.getRuntime().exec("su")
        DataOutputStream(p.outputStream).use { os ->
            os.writeBytes(command + "\n")
            os.writeBytes("exit\n")
            os.flush()
        }
        val stdout = p.inputStream.bufferedReader().readText()
        val stderr = p.errorStream.bufferedReader().readText()
        val code = p.waitFor()
        p.destroy()
        if (code != 0) throw Exception("exit=$code ${stderr.ifEmpty { stdout }}")
        return stdout.ifEmpty { stderr }.trim()
    }

    private fun installRoot(path: String) {
        val f = File(path); if (!f.exists()) throw IOException("Introuvable:$path")
        val output = runRootCommand("pm install -t -i $VENDING -r ${shellQuote(path)}")
        log("ROOT", "pm install -> $output")
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

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
