# Installer V7 — Root PackageInstaller session

Cette version teste une voie Root différente de V6.2.

## Méthode Root

Au lieu de `pm install -i ...` en une seule commande, Root utilise explicitement :

1. `cmd package install-create -r -t -i com.android.vending -S <taille>`
2. `cmd package install-write ...`
3. `cmd package install-commit ...`

L'application journalise ensuite `InstallSourceInfo`, notamment `installingPackageName` et `initiatingPackageName`. Android distingue officiellement ces deux informations ; cette V7 sert donc surtout à mesurer si la session explicite change quelque chose sur le Pixel.

## Test

1. Désinstaller complètement l'APK de test.
2. Installer le même APK avec **Root**.
3. Vérifier Android Auto.
4. Si possible, regarder le journal `Download/installer_log.txt`.

Si Android Auto refuse toujours l'application, envoie-moi le bloc `InstallSourceInfo` du journal. On saura alors précisément si le problème vient de l'installateur, de l'initiateur ou d'une autre restriction Android Auto.
