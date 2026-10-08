package com.tomtom.installer

import android.app.AlertDialog
import android.content.pm.PackageInstaller
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
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
    private val SHIZUKU_CODE = 1002
    private val VENDING = "com.android.vending"
    private val VERIFY_DELAY_MS = 900L

    private var watchedPackage: String? = null
    private var privilegedInstallRunning = false
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
            if (packageName != watchedPackage) return

            log("VERIFY", "Événement installation: $packageName")
            handler.postDelayed({
                verifyAttribution(packageName, "POST_INSTALL")
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
            val source = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) info.packageSource else null
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

    // Verification is read-only: missing metadata is not evidence of a wrong
    // installer. Android can reject set-installer even for UID 0.
    private fun verifyAttribution(packageName: String, tag: String, callback: ((Map<String, Any?>?) -> Unit)? = null) {
        Thread {
            val info = logInstallSource(packageName, tag)
            runOnUiThread { callback?.invoke(info) }
        }.start()
    }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        splitInstaller = SplitApkInstaller(this)
        try { Shizuku.addRequestPermissionResultListener(shizukuListener) } catch (_: Exception) {}

        handleSplitResult(intent)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageAddedReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(packageAddedReceiver, filter)
        }

    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSplitResult(intent)
    }

    private fun handleSplitResult(intent: Intent?) {
        if (intent?.action != "SPLIT_DONE") return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirmation != null) startActivity(confirmation)
            else log("SPLIT", "Confirmation Android absente")
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            val installedPackage = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
            installedPackage?.let { verifyAttribution(it, "SPLIT_INSTALL") }
            val launch = installedPackage?.let { packageManager.getLaunchIntentForPackage(it) }
            AlertDialog.Builder(this).apply {
                setTitle("Application installée")
                setMessage("L’installation est terminée.")
                setNegativeButton("OK") { _, _ -> }
                if (launch != null) setPositiveButton("Ouvrir") { _, _ -> startActivity(launch) }
            }.show()
        } else {
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Installation échouée"
            log("SPLIT", "Échec: $message")
            AlertDialog.Builder(this).setTitle("Installation interrompue")
                .setMessage(message).setPositiveButton("OK") { _, _ -> }.show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { Shizuku.removeRequestPermissionResultListener(shizukuListener) } catch (_: Exception) {}
        try { unregisterReceiver(packageAddedReceiver) } catch (_: Exception) {}
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "installApk" -> doInstall(call.argument("path"), result)
                "installApkShizuku" -> {
                    val path = call.argument<String>("path")
                    if (path == null) {
                        result.error("INVALID_PATH", "null", null)
                    } else if (installationBusy()) {
                        result.error("INSTALL_BUSY", "Une installation est déjà en cours", null)
                    } else if (!isShizukuAvailable()) {
                        result.error("SHIZUKU_UNAVAILABLE", "Shizuku non disponible", null)
                    } else if (!isShizukuGranted()) {
                        pendingShizukuPath = path
                        pendingShizukuResult = result
                        try { Shizuku.requestPermission(SHIZUKU_CODE) }
                        catch (e: Exception) {
                            pendingShizukuPath = null
                            pendingShizukuResult = null
                            result.error("SHIZUKU_ERROR", e.message, null)
                        }
                    } else {
                        doInstallShizuku(path, result)
                    }
                }
                "installSplitApk" -> {
                    val path = call.argument<String>("path")
                    if (installationBusy()) {
                        result.error("INSTALL_BUSY", "Une installation est déjà en cours", null)
                    } else if (path != null) {
                        log("SPLIT", "Début: $path")
                        splitInstaller.install(path, object : SplitApkInstaller.InstallCallback {
                            override fun onSuccess() { log("SPLIT", "OK"); result.success("install_started") }
                            override fun onError(msg: String) {
                                if (msg.startsWith("SINGLE:")) {
                                    doInstall(msg.removePrefix("SINGLE:"), result)
                                }
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
                    else verifyAttribution(pkg, "MANUAL_VERIFY") { result.success(it) }
                }
                "isShizukuAvailable" -> result.success(isShizukuAvailable())
                "isShizukuGranted" -> result.success(isShizukuGranted())
                "getLogPath" -> result.success(logFile.absolutePath)
                "clearLog" -> { try { logFile.writeText(""); result.success("ok") } catch (e: Exception) { result.error("ERR", e.message, null) } }
                else -> result.notImplemented()
            }
        }
    }

    private fun doInstall(path: String?, result: MethodChannel.Result) {
        if (installationBusy()) {
            result.error("INSTALL_BUSY", "Une installation est déjà en cours", null)
            return
        }
        if (path == null) {
            result.error("INVALID_PATH", "null", null)
            return
        }
        try {
            watchedPackage = getPackageNameFromApk(path)
            log("STANDARD", "Début: $path")
            installApk(path)
            // Do not request an activity result: Android keeps its final OK / Open dialog.
            result.success("native_installer_opened")
        } catch (e: Exception) {
            log("STANDARD", "ERREUR: ${e.message}")
            result.error("INSTALL_ERROR", e.message, null)
        }
    }

    private fun uri(f: File): Uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", f)

    // KingInstaller-compatible standard method. Keep this path as close as
    // possible to the proven KingInstaller flow.
    private fun buildKingIntent(apkUri: Uri): Intent {
        return Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, VENDING)
            putExtra(Intent.EXTRA_REFERRER, Uri.parse("android-app://$VENDING"))
            putExtra("android.intent.extra.REFERRER_NAME", "android-app://$VENDING")
        }
    }

    private fun installApk(path: String) {
        val f = File(path)
        if (!f.exists()) throw IOException("Introuvable:$path")
        log("STANDARD", "${f.length()} octets")
        startActivity(buildKingIntent(uri(f)))
    }

    private fun installationBusy(): Boolean = privilegedInstallRunning ||
        pendingShizukuResult != null

    private fun doInstallShizuku(path: String, result: MethodChannel.Result) {
        openWithShizuku(path, result)
    }

    private fun openWithShizuku(path: String, result: MethodChannel.Result) {
        val tag = "SHIZUKU"
        if (installationBusy()) {
            result.error("INSTALL_BUSY", "Une installation est déjà en cours", null)
            return
        }
        val file = File(path)
        if (!file.isFile || file.length() <= 0L) {
            result.error("INVALID_APK", "APK introuvable ou vide", null)
            return
        }
        val targetPackage = getPackageNameFromApk(path)
        if (targetPackage == null) {
            result.error("INVALID_APK", "Cet APK ne peut pas être lu par Android", null)
            return
        }
        privilegedInstallRunning = true
        watchedPackage = targetPackage
        Thread {
            try {
                val fileUri = uri(file)
                grantInstallerAccess(fileUri)
                log(tag, "Ouverture du programme d’installation Android pour $targetPackage")
                val intent = buildKingIntent(fileUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("android.content.pm.extra.INSTALL_REASON", 1)
                }
                val code = NativeInstallerLauncher.launchWithShizuku(intent)
                log(tag, "startActivityAsUser via Shizuku -> code=$code")
                runOnUiThread {
                    privilegedInstallRunning = false
                    // Opening the installer is not confirmation of installation.
                    result.success("native_installer_opened")
                }
            } catch (e: Exception) {
                val message = e.cause?.message ?: e.message ?: "Ouverture impossible"
                log(tag, "ERREUR: ${e.javaClass.simpleName}: $message")
                runOnUiThread {
                    privilegedInstallRunning = false
                    result.error("${tag}_ERROR", message, null)
                }
            }
        }.start()
    }

    private fun grantInstallerAccess(fileUri: Uri) {
        val packages = mutableSetOf(
            "com.android.shell", "com.google.android.packageinstaller", "com.android.packageinstaller"
        )
        @Suppress("DEPRECATION")
        val handlers = packageManager.queryIntentActivities(buildKingIntent(fileUri), PackageManager.MATCH_DEFAULT_ONLY)
        handlers.forEach { packages.add(it.activityInfo.packageName) }
        packages.forEach { target ->
            try {
                grantUriPermission(target, fileUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                log("URI", "Lecture de l’APK autorisée pour $target")
            } catch (e: Exception) {
                if (target == "com.android.shell") throw e
                log("URI", "$target: ${e.message}")
            }
        }
    }

}
