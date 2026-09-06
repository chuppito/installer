import 'package:flutter/material.dart';
import '../services/apk_installer_service.dart';

class StatusBanner extends StatelessWidget {
  final InstallStatus status;
  final String message;
  const StatusBanner({super.key, required this.status, required this.message});

  @override
  Widget build(BuildContext context) {
    final Color color;
    final IconData icon;
    switch (status) {
      case InstallStatus.success: color = const Color(0xFF0F9D58); icon = Icons.check_circle_rounded;
      case InstallStatus.error: color = const Color(0xFFE53935); icon = Icons.error_rounded;
      case InstallStatus.installing: color = const Color(0xFF1A73E8); icon = Icons.downloading_rounded;
      default: color = const Color(0xFFF4B400); icon = Icons.info_rounded;
    }
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      decoration: BoxDecoration(
        color: color.withOpacity(0.12),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: color.withOpacity(0.3)),
      ),
      child: Row(children: [
        status == InstallStatus.installing
            ? SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2, color: color))
            : Icon(icon, color: color, size: 20),
        const SizedBox(width: 12),
        Expanded(child: Text(message, style: TextStyle(color: color, fontSize: 13, fontWeight: FontWeight.w600))),
      ]),
    );
  }
}
