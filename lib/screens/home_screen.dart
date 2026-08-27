import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:file_picker/file_picker.dart';
import 'package:permission_handler/permission_handler.dart';
import '../services/apk_installer_service.dart';
import '../widgets/apk_card.dart';
import '../widgets/status_banner.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});
  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with SingleTickerProviderStateMixin {
  String? _path, _name;
  int? _size;
  bool _split = false, _rooted = false, _colorOS = false, _hyperOS = false, _shizuku = false;
  InstallStatus _status = InstallStatus.idle;
  String _msg = '';
  String? _logPath;
  late AnimationController _anim;
  late Animation<double> _fade;

  @override
  void initState() {
    super.initState();
    _anim = AnimationController(vsync: this, duration: const Duration(milliseconds: 350));
    _fade = CurvedAnimation(parent: _anim, curve: Curves.easeInOut);
    _init();
  }

  Future<void> _init() async {
    final r = await ApkInstallerService.isRooted();
    final c = await ApkInstallerService.isColorOS();
    final h = await ApkInstallerService.isHyperOS();
    final s = await ApkInstallerService.isShizukuAvailable();
    final l = await ApkInstallerService.getLogPath();
    setState(() { _rooted = r; _colorOS = c; _hyperOS = h; _shizuku = s; _logPath = l; });
  }

  @override
  void dispose() { _anim.dispose(); super.dispose(); }

  Future<void> _pick() async {
    _set(InstallStatus.requestingPermission, 'Vérification des permissions…');
    if (!await ApkInstallerService.requestPermissions()) {
      _set(InstallStatus.error, 'Permissions refusées.'); _permDialog(); return;
    }
    _set(InstallStatus.pickingFile, 'Sélection…');
    try {
      final r = await FilePicker.platform.pickFiles(type: FileType.any, allowMultiple: false);
      if (r?.files.single.path != null) {
        final p = r!.files.single.path!;
        final ext = p.split('.').last.toLowerCase();
        if (!['apk', 'apkm', 'xapk', 'apks'].contains(ext)) {
          _set(InstallStatus.error, 'Format non supporté. Utilise APK, APKM, XAPK ou APKS.'); return;
        }
        final s = await File(p).stat();
        setState(() { _path = p; _name = r.files.single.name; _size = s.size; _split = ApkInstallerService.isSplit(p); _status = InstallStatus.idle; _msg = ''; });
        _anim.forward(from: 0);
      } else { _set(InstallStatus.idle, ''); }
    } catch (e) { _set(InstallStatus.error, 'Erreur: $e'); }
  }

  Future<void> _install(InstallMethod method) async {
    if (_path == null) return;
    _set(InstallStatus.installing, 'En attente…');
    try {
      if (!await ApkInstallerService.canInstall()) await Permission.requestInstallPackages.request();
      String code;
      if (_split) {
        code = await ApkInstallerService.installSplitApk(_path!);
      } else {
        code = switch (method) {
          InstallMethod.standard => await ApkInstallerService.installApk(_path!),
          InstallMethod.oppoNoRoot => await ApkInstallerService.installApkOppo(_path!),
          InstallMethod.hyperOS => await ApkInstallerService.installApkHyperOS(_path!),
          InstallMethod.shizuku => await ApkInstallerService.installApkShizuku(_path!),
          InstallMethod.oppoRoot => await ApkInstallerService.installApkRoot(_path!),
        };
      }
      switch (code) {
        case 'install_success': _set(InstallStatus.success, 'Installation réussie ✓');
        case 'install_started': _set(InstallStatus.success, 'Installation lancée ✓');
        case 'install_cancelled': _set(InstallStatus.error, 'Annulée.');
        case 'install_failed': _set(InstallStatus.error, 'Échec. Vérifie la signature APK.');
        default: _set(InstallStatus.success, 'Lancée ($code)');
      }
    } catch (e) {
      final msg = e.toString();
      if (msg.contains('SHIZUKU_UNAVAILABLE')) {
        _set(InstallStatus.error, 'Shizuku non disponible. Installe l\'app Shizuku depuis le Play Store et lance-le.');
      } else if (msg.contains('SHIZUKU_DENIED')) {
        _set(InstallStatus.error, 'Permission Shizuku refusée.');
      } else {
        _set(InstallStatus.error, 'Erreur: $msg');
      }
    }
  }

  void _set(InstallStatus s, String m) => setState(() { _status = s; _msg = m; });
  void _reset() { setState(() { _path = _name = null; _size = null; _split = false; _status = InstallStatus.idle; _msg = ''; }); _anim.reverse(); }
  String _fmt(int b) => b < 1048576 ? '${(b / 1024).toStringAsFixed(1)} Ko' : '${(b / 1048576).toStringAsFixed(1)} Mo';

  void _permDialog() => showDialog(context: context, builder: (ctx) => AlertDialog(
    title: const Text('Permissions requises'),
    content: const Text('Accès aux fichiers et installation d\'applications requis.'),
    actions: [TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Annuler')), FilledButton(onPressed: () { Navigator.pop(ctx); openAppSettings(); }, child: const Text('Paramètres'))],
  ));

  void _shizukuDialog() => showDialog(context: context, builder: (ctx) => AlertDialog(
    title: const Text('Shizuku — Mode universel'),
    content: const SingleChildScrollView(child: Text(
      'Shizuku permet d\'installer des APK avec com.android.vending sans root.\n\n'
      'Testé et confirmé sur :\n'
      '• Honor • Nothing Phone\n'
      '• Pixel • Redmagic\n'
      '• Et la plupart des appareils\n\n'
      'Comment l\'activer :\n'
      '1. Installe "Shizuku" depuis le Play Store\n'
      '2. Active le débogage sans fil dans les options développeur\n'
      '3. Lance Shizuku via "Démarrer via ADB"\n'
      '4. Reviens ici et appuie sur Shizuku',
    )),
    actions: [FilledButton(onPressed: () => Navigator.pop(ctx), child: const Text('OK'))],
  ));

  void _logDialog() => showDialog(context: context, builder: (ctx) => AlertDialog(
    title: const Text('Log de débogage'),
    content: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
      Container(padding: const EdgeInsets.all(10),
        decoration: BoxDecoration(color: Colors.grey.withOpacity(0.1), borderRadius: BorderRadius.circular(8)),
        child: Text(_logPath ?? 'Downloads/installer_log.txt', style: const TextStyle(fontSize: 11, fontFamily: 'monospace'))),
      const SizedBox(height: 10),
      const Text('Partage ce fichier pour analyser les erreurs.', style: TextStyle(fontSize: 12)),
    ]),
    actions: [
      TextButton(onPressed: () async { await ApkInstallerService.clearLog(); if (ctx.mounted) { Navigator.pop(ctx); ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Log effacé'))); } }, child: const Text('Effacer')),
      FilledButton(onPressed: () { Clipboard.setData(ClipboardData(text: _logPath ?? '')); Navigator.pop(ctx); ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Copié'))); }, child: const Text('Copier')),
    ],
  ));

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final dark = Theme.of(context).brightness == Brightness.dark;
    final busy = _status == InstallStatus.installing;

    return Scaffold(
      backgroundColor: cs.surface,
      appBar: AppBar(backgroundColor: Colors.transparent, elevation: 0,
        title: Row(children: [
          Container(width: 36, height: 36, decoration: BoxDecoration(color: cs.primary, borderRadius: BorderRadius.circular(8)),
            child: const Icon(Icons.system_update_alt_rounded, color: Colors.white, size: 20)),
          const SizedBox(width: 10),
          const Text('Installer', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 20)),
        ]),
        actions: [
          IconButton(onPressed: _logDialog, icon: const Icon(Icons.bug_report_outlined), tooltip: 'Log'),
          if (_path != null) IconButton(onPressed: _reset, icon: const Icon(Icons.close_rounded)),
          const SizedBox(width: 4),
        ],
      ),
      body: SafeArea(child: SingleChildScrollView(padding: const EdgeInsets.all(20), child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // Banner
          Container(padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
            decoration: BoxDecoration(color: const Color(0xFF1A73E8).withOpacity(0.1), borderRadius: BorderRadius.circular(12), border: Border.all(color: const Color(0xFF1A73E8).withOpacity(0.3))),
            child: const Row(children: [
              Icon(Icons.store_rounded, color: Color(0xFF1A73E8), size: 18),
              SizedBox(width: 10),
              Expanded(child: Text('Installation via com.android.vending', style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: Color(0xFF1A73E8)))),
              Icon(Icons.verified_rounded, color: Color(0xFF1A73E8), size: 16),
            ])),

          // Badges
          if (_colorOS || _hyperOS || _rooted || _shizuku || _split) ...[
            const SizedBox(height: 10),
            Wrap(spacing: 8, runSpacing: 6, children: [
              if (_colorOS) _badge('ColorOS', Colors.orange),
              if (_hyperOS) _badge('HyperOS', Colors.deepOrange),
              if (_rooted) _badge('Root', Colors.green),
              if (_shizuku) _badge('Shizuku ✓', Colors.teal),
              if (_split) _badge('Split APK', Colors.purple),
            ]),
          ],

          const SizedBox(height: 20),

          // Zone sélection
          AnimatedContainer(duration: const Duration(milliseconds: 250),
            decoration: BoxDecoration(
              color: _path != null ? cs.primaryContainer.withOpacity(0.3) : (dark ? Colors.white.withOpacity(0.05) : Colors.grey.shade100),
              borderRadius: BorderRadius.circular(20),
              border: Border.all(color: _path != null ? cs.primary.withOpacity(0.5) : Colors.grey.withOpacity(0.3), width: 2),
            ),
            child: InkWell(onTap: busy ? null : _pick, borderRadius: BorderRadius.circular(20),
              child: Padding(padding: const EdgeInsets.all(28),
                child: AnimatedSwitcher(duration: const Duration(milliseconds: 250),
                  child: _path == null
                      ? Column(key: const ValueKey('e'), children: [
                          Container(width: 72, height: 72, decoration: BoxDecoration(color: cs.primary.withOpacity(0.1), shape: BoxShape.circle), child: Icon(Icons.folder_open_rounded, size: 38, color: cs.primary)),
                          const SizedBox(height: 14),
                          Text('Sélectionner un fichier', style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700, color: cs.onSurface)),
                          const SizedBox(height: 6),
                          Text('APK • APKM • XAPK • APKS', style: TextStyle(fontSize: 13, color: cs.onSurface.withOpacity(0.5))),
                        ])
                      : FadeTransition(key: const ValueKey('f'), opacity: _fade, child: ApkCard(name: _name!, path: _path!, size: _fmt(_size ?? 0))),
                ),
              ),
            ),
          ),

          const SizedBox(height: 20),
          if (_msg.isNotEmpty) ...[StatusBanner(status: _status, message: _msg), const SizedBox(height: 16)],

          // Boutons
          if (_path == null)
            _mainBtn('Choisir un fichier', Icons.folder_open_rounded, cs.primary, _pick, false)
          else Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            _mainBtn(_split ? 'Installer (split)' : 'Installer', Icons.system_update_alt_rounded, const Color(0xFF1A73E8), busy ? null : () => _install(InstallMethod.standard), busy),
            if (!_split) ...[
              const SizedBox(height: 12),
              _div(cs),
              const SizedBox(height: 12),
              _altBtn('Shizuku (universel)', 'Honor, Nothing, Pixel, Redmagic…', Icons.vpn_key_rounded, Colors.teal,
                busy ? null : () => _install(InstallMethod.shizuku),
                info: _shizukuDialog),
              const SizedBox(height: 10),
              _altBtn('Oppo / Realme (ColorOS)', 'OppoTrick — bypass scanner', Icons.phonelink_setup_rounded, Colors.orange, busy ? null : () => _install(InstallMethod.oppoNoRoot)),
              const SizedBox(height: 10),
              _altBtn('Xiaomi / Redmi / Poco (HyperOS)', 'Contourne le Security Center', Icons.shield_outlined, Colors.deepOrange, busy ? null : () => _install(InstallMethod.hyperOS)),
              const SizedBox(height: 10),
              _altBtn('Root', 'pm install -i com.android.vending', Icons.security_rounded, _rooted ? Colors.green : Colors.grey,
                busy ? null : () {
                  if (!_rooted) showDialog(context: context, builder: (ctx) => AlertDialog(title: const Text('Root non détecté'), content: const Text('Cette méthode nécessite root.'), actions: [FilledButton(onPressed: () => Navigator.pop(ctx), child: const Text('OK'))]));
                  else _install(InstallMethod.oppoRoot);
                }, disabled: !_rooted),
            ],
          ]),

          const SizedBox(height: 28),
          _compat(cs),
          const SizedBox(height: 20),
        ],
      ))),
    );
  }

  Widget _badge(String l, Color c) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
    decoration: BoxDecoration(color: c.withOpacity(0.12), borderRadius: BorderRadius.circular(20), border: Border.all(color: c.withOpacity(0.4))),
    child: Text(l, style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: c)));

  Widget _div(ColorScheme cs) => Row(children: [
    Expanded(child: Divider(color: cs.outline.withOpacity(0.3))),
    Padding(padding: const EdgeInsets.symmetric(horizontal: 12), child: Text('Méthodes alternatives', style: TextStyle(fontSize: 11, color: cs.onSurface.withOpacity(0.4)))),
    Expanded(child: Divider(color: cs.outline.withOpacity(0.3))),
  ]);

  Widget _mainBtn(String l, IconData ic, Color c, VoidCallback? fn, bool loading) => SizedBox(height: 56,
    child: FilledButton(onPressed: fn,
      style: FilledButton.styleFrom(backgroundColor: c, foregroundColor: Colors.white, shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14))),
      child: loading
          ? const Row(mainAxisAlignment: MainAxisAlignment.center, children: [SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white)), SizedBox(width: 10), Text('En attente…', style: TextStyle(fontSize: 15, fontWeight: FontWeight.w700))])
          : Row(mainAxisAlignment: MainAxisAlignment.center, children: [Icon(ic, size: 20), const SizedBox(width: 10), Text(l, style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w800))])));

  Widget _altBtn(String l, String sub, IconData ic, Color c, VoidCallback? fn, {bool disabled = false, VoidCallback? info}) => Opacity(
    opacity: disabled ? 0.5 : 1,
    child: OutlinedButton(onPressed: fn,
      style: OutlinedButton.styleFrom(foregroundColor: c, side: BorderSide(color: c.withOpacity(0.5), width: 1.5), shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)), padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10)),
      child: Row(children: [
        Icon(ic, size: 20, color: c), const SizedBox(width: 10),
        Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(l, style: TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: c)),
          Text(sub, style: TextStyle(fontSize: 11, color: c.withOpacity(0.7))),
        ])),
        if (info != null) GestureDetector(onTap: info, child: Icon(Icons.info_outline, size: 18, color: c.withOpacity(0.7)))
        else Icon(Icons.arrow_forward_ios_rounded, size: 13, color: c.withOpacity(0.5)),
      ])));

  Widget _compat(ColorScheme cs) {
    const items = [('Pixel', Icons.smartphone_rounded), ('Samsung', Icons.phone_android_rounded), ('OnePlus', Icons.devices_rounded), ('Oppo', Icons.phone_iphone_rounded), ('Realme', Icons.phone_rounded), ('Xiaomi', Icons.phone_android_rounded), ('LineageOS', Icons.settings_applications_rounded), ('Honor', Icons.phone_rounded), ('Nothing', Icons.phone_android_rounded), ('Redmagic', Icons.videogame_asset_rounded)];
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text('Compatible avec', style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: cs.onSurface.withOpacity(0.5))),
      const SizedBox(height: 8),
      Wrap(spacing: 8, runSpacing: 8, children: items.map((b) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
        decoration: BoxDecoration(color: cs.surfaceContainerHighest, borderRadius: BorderRadius.circular(20)),
        child: Row(mainAxisSize: MainAxisSize.min, children: [Icon(b.$2, size: 13, color: cs.primary), const SizedBox(width: 4), Text(b.$1, style: TextStyle(fontSize: 12, color: cs.onSurface.withOpacity(0.8)))]),
      )).toList()),
    ]);
  }
}
