# Installer

Application Flutter pour installer des APK avec `com.android.vending` comme source.

## Formats supportés
`.apk` `.apkm` `.xapk` `.apks`

## Méthodes
- Standard (Pixel, Samsung, LineageOS…)
- Shizuku — universel sans root (Honor, Nothing, Pixel, Redmagic…)
- ColorOS (Oppo, Realme, OnePlus)
- HyperOS (Xiaomi, Redmi, Poco)
- Root

## Package : `com.tomtom.installer`
## minSdk : 24 (Android 7+)


## Version 1.1.0 — modes d'installation

Les méthodes restent sélectionnables manuellement :
- Package Installer standard ;
- Shizuku + Package Installer ;
- Oppo / Realme (ColorOS) ;
- Xiaomi / Redmi / Poco (HyperOS) ;
- Root.

### Shizuku + correction Play Store

Le mode Shizuku n'utilise pas `pm install` directement. Il lance le Package Installer système via `am start`, puis surveille la fin de l'installation. Une fois l'application détectée comme installée, il exécute :

```text
cmd package set-installer <package> com.android.vending
```

L'objectif est donc d'obtenir à la fois l'installation par le Package Installer système et l'installer-of-record `com.android.vending`. Shizuku reste optionnel : l'application peut être utilisée sans Shizuku avec les autres boutons.

La méthode Root applique également `cmd package set-installer` après `pm install`.
