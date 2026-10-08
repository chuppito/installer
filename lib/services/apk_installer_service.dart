import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:device_info_plus/device_info_plus.dart';

enum InstallStatus { idle, requestingPermission, pickingFile, installing, success, error }
enum InstallMethod { shizuku, standard }

class ApkInstallerService {
  static const _ch = MethodChannel('com.tomtom.installer/install');

  static Future<bool> requestPermissions() async {
    final info = await DeviceInfoPlugin().androidInfo;
    if (info.version.sdkInt >= 30) {
      if (!await Permission.manageExternalStorage.isGranted)
        await Permission.manageExternalStorage.request();
      return (await Permission.requestInstallPackages.request()).isGranted;
    }
    final s = await [Permission.storage, Permission.requestInstallPackages].request();
    return s.values.every((v) => v.isGranted);
  }

  static Future<String> installApk(String p) async =>
      await _ch.invokeMethod<String>('installApk', {'path': p}) ?? 'started';
  static Future<String> installApkShizuku(String p) async =>
      await _ch.invokeMethod<String>('installApkShizuku', {'path': p}) ?? 'started';
  static Future<String> installSplitApk(String p) async =>
      await _ch.invokeMethod<String>('installSplitApk', {'path': p}) ?? 'started';

  static Future<bool> isShizukuAvailable() async { try { return await _ch.invokeMethod<bool>('isShizukuAvailable') ?? false; } catch (_) { return false; } }
  static Future<bool> isShizukuGranted() async { try { return await _ch.invokeMethod<bool>('isShizukuGranted') ?? false; } catch (_) { return false; } }
  static Future<String?> getLogPath() async { try { return await _ch.invokeMethod<String>('getLogPath'); } catch (_) { return null; } }
  static Future<Map<dynamic, dynamic>?> getInstallSourceInfo(String packageName) async { try { return await _ch.invokeMethod<Map<dynamic, dynamic>>('getInstallSourceInfo', {'packageName': packageName}); } catch (_) { return null; } }
  static Future<Map<dynamic, dynamic>?> verifyInstallSource(String packageName) async { try { return await _ch.invokeMethod<Map<dynamic, dynamic>>('verifyInstallSource', {'packageName': packageName}); } catch (_) { return null; } }
  static Future<void> clearLog() async { try { await _ch.invokeMethod('clearLog'); } catch (_) {} }
  static Future<bool> canInstall() async => await Permission.requestInstallPackages.isGranted;
  static bool isSplit(String p) => ['apkm', 'xapk', 'apks'].contains(p.split('.').last.toLowerCase());
}
