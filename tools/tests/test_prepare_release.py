import hashlib
import subprocess
import os
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from tools.prepare_release import metadata, package_release, certificate_from_output, signed_certificate


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
    def test_numbered_signer_and_public_key_or_source_stamp_are_distinguished(self):
        output = (
            'Signer #1 certificate SHA-256 digest: ' + 'A' * 64 + '\n'
            'Signer #1 public key SHA-256 digest: ' + 'b' * 64 + '\n'
            'Source Stamp Signer certificate SHA-256 digest: ' + 'c' * 64 + '\n'
        )
        self.assertEqual(certificate_from_output(output), 'a' * 64)

    def test_same_certificate_across_sdk_ranges_is_accepted(self):
        output = (
            'Signer (minSdkVersion=33, maxSdkVersion=2147483647) certificate SHA-256 digest: ' + 'a' * 64 + '\n'
            'Signer (minSdkVersion=24, maxSdkVersion=32) certificate SHA-256 digest: ' + 'a' * 64 + '\n'
        )
        self.assertEqual(certificate_from_output(output), 'a' * 64)

    def test_development_sdk_range_and_crlf_are_supported(self):
        output = 'Signer (minSdkVersion=33 (dev release=true), maxSdkVersion=2147483647) certificate SHA-256 digest: ' + 'A' * 64 + '\r\n'
        self.assertEqual(certificate_from_output(output), 'a' * 64)

    def test_missing_or_different_certificates_are_rejected(self):
        for output in [
            '',
            'Source Stamp Signer certificate SHA-256 digest: ' + 'a' * 64,
            'Signer #1 certificate SHA-256 digest: short',
            'Signer #1 certificate SHA-256 digest: ' + 'a' * 64 + '\nSigner #2 certificate SHA-256 digest: ' + 'b' * 64,
            'Signer (minSdkVersion=33, maxSdkVersion=2147483647) certificate SHA-256 digest: ' + 'a' * 64 + '\nSigner (minSdkVersion=24, maxSdkVersion=32) certificate SHA-256 digest: ' + 'b' * 64,
        ]:
            with self.subTest(output=output):
                with self.assertRaises(ValueError):
                    certificate_from_output(output)

    def test_failed_apksigner_verification_is_not_bypassed(self):
        with tempfile.TemporaryDirectory() as directory:
            sdk = Path(directory)
            signer = sdk / 'build-tools/36.0.0/apksigner'
            signer.parent.mkdir(parents=True)
            signer.touch()
            with patch.dict(os.environ, {'ANDROID_HOME': str(sdk)}):
                with patch('tools.prepare_release.subprocess.check_output', side_effect=subprocess.CalledProcessError(1, 'apksigner')):
                    with self.assertRaises(subprocess.CalledProcessError):
                        signed_certificate(Path('invalid.apk'))


if __name__ == '__main__':
    unittest.main()
