import 'dart:convert';
import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/services.dart';
import 'package:installer/services/app_update_service.dart';

Map<String, dynamic> manifest({int code = 1040}) => {
      'package_name': 'com.tomtom.installer',
      'version': '1.3.0',
      'version_code': code,
      'apk_url':
          'https://github.com/chuppito/installer/releases/download/v1.3.0-1040/Installer-1.3.0-1040.apk',
      'notes': 'Une nouvelle version.',
    };

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test('Reads the version actually installed through the native bridge',
      () async {
    const channel = MethodChannel('com.tomtom.installer/install');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'getAppVersion')
        return {'versionName': '1.3.0', 'versionCode': 1039};
      return null;
    });
    addTearDown(() => TestDefaultBinaryMessengerBinding
        .instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null));
    final installed = await AppUpdateService.installedVersion();
    expect(installed.name, '1.3.0');
    expect(installed.code, 1039);
  });
  test('Rebuilds are detected even with the same version name', () {
    final update = AppUpdate.fromJson(manifest());
    expect(update.isNewerThan(const AppVersion('1.3.0', 1039)), isTrue);
    expect(update.isNewerThan(const AppVersion('1.3.0', 1040)), isFalse);
    expect(update.isNewerThan(const AppVersion('1.3.0', 1041)), isFalse);
  });

  test('Rejects malformed versions, other packages and untrusted APK links',
      () {
    for (final invalid in [
      {...manifest(), 'version_code': '1040'},
      {...manifest(), 'version': 'invalid'},
      {...manifest(), 'package_name': 'another.package'},
      {
        ...manifest(),
        'apk_url':
            'http://github.com/chuppito/installer/releases/download/v1/a.apk'
      },
      {
        ...manifest(),
        'apk_url': 'https://github.com/another/repo/releases/download/v1/a.apk'
      },
      {
        ...manifest(),
        'apk_url':
            'https://github.com.evil.test/chuppito/installer/releases/download/v1/a.apk'
      },
    ]) {
      expect(() => AppUpdate.fromJson(invalid), throwsFormatException);
    }
  });

  group('Manifest HTTP handling', () {
    late HttpServer server;
    late Uri endpoint;
    setUp(() async {
      HttpOverrides.global = null;
      server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      endpoint = Uri.parse('http://127.0.0.1:${server.port}/update.json');
    });
    tearDown(() async => server.close(force: true));

    test('Reads a newer release and does not offer a downgrade', () async {
      server.listen((request) async {
        request.response.write(jsonEncode(manifest()));
        await request.response.close();
      });
      expect(
          await AppUpdateService.check(const AppVersion('1.2.0', 6),
              endpoint: endpoint),
          isNotNull);
      expect(
          await AppUpdateService.check(const AppVersion('1.3.0', 1041),
              endpoint: endpoint),
          isNull);
    });

    test('Unavailable release is an error, not an up-to-date result', () async {
      server.listen((request) async {
        request.response.statusCode = 404;
        await request.response.close();
      });
      await expectLater(
          AppUpdateService.check(const AppVersion('1.2.0', 6),
              endpoint: endpoint),
          throwsA(isA<HttpException>()));
    });

    test('Malformed server response is rejected', () async {
      server.listen((request) async {
        request.response.write('<html>Server error</html>');
        await request.response.close();
      });
      await expectLater(
          AppUpdateService.check(const AppVersion('1.2.0', 6),
              endpoint: endpoint),
          throwsFormatException);
    });
  });
}
