# Signature et mises à jour d’Installer

Le code peut être public. Ne jamais ajouter le keystore, ses mots de passe ou une clé privée au dépôt.
Le workflow utilise exclusivement les secrets GitHub Actions pour signer les APK de publication.
Il s’arrête si ces secrets manquent : il ne produit pas de release signée avec une nouvelle clé de débogage.

## Configuration initiale

Dans [Settings → Secrets and variables → Actions](https://github.com/chuppito/installer/settings/secrets/actions), créer ces quatre secrets avec **la clé existante** :

| Secret | Valeur |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Contenu du fichier `.jks` / `.keystore` encodé en Base64 |
| `ANDROID_KEYSTORE_PASSWORD` | Mot de passe du keystore |
| `ANDROID_KEY_ALIAS` | Alias de la clé à utiliser dans le keystore |
| `ANDROID_KEY_PASSWORD` | Mot de passe de cette clé |

Pour encoder le fichier sur Linux :

```bash
base64 -w 0 installer.jks > installer-key-base64.txt
```

Sur macOS :

```bash
base64 -i installer.jks | tr -d '\n' > installer-key-base64.txt
```

Sur PowerShell :

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes('installer.jks')) | Set-Content -NoNewline installer-key-base64.txt
```

Copier le contenu du fichier résultant dans le secret `ANDROID_KEYSTORE_BASE64`, puis supprimer ce fichier de travail. Base64 n’est pas un chiffrement : ne pas l’ajouter au dépôt. Conserver une sauvegarde privée du keystore d’origine.

La signature des anciens builds utilisait `signingConfigs.debug`. Disposer d’un keystore ne prouve pas qu’il correspond à l’APK déjà installé. Pour vérifier, comparer le SHA-256 du certificat de l’APK et celui de l’alias du keystore :

```bash
apksigner verify --print-certs ancien-installer.apk
keytool -list -v -keystore installer.jks -alias ALIAS
```

Ces commandes affichent des certificats publics, pas la clé privée. Android exige une signature compatible pour mettre à jour l’application existante. Si les certificats diffèrent et qu’aucune rotation de clé compatible n’a été configurée, il faudra désinstaller l’ancien APK puis installer la première release signée avec la clé fixe. Les données de l’application peuvent être supprimées lors de la désinstallation.

## Publication automatique

1. Rendre le dépôt public pour permettre aux utilisateurs de consulter `update.json` et télécharger l’APK sans connexion GitHub.
2. Renseigner les quatre secrets.
3. Pousser sur `main` ou lancer **Build APK → Run workflow**, en choisissant `main`.

Les compilations d’autres branches produisent un artefact de test, sans release publique.
Les tests doivent réussir, l’APK doit être signé et sa signature vérifiée avant publication.
Le tag est `vVERSION-CODE`, par exemple `v1.3.0-1040`.
Le code Android vaut `1000 + GITHUB_RUN_NUMBER`. Il augmente lors de chaque nouvelle exécution du workflow, y compris si le nom de version reste identique. Relancer une même exécution conserve son numéro.
Le nom de version vient de `pubspec.yaml`. Actualiser aussi `RELEASE_NOTES.md` lors des futures évolutions.

La release contient :

- l’APK signé ;
- `update.json` : version, code Android, URL APK, empreinte du fichier, empreinte publique du certificat et notes ;
- `SHA256SUMS` ;
- `signing-certificate.txt` : **empreinte publique uniquement**.

À partir de la deuxième release, le workflow compare le certificat à celui de la dernière release et refuse la publication si la clé a changé.
Le keystore temporaire est créé hors du dépôt sur le runner et supprimé en fin de compilation. Il n’est ni inclus dans l’APK, ni téléversé en artefact.

## Dans l’application

Installer vérifie une fois au lancement l’adresse :

`https://github.com/chuppito/installer/releases/latest/download/update.json`

Un bouton dans la barre du haut permet aussi une vérification manuelle.
Le code Android de la release est comparé à celui réellement installé, plutôt qu’au titre affiché ou au nom de l’APK.
Une version plus récente affiche **Plus tard / Télécharger**. Le téléchargement s’ouvre dans le navigateur ; ouvrir l’APK téléchargé pour confirmer la mise à jour dans Android.
Une indisponibilité réseau reste silencieuse au lancement et affiche une erreur lors d’une vérification manuelle. Elle n’est pas interprétée comme « aucune mise à jour ».

## Références

- Android Developers, *Sign your app* : https://developer.android.com/studio/publish/app-signing
- GitHub Docs, *Using secrets in GitHub Actions* : https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets
- GitHub Docs, *REST API endpoints for releases* : https://docs.github.com/en/rest/releases/releases
