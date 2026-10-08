import hashlib
import subprocess
import os
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from tools.prepare_release import metadata, package_release, signed_certificate


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



class CertificateTests(unittest.TestCase):
    def test_verified_certificate_is_used_and_latest_sdk_library_selected(self):
        with tempfile.TemporaryDirectory() as directory:
            sdk = Path(directory)
            for version in ('9.0.0', '36.0.0'):
                library = sdk / f'build-tools/{version}/lib/apksigner.jar'
                library.parent.mkdir(parents=True)
                library.touch()
            with patch.dict(os.environ, {'ANDROID_HOME': str(sdk)}):
                with patch('tools.prepare_release.subprocess.check_output', return_value='a' * 64 + '\n') as verify:
                    self.assertEqual(signed_certificate(Path('signed.apk')), 'a' * 64)
                    self.assertEqual(verify.call_args.args[0][2], str(library))

    def test_invalid_verifier_output_blocks_publication(self):
        with tempfile.TemporaryDirectory() as directory:
            sdk = Path(directory)
            library = sdk / 'build-tools/36.0.0/lib/apksigner.jar'
            library.parent.mkdir(parents=True)
            library.touch()
            with patch.dict(os.environ, {'ANDROID_HOME': str(sdk)}):
                for output in ('', 'short', 'a' * 64 + '\n' + 'b' * 64):
                    with self.subTest(output=output):
                        with patch('tools.prepare_release.subprocess.check_output', return_value=output):
                            with self.assertRaises(ValueError):
                                signed_certificate(Path('invalid.apk'))

    def test_failed_signature_verification_is_not_bypassed(self):
        with tempfile.TemporaryDirectory() as directory:
            sdk = Path(directory)
            library = sdk / 'build-tools/36.0.0/lib/apksigner.jar'
            library.parent.mkdir(parents=True)
            library.touch()
            with patch.dict(os.environ, {'ANDROID_HOME': str(sdk)}):
                with patch('tools.prepare_release.subprocess.check_output', side_effect=subprocess.CalledProcessError(1, 'java')):
                    with self.assertRaises(subprocess.CalledProcessError):
                        signed_certificate(Path('invalid.apk'))


if __name__ == '__main__':
    unittest.main()
