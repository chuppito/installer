import 'dart:convert';
import 'dart:io';
import 'package:flutter/services.dart';

class AppVersion {
  final String name;
  final int code;
  const AppVersion(this.name, this.code);
}

class AppUpdate {
  final String version;
  final int code;
  final Uri downloadUrl;
  final String notes;

  const AppUpdate(this.version, this.code, this.downloadUrl, this.notes);

  factory AppUpdate.fromJson(Map<String, dynamic> json) {
    final version = json['version'];
    final code = json['version_code'];
    final url = Uri.tryParse(
        json['apk_url'] is String ? json['apk_url'] as String : '');
    if (json['package_name'] != 'com.tomtom.installer' ||
        version is! String ||
        !RegExp(r'^\d+\.\d+\.\d+$').hasMatch(version) ||
        code is! int ||
        code < 1 ||
        url == null ||
        !isAllowedDownload(url)) {
      throw const FormatException('Informations de mise à jour invalides.');
    }
    return AppUpdate(version, code, url,
        json['notes'] is String ? json['notes'] as String : '');
  }

  static bool isAllowedDownload(Uri url) =>
      url.scheme == 'https' &&
      url.host == 'github.com' &&
      url.port == 443 &&
      url.userInfo.isEmpty &&
      !url.hasQuery &&
      !url.hasFragment &&
      url.path.startsWith('/chuppito/installer/releases/download/') &&
      url.path.endsWith('.apk') &&
      !url.pathSegments.contains('..');

  bool isNewerThan(AppVersion installed) => code > installed.code;
}

class AppUpdateService {
  static const _channel = MethodChannel('com.tomtom.installer/install');
  static final manifestUrl = Uri.parse(
    'https://github.com/chuppito/installer/releases/latest/download/update.json',
  );

  static Future<AppVersion> installedVersion() async {
    final info =
        await _channel.invokeMapMethod<String, dynamic>('getAppVersion');
    if (info == null ||
        info['versionName'] is! String ||
        info['versionCode'] is! int) {
      throw const FormatException('Version installée introuvable.');
    }
    return AppVersion(
        info['versionName'] as String, info['versionCode'] as int);
  }

  static Future<AppUpdate?> check(AppVersion installed, {Uri? endpoint}) async {
    final client = HttpClient()
      ..connectionTimeout = const Duration(seconds: 15);
    try {
      final request = await client
          .getUrl(endpoint ?? manifestUrl)
          .timeout(const Duration(seconds: 15));
      request.headers.set(HttpHeaders.acceptHeader, 'application/json');
      final response =
          await request.close().timeout(const Duration(seconds: 15));
      if (response.statusCode == 404) {
        throw const HttpException(
            'Aucune mise à jour publiée accessible. Vérifie que le dépôt est public.');
      }
      if (response.statusCode != 200) {
        throw HttpException(
            'Le serveur de mises à jour répond ${response.statusCode}.');
      }
      final bytes = <int>[];
      await for (final chunk in response.timeout(const Duration(seconds: 15))) {
        bytes.addAll(chunk);
        if (bytes.length > 65536)
          throw const FormatException(
              'Réponse de mise à jour trop volumineuse.');
      }
      final json = jsonDecode(utf8.decode(bytes));
      if (json is! Map<String, dynamic>)
        throw const FormatException('Réponse de mise à jour invalide.');
      final update = AppUpdate.fromJson(json);
      return update.isNewerThan(installed) ? update : null;
    } finally {
      client.close(force: true);
    }
  }

  static Future<void> download(AppUpdate update) async {
    if (!AppUpdate.isAllowedDownload(update.downloadUrl))
      throw const FormatException('Lien APK invalide.');
    await _channel.invokeMethod<void>(
        'openUpdateDownload', {'url': update.downloadUrl.toString()});
  }
}
