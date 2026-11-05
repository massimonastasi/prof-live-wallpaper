#!/usr/bin/env python3
"""Fetch and verify the exact freely licensed artwork used by the build."""
import hashlib
import io
from pathlib import Path
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
URL = 'https://github.com/freedoom/freedoom/releases/download/v0.13.0/freedoom-0.13.0.zip'
ARCHIVE_SHA256 = '3f9b264f3e3ce503b4fb7f6bdcb1f419d93c7b546f4df3e874dd878db9688f59'
WAD_SHA256 = 'a8772e088847032510d97ba2312406a6998f21cbab44d4ff10696faa9c0ecd4b'
target = ROOT / 'app/wad/freedoom-full.wad'
if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() == WAD_SHA256:
    print('Freedoom 0.13.0 Phase 2 already verified')
else:
    with urllib.request.urlopen(URL, timeout=120) as response:
        archive = response.read()
    if hashlib.sha256(archive).hexdigest() != ARCHIVE_SHA256:
        raise SystemExit('Freedoom archive checksum mismatch')
    with zipfile.ZipFile(io.BytesIO(archive)) as z:
        names = [n for n in z.namelist() if n.endswith('/freedoom2.wad')]
        if len(names) != 1:
            raise SystemExit('Expected exactly one Phase 2 WAD')
        wad = z.read(names[0])
    if hashlib.sha256(wad).hexdigest() != WAD_SHA256:
        raise SystemExit('Freedoom WAD checksum mismatch')
    target.parent.mkdir(parents=True, exist_ok=True)
    staged = target.with_suffix('.tmp')
    staged.write_bytes(wad)
    staged.replace(target)
    print('Verified Freedoom 0.13.0 Phase 2 downloaded')
