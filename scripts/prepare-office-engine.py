#!/usr/bin/env python3
"""Verify and extract the same pinned Office runtime as prepare-office-engine.ps1."""
import hashlib
from pathlib import Path, PurePosixPath
import shutil
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT / '.engine-cache'
RUNTIME = CACHE / 'runtime/main'
COMMIT = 'cf7b84af545e7112ee7ea69d7ae1ccbd2b38e4e9'
ARTIFACTS = {
    'x86_64': '8ef8fc5aff5ab4296a7f54d0293e49807ca6a606ffe47a7330b1f20a677dd544',
    'arm64-v8a': '554df83f7748476e950867ffc5597f5c9074b8790f9d8d8aa5eeb807cc89fa86',
}


def prepare():
    stamp = RUNTIME / 'prepared-v2.txt'
    if stamp.is_file() and all((RUNTIME / 'jniLibs' / abi / 'libandroidapp.so').is_file() for abi in ARTIFACTS):
        print('Pinned Office engine is ready.')
        return
    CACHE.mkdir(parents=True, exist_ok=True)
    RUNTIME.mkdir(parents=True, exist_ok=True)
    for abi, expected in ARTIFACTS.items():
        apk = CACHE / f'collabora-{abi}.apk'
        if not apk.is_file():
            url = f'https://www.collaboraoffice.com/downloads/Collabora-Office-Android-Snapshot/collabora-office-mobile-26.04.6.0-951-snapshot-{abi}-2026-10-15.apk'
            partial = apk.with_suffix('.apk.part')
            print(f'Downloading pinned {abi} Office runtime...', flush=True)
            with urllib.request.urlopen(url, timeout=120) as src, partial.open('wb') as dest:
                shutil.copyfileobj(src, dest)
            partial.replace(apk)
        with apk.open('rb') as src:
            actual = hashlib.file_digest(src, 'sha256').hexdigest()
        if actual != expected:
            raise ValueError(f'Office runtime SHA-256 mismatch: {abi}')
        with zipfile.ZipFile(apk) as archive:
            for entry in archive.infolist():
                name = entry.filename
                if entry.is_dir() or name.startswith('assets/dexopt/'):
                    continue
                if name.startswith(f'lib/{abi}/'):
                    relative = 'jniLibs/' + name[4:]
                elif abi == 'x86_64' and name.startswith('assets/'):
                    relative = name
                else:
                    continue
                path = PurePosixPath(relative)
                if path.is_absolute() or '..' in path.parts or '\\' in relative:
                    raise ValueError('Office archive path escaped runtime')
                target = RUNTIME.joinpath(*path.parts).resolve()
                if not target.is_relative_to(RUNTIME.resolve()):
                    raise ValueError('Office archive path escaped runtime')
                target.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(entry) as src, target.open('wb') as dest:
                    shutil.copyfileobj(src, dest)
    fonts = RUNTIME / 'assets/unpack/etc/fonts/fonts.conf'
    fonts.write_text(fonts.read_text(encoding='utf-8').replace('com.collabora.libreoffice', 'kr.documentstudio'), encoding='utf-8')
    for name in ('baseline.prof', 'baseline.profm'):
        (RUNTIME / 'assets/dexopt' / name).unlink(missing_ok=True)
    stamp.write_text(COMMIT, encoding='utf-8')
    print('Verified and extracted both Office ABIs.')


if __name__ == '__main__':
    prepare()
