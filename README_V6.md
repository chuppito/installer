# Installer V6

V6 focuses on reliable installer attribution for Android Auto and on robust diagnostics.

## Changes

- Root installation uses `pm install -t -i com.android.vending -r` directly.
- Root installation is automatically verified with Android `InstallSourceInfo`.
- If Android did not keep `com.android.vending` as installer-of-record, V6 runs `cmd package set-installer <package> com.android.vending` through root and verifies again.
- Standard installation follows the proven KingInstaller-style `ACTION_INSTALL_PACKAGE` flow with `EXTRA_INSTALLER_PACKAGE_NAME = com.android.vending`.
- Shizuku no longer calls `Shizuku.newProcess()`.
- Shizuku uses the official `UserService` API and a small AIDL command service. This is the supported direction in Shizuku API 13.x.
- After a Shizuku installation, the package-added receiver verifies `InstallSourceInfo` and, when needed, calls `cmd package set-installer` through the Shizuku UserService.
- Flutter now exposes `getInstallSourceInfo` and `verifyInstallSource` for diagnostics.
- Existing Oppo, HyperOS and split/APKS/APKM/XAPK paths are retained.

## Verification

`InstallSourceInfo` reports separately:

- `installingPackageName`: installer-of-record
- `initiatingPackageName`: package that actually requested the installation
- `originatingPackageName`: package on whose behalf the installation was requested, when available
- `packageSource`: Android package source classification

V6 logs all of these to `Download/installer_log.txt`.
