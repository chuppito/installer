package com.tomtom.installer

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileWriter
import java.io.IOException
import java.io.InputStreamReader
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

    // ─── BroadcastReceiver : après installation, force com.android.vending ──
    // Inspiré de KingInstaller 1.7 : cmd package set-installer <pkg> com.android.vending
    private val packageAddedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_PACKAGE_ADDED || intent.action == Intent.ACTION_PACKAGE_REPLACED) {
                val packageName = intent.data?.schemeSpecificPart ?: return
                log("POST_INSTALL", "Package installé: $packageName — forçage source Play Store")
                // Via Shizuku si disponible
                if (isShizukuAvailable() && isShizukuGranted()) {
                    forceInstallerViaShizuku(packageName)
                }
                // Via root si disponible
                else if (isRooted()) {
                    forceInstallerViaRoot(packageName)
                }
            }
        }
    }

    private fun forceInstallerViaShizuku(packageName: String) {
        // Shizuku.newProcess est privé dans l'API publique
        // On utilise root comme fallback pour set-installer
        forceInstallerViaRoot(packageName)
    }

    private fun forceInstallerViaRoot(packageName: String) {
        Thread {
            try {
                val process = Runtime.getRuntime().exec("su")
                val os = DataOutputStream(process.outputStream)
                os.writeBytes("cmd package set-installer $packageName $VENDING\n")
                os.writeBytes("exit\n")
                os.flush()
                process.waitFor()
                process.destroy()
                log("POST_INSTALL", "Source forcée via root pour $packageName")
            } catch (e: Exception) {
                log("POST_INSTALL", "Root set-installer échoué: ${e.message}")
            }
        }.start()
    }

    // ─── Shizuku ────────────────────────────────────────────────────────────

    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == SHIZUKU_CODE) {
            if (result == PackageManager.PERMISSION_GRANTED) {
                log("SHIZUKU", "Permission accordée")
                val path = pendingShizukuPath; val res = pendingShizukuResult
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

        // Écoute les installations pour forcer la source Play Store après coup
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
                        log("SHIZUKU", "Début: $path")
                        if (!isShizukuAvailable()) {
                            result.error("SHIZUKU_UNAVAILABLE", "Shizuku non disponible", null); return@setMethodCallHandler
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
                        try { log("ROOT", "Début"); installRoot(path); result.success("install_success") }
                        catch (e: Exception) { log("ROOT", "ERREUR:${e.message}"); result.error("ROOT_ERROR", e.message, null) }
                    } else result.error("INVALID_PATH", "null", null)
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

    // ─── Méthode King (standard + tous les extras) ───────────────────────────
    private fun buildKingIntent(apkUri: Uri): Intent {
        return Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = apkUri
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
            putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, VENDING)
            putExtra("installerPackageName", VENDING)
            putExtra("android.content.pm.extra.VERIFICATION_INSTALLER_PACKAGE", VENDING)
            putExtra("android.content.pm.extra.VERIFICATION_INSTALLER_UID", 0)
            putExtra("android.intent.extra.INSTALL_REASON", 1)
            putExtra("android.intent.extra.REFERRER_NAME", "android-app://$VENDING")
            putExtra(Intent.EXTRA_REFERRER, Uri.parse("android-app://$VENDING"))
            putExtra("android.intent.extra.ORIGINATING_PACKAGE", VENDING)
            if (Build.VERSION.SDK_INT >= 34) {
                putExtra("android.content.pm.extra.REQUEST_UPDATE_OWNERSHIP", true)
            }
        }
    }

    private fun installApk(path: String) {
        val f = File(path); if (!f.exists()) throw IOException("Introuvable:$path")
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

    // ─── Méthode Shizuku — am start via shell (méthode KingInstaller 1.7) ───
    // Utilise `am start` en shell Shizuku pour lancer l'installeur avec
    // tous les extras Play Store, bypass total du UID check
    private fun doInstallShizuku(path: String, result: MethodChannel.Result) {
        Thread {
            try {
                val f = File(path); if (!f.exists()) throw IOException("Introuvable:$path")
                log("SHIZUKU", "Installation via am start shell: ${f.length()} octets")

                val fileUri = uri(f)

                // Accorde les permissions URI à la shell et à l'installeur système
                val targets = listOf("com.android.shell", "com.google.android.packageinstaller", "com.android.packageinstaller")
                targets.forEach { pkg ->
                    try { grantUriPermission(pkg, fileUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                }

                // Utilise PackageInstaller avec setInstallerPackageName
                // Shizuku donne les droits élevés pour bypasser la restriction UID
                val pi = packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setInstallerPackageName(VENDING)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
                }
                val sessionId = pi.createSession(params)
                val session = pi.openSession(sessionId)
                try {
                    session.openWrite("package", 0, f.length()).use { output ->
                        FileInputStream(f).use { input ->
                            val buffer = ByteArray(65536); var n: Int
                            while (input.read(buffer).also { n = it } != -1) output.write(buffer, 0, n)
                            session.fsync(output)
                        }
                    }
                    val intent = Intent(this@MainActivity, MainActivity::class.java).apply {
                        action = "com.tomtom.installer.SHIZUKU_COMPLETE"
                    }
                    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
                    else android.app.PendingIntent.FLAG_UPDATE_CURRENT
                    val pi2 = android.app.PendingIntent.getActivity(this@MainActivity, sessionId, intent, flags)
                    session.commit(pi2.intentSender)
                    log("SHIZUKU", "Session PackageInstaller commitée")
                    runOnUiThread { result.success("install_started") }
                } catch (e2: Exception) {
                    session.abandon()
                    throw e2
                } finally {
                    session.close()
                }
            } catch (e: Exception) {
                log("SHIZUKU", "ERREUR:${e.message}")
                runOnUiThread { result.error("SHIZUKU_ERROR", e.message, null) }
            }
        }.start()
    }

    private fun installRoot(path: String) {
        val f = File(path); if (!f.exists()) throw IOException("Introuvable:$path")
        val p = Runtime.getRuntime().exec("su")
        DataOutputStream(p.outputStream).use { os ->
            os.writeBytes("pm install -t -i $VENDING -r \"$path\"\n")
            os.writeBytes("exit\n"); os.flush()
        }
        val code = p.waitFor()
        val err = p.errorStream.bufferedReader().readText()
        p.destroy()
        log("ROOT", "Exit:$code${if (err.isNotEmpty()) " err:$err" else ""}")
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
