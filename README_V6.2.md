# Installer V6.2

## Corrections

### Shizuku
- Removed the custom UserService/Binder implementation that could crash Installer when the Shizuku button was pressed.
- Uses the public `Shizuku.newProcess()` API from Shizuku API 12.2.0, which is compatible with the installed Shizuku server versions while avoiding the private API present in 13.x.
- Shizuku executes `pm install -t -i com.android.vending -r <apk>` directly.
- The command exit code and output are captured; failures are returned to Flutter instead of crashing the app.

### Root
- Keeps direct `pm install -t -i com.android.vending -r` installation.
- Reads `InstallSourceInfo` after installation.
- If the installer-of-record is not `com.android.vending`, root attempts `cmd package set-installer` and verifies again.

### Diagnostics
`InstallSourceInfo` records:
- installingPackageName
- initiatingPackageName
- originatingPackageName
- packageSource
- updateOwnerPackageName (where available)

Important: Android documents that changing the installer-of-record does not change `initiatingPackageName`. This distinction is intentionally logged because Android Auto may enforce additional restrictions.
