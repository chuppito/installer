import 'package:flutter/material.dart';
import 'dart:io';

class ApkCard extends StatelessWidget {
  final String name, path, size;
  const ApkCard({super.key, required this.name, required this.path, required this.size});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final isSplit = ['apkm', 'xapk', 'apks'].contains(path.split('.').last.toLowerCase());
    return Column(children: [
      Container(width: 72, height: 72,
        decoration: BoxDecoration(color: cs.primary.withOpacity(0.15), borderRadius: BorderRadius.circular(16)),
        child: Icon(isSplit ? Icons.folder_zip_rounded : Icons.android_rounded,
          size: 42, color: isSplit ? cs.primary : const Color(0xFF3DDC84))),
      const SizedBox(height: 12),
      Text(name, textAlign: TextAlign.center, maxLines: 2, overflow: TextOverflow.ellipsis,
        style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: cs.onSurface)),
      const SizedBox(height: 6),
      Container(padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
        decoration: BoxDecoration(color: cs.primary.withOpacity(0.1), borderRadius: BorderRadius.circular(20)),
        child: Text(size, style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: cs.primary))),
      const SizedBox(height: 8),
      Text(_short(path), maxLines: 1, overflow: TextOverflow.ellipsis,
        style: TextStyle(fontSize: 11, color: cs.onSurface.withOpacity(0.4))),
      const SizedBox(height: 10),
      const Row(mainAxisAlignment: MainAxisAlignment.center, children: [
        Icon(Icons.store_rounded, size: 13, color: Color(0xFF1A73E8)),
        SizedBox(width: 4),
        Text('via com.android.vending', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: Color(0xFF1A73E8))),
      ]),
    ]);
  }

  String _short(String p) {
    final parts = p.split(Platform.pathSeparator);
    return parts.length <= 3 ? p : '…/${parts.sublist(parts.length - 2).join('/')}';
  }
}
