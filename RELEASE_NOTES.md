Installer 1.3.6

- Correction du lancement en root : changement d’identité et ouverture de l’installateur effectués sur le thread principal du processus séparé.
- Vérification de l’identité du processus dans /proc avant le lancement, afin de refuser une identité root résiduelle.
- Journalisation du PID, du thread et des UID réellement vérifiés.
- Conservation du mode ADB, des archives split et de la signature de publication.
