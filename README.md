# Installer

Application Flutter pour installer des APK avec `com.android.vending` comme source.

## Formats supportés
`.apk` `.apkm` `.xapk` `.apks`

## Méthodes
- **Standard** — King method avec tous les extras Play Store
- **Shizuku** — am start via shell (Honor, Nothing, Pixel, Redmagic…)
- **ColorOS** — OppoTrick (Oppo, Realme, OnePlus)
- **HyperOS** — Contourne SecurityCenter (Xiaomi, Redmi, Poco)
- **Root** — pm install direct

## Fonctionnalité clé
Après chaque installation, force automatiquement `com.android.vending` comme source via :
`cmd package set-installer <package> com.android.vending`
(via Shizuku ou root si disponible)

## Package : `com.tomtom.installer` | minSdk : 24
