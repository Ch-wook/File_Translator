#!/usr/bin/env python3
"""Fetch an immutable, OFL-licensed native font pack; fail closed on every byte mismatch."""
from pathlib import Path
import hashlib, json, os, urllib.request

ROOT=Path(__file__).resolve().parent.parent
MANIFEST=ROOT/'config/studio-fonts.json'
OUTPUT=ROOT/'.engine-cache/studio-fonts/assets'
def blob_id(data):
    return hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()
def prepare():
    manifest=json.loads(MANIFEST.read_text())
    fonts=OUTPUT/'fonts';fonts.mkdir(parents=True,exist_ok=True)
    licenses=OUTPUT/'font-licenses';licenses.mkdir(exist_ok=True)
    expected=set();checks=[]
    for family in manifest['families']:
        for item in family['files']:
            dest=(licenses if item['filename'].endswith('.txt') else fonts)/item['filename']
            expected.add(dest)
            data=dest.read_bytes() if dest.is_file() else b''
            if len(data)!=item['size'] or blob_id(data)!=item['gitBlob']:
                url='https://raw.githubusercontent.com/{}/{}/{}'.format(manifest['repository'],manifest['commit'],item['path'])
                with urllib.request.urlopen(url,timeout=120) as response:data=response.read()
                if len(data)!=item['size'] or blob_id(data)!=item['gitBlob']:
                    raise ValueError('Pinned font or license integrity mismatch: '+item['path'])
                temp=dest.with_suffix(dest.suffix+'.tmp')
                with temp.open('wb') as stream:stream.write(data);stream.flush();os.fsync(stream.fileno())
                temp.replace(dest)
            checks.append(dict(family=family['family'],filename=item['filename'],sha256=hashlib.sha256(data).hexdigest(),bytes=len(data)))
    for directory in [fonts,licenses]:
        for dest in directory.iterdir():
            if dest not in expected:dest.unlink()
    (licenses/'studio-fonts.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
    (licenses/'SHA256SUMS').write_text(''.join(item['sha256']+'  '+item['filename']+'\n' for item in checks))
    print(json.dumps(dict(families=len(manifest['families']),files=len(checks),bytes=sum(i['bytes'] for i in checks),checks=checks),ensure_ascii=False))
if __name__=='__main__':prepare()
