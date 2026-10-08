import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:file_picker/file_picker.dart';
import 'package:permission_handler/permission_handler.dart';
import '../services/apk_installer_service.dart';
import '../services/app_update_service.dart';
import '../widgets/apk_card.dart';
import '../widgets/status_banner.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});
  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with SingleTickerProviderStateMixin, WidgetsBindingObserver {
  String? _path, _name;
  int? _size;
  bool _split = false, _shizuku = false;
  InstallStatus _status = InstallStatus.idle;
  String _msg = '';
  String? _logPath;
  AppVersion? _version;
  bool _checkingUpdate = false;
  late AnimationController _anim;
  late Animation<double> _fade;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _anim = AnimationController(vsync: this, duration: const Duration(milliseconds: 350));
    _fade = CurvedAnimation(parent: _anim, curve: Curves.easeInOut);
    _init();
    _checkUpdate(automatic: true);
  }

  Future<void> _init() async {
    final s = await ApkInstallerService.isShizukuAvailable();
    final l = await ApkInstallerService.getLogPath();
    if (!mounted) return;
    setState(() { _shizuku = s; _logPath = l; });
  }

  Future<void> _checkUpdate({bool automatic = false}) async {
    if (_checkingUpdate) return;
    setState(() => _checkingUpdate = true);
    try {
      final installed = _version ?? await AppUpdateService.installedVersion();
      if (!mounted) return;
      setState(() => _version = installed);
      final update = await AppUpdateService.check(installed);
      if (!mounted) return;
      if (update == null) {
        if (!automatic) ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Installer ${installed.name} : aucune mise à jour disponible.')),
        );
        return;
      }
      final download = await showDialog<bool>(context: context, builder: (ctx) => AlertDialog(
        title: const Text('Mise à jour disponible'),
        content: SingleChildScrollView(child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Version installée : ${installed.name} (${installed.code})'),
            Text('Nouvelle version : ${update.version} (${update.code})'),
            if (update.notes.isNotEmpty) ...[const SizedBox(height: 12), Text(update.notes)],
            const SizedBox(height: 12),
            const Text('Télécharge l’APK, puis ouvre-le pour confirmer la mise à jour dans Android.'),
          ],
        )),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Plus tard')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Télécharger')),
        ],
      ));
      if (download == true) await AppUpdateService.download(update);
    } catch (e) {
      if (mounted && !automatic) ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Impossible de vérifier les mises à jour : $e')),
      );
    } finally {
      if (mounted) setState(() => _checkingUpdate = false);
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _anim.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _init();
  }

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
          InstallMethod.shizuku => await ApkInstallerService.installApkShizuku(_path!),
        };
      }
      switch (code) {
        case 'install_success': _set(InstallStatus.success, 'Installation réussie ✓');
        case 'native_installer_opened':
          _set(InstallStatus.idle, 'Programme d’installation Android ouvert. Confirme dans sa fenêtre.');
        case 'install_started':
          _set(InstallStatus.idle, 'Installation lancée. Termine dans la fenêtre Android.');
        case 'install_cancelled': _set(InstallStatus.error, 'Annulée.');
        case 'install_failed': _set(InstallStatus.error, 'Échec. Vérifie la signature APK.');
        default: _set(InstallStatus.error, 'Résultat non confirmé ($code). Consulte le journal.');
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

  void _set(InstallStatus s, String m) {
    if (!mounted) return;
    setState(() { _status = s; _msg = m; });
  }
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
      'Shizuku ouvre le programme d’installation Android : confirme l’installation ou la mise à jour, puis utilise « Ouvrir » si proposé.\n\n'
      'L’attribution Play Store est demandée puis vérifiée dans le journal. '
      'Son acceptation dépend du système.\n\n'
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
          Image.asset('assets/installer_icon.png', width: 36, height: 36),
          const SizedBox(width: 10),
          Expanded(child: Text(_version == null ? 'Installer' : 'Installer ${_version!.name}', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 20), overflow: TextOverflow.ellipsis)),
        ]),
        actions: [
          IconButton(
            onPressed: _checkingUpdate ? null : () => _checkUpdate(),
            icon: _checkingUpdate
                ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
                : const Icon(Icons.system_update_rounded),
            tooltip: 'Vérifier les mises à jour',
          ),
          IconButton(onPressed: _logDialog, icon: const Icon(Icons.bug_report_outlined), tooltip: 'Log'),
          if (_path != null) IconButton(onPressed: _status == InstallStatus.installing ? null : _reset, icon: const Icon(Icons.close_rounded)),
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
              Expanded(child: Text('Attribution Play Store demandée', style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: Color(0xFF1A73E8)))),
              Icon(Icons.verified_rounded, color: Color(0xFF1A73E8), size: 16),
            ])),

          // Badges
          if (_shizuku || _split) ...[
            const SizedBox(height: 10),
            Wrap(spacing: 8, runSpacing: 6, children: [
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

          // Installation methods: Shizuku first, then the standard Android installer.
          if (_path == null)
            _mainBtn('Choisir un fichier', Icons.folder_open_rounded, cs.primary, _pick, false)
          else Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            if (!_split) ...[
              _mainBtn('Shizuku', Icons.vpn_key_rounded, Colors.teal,
                busy ? null : () => _install(InstallMethod.shizuku), busy),
              Align(alignment: Alignment.centerRight,
                child: TextButton.icon(onPressed: _shizukuDialog,
                  icon: const Icon(Icons.info_outline, size: 16),
                  label: const Text('Comment activer Shizuku ?'))),
              const SizedBox(height: 4),
            ],
            _mainBtn(_split ? 'Package Installer (split)' : 'Package Installer',
              Icons.system_update_alt_rounded, const Color(0xFF1A73E8),
              busy ? null : () => _install(InstallMethod.standard), busy),
          ]),

          const SizedBox(height: 28),
          const SizedBox(height: 20),
        ],
      ))),
    );
  }

  Widget _badge(String l, Color c) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
    decoration: BoxDecoration(color: c.withOpacity(0.12), borderRadius: BorderRadius.circular(20), border: Border.all(color: c.withOpacity(0.4))),
    child: Text(l, style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: c)));

  Widget _mainBtn(String l, IconData ic, Color c, VoidCallback? fn, bool loading) => SizedBox(height: 56,
    child: FilledButton(onPressed: fn,
      style: FilledButton.styleFrom(backgroundColor: c, foregroundColor: Colors.white, shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14))),
      child: loading
          ? const Row(mainAxisAlignment: MainAxisAlignment.center, children: [SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white)), SizedBox(width: 10), Text('En attente…', style: TextStyle(fontSize: 15, fontWeight: FontWeight.w700))])
          : Row(mainAxisAlignment: MainAxisAlignment.center, children: [Icon(ic, size: 20), const SizedBox(width: 10), Text(l, style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w800))])));

}
