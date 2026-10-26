#!/usr/bin/env python3
"""Fetch one unmodified, Git-blob-pinned upstream HWP fixture for regression tests."""
import hashlib,json,pathlib,urllib.parse,urllib.request
root=pathlib.Path(__file__).resolve().parent.parent
manifest=json.loads((root/'scripts/tests/open-corpus.json').read_text())
item=next(f for f in manifest['files'] if f['path']=='sample_hwp/distribution.hwp')
url='https://raw.githubusercontent.com/'+manifest['repository']+'/'+manifest['commit']+'/'+urllib.parse.quote(item['path'])
body=urllib.request.urlopen(url,timeout=60).read()
assert len(body)==item['size']
assert hashlib.sha1(b'blob '+str(len(body)).encode()+b'\0'+body).hexdigest()==item['sha']
out=root/'artifacts/open-fixtures/android-assets';out.mkdir(parents=True,exist_ok=True)
(out/'distribution.hwp').write_bytes(body)
(root/'artifacts/open-fixtures/provenance.json').write_text(json.dumps({
    'repository':manifest['repository'],'commit':manifest['commit'],**item,
    'sha256':hashlib.sha256(body).hexdigest(),'license':'Apache-2.0',
    'sourceURL':url,'purpose':'Unmodified distributable HWP; viewing only, editing remains denied.'},indent=2))
print('OPEN_FIXTURE_VERIFIED',item['sha'],len(body))
