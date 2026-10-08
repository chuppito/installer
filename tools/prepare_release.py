"""Prepare versioned APK releases; private signing keys never enter the output."""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
from pathlib import Path


def version_from_pubspec(path=Path('pubspec.yaml')):
    match = re.search(r'^version:\s*(\d+\.\d+\.\d+)\+(\d+)\s*$', path.read_text(), re.M)
    if not match:
        raise ValueError('pubspec.yaml must contain version: x.y.z+number')
    return match.group(1), int(match.group(2))


def metadata(run_number, path=Path('pubspec.yaml')):
    version, minimum = version_from_pubspec(path)
    # The Actions run number increases even when rebuilding the same version.
    code = 1000 + int(run_number)
    if code < minimum:
        raise ValueError('Actions build number is below the pubspec version code')
    if code > 2100000000:
        raise ValueError('Android version code limit exceeded')
    return {'version': version, 'version_code': code, 'tag': f'v{version}-{code}',
            'apk_name': f'Installer-{version}-{code}.apk'}


def signed_certificate(apk):
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
    candidates = list((sdk / 'build-tools').glob('*/lib/apksigner.jar'))
    if not candidates:
        raise ValueError('Android APK signature verification library is unavailable')
    library = max(candidates, key=lambda p: tuple(int(n) for n in re.findall(r'\d+', p.parent.parent.name)))
    helper = Path(__file__).with_name('ApkCertificateVerifier.java').resolve()
    certificate = subprocess.check_output(
        ['java', '-cp', str(library), str(helper), str(apk)], text=True,
    ).strip()
    if not re.fullmatch(r'[0-9a-f]{64}', certificate):
        raise ValueError('APK verifier did not return one SHA-256 signing certificate')
    return certificate


def package_release(apk, run_number, output, previous_certificate=None):
    info = metadata(run_number)
    certificate = signed_certificate(apk)
    if previous_certificate and certificate != Path(previous_certificate).read_text().strip().lower():
        raise ValueError('Signing certificate changed: use the same signing secrets as the previous release')
    output.mkdir(parents=True, exist_ok=True)
    target = output / info['apk_name']
    shutil.copyfile(apk, target)
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    notes = Path('RELEASE_NOTES.md').read_text().strip()
    if len(notes.encode('utf-8')) > 16000:
        raise ValueError('Release notes are too long')
    manifest = {
        'package_name': 'com.tomtom.installer',
        'version': info['version'], 'version_code': info['version_code'],
        'apk_url': f"https://github.com/chuppito/installer/releases/download/{info['tag']}/{info['apk_name']}",
        'sha256': digest, 'signing_certificate_sha256': certificate, 'notes': notes,
    }
    (output / 'update.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    (output / 'signing-certificate.txt').write_text(certificate + '\n')
    (output / 'SHA256SUMS').write_text(f"{digest}  {info['apk_name']}\n")
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=['metadata', 'package'])
    parser.add_argument('--run-number', required=True, type=int)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--previous-certificate', type=Path)
    args = parser.parse_args()
    if args.command == 'metadata':
        with args.output.open('a') as stream:
            for key, value in metadata(args.run_number).items():
                stream.write(f'{key}={value}\n')
    else:
        if not args.apk:
            parser.error('--apk is required for packaging')
        package_release(args.apk, args.run_number, args.output, args.previous_certificate)
