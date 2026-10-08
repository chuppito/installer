import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from tools.prepare_release import metadata, package_release


class ReleaseTests(unittest.TestCase):
    def test_rebuild_has_higher_android_code_and_distinct_tag(self):
        first, second = metadata(39), metadata(40)
        self.assertGreater(second['version_code'], first['version_code'])
        self.assertNotEqual(second['tag'], first['tag'])

    def test_manifest_matches_published_apk_and_installed_build_code(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            apk = folder / 'source.apk'
            apk.write_bytes(b'APK bytes for checksum verification')
            with patch('tools.prepare_release.signed_certificate', return_value='a' * 64):
                manifest = package_release(apk, 40, folder / 'release')
            self.assertEqual(manifest['version_code'], 1040)
            self.assertEqual(manifest['sha256'], hashlib.sha256(apk.read_bytes()).hexdigest())
            name = manifest['apk_url'].split('/')[-1]
            self.assertEqual((folder / 'release' / name).read_bytes(), apk.read_bytes())
            self.assertEqual(json.loads((folder / 'release/update.json').read_text()), manifest)
            self.assertEqual((folder / 'release/signing-certificate.txt').read_text().strip(), 'a' * 64)

    def test_changed_signing_key_blocks_publication(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            previous = folder / 'previous.txt'
            previous.write_text('b' * 64)
            with patch('tools.prepare_release.signed_certificate', return_value='a' * 64):
                with self.assertRaisesRegex(ValueError, 'Signing certificate changed'):
                    package_release(folder / 'source.apk', 40, folder / 'release', previous)
            self.assertFalse((folder / 'release').exists())


if __name__ == '__main__':
    unittest.main()
