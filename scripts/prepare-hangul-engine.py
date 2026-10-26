#!/usr/bin/env python3
"""Build pinned MIT rhwp into the app's offline asset cache (Node.js 24, npm)."""
from pathlib import Path
import hashlib, json, os, shutil, subprocess, tarfile, urllib.request
ROOT=Path(__file__).resolve().parent.parent
CACHE=ROOT/'.engine-cache/hangul'
SOURCE=CACHE/'source'
OUTPUT=CACHE/'runtime'
COMMIT='1a76570e833917d15817415a53c09ad61ab3203f'
PACKAGE='https://registry.npmjs.org/@rhwp/core/-/core-0.8.7.tgz'

def run(*args,**kwargs): subprocess.run(args,check=True,**kwargs)
def digest(path):
    with path.open('rb') as stream: return hashlib.file_digest(stream,'sha256').hexdigest()
def prepare():
    inputs=[ROOT/'web/hangul'/name for name in ['index.html','host.css','host.js','engine-adapter.ts','host-readonly.ts','patch-engine.py']]
    buildInputs=inputs+[ROOT/'web/hangul/engine-lock.json',Path(__file__)]
    stamp=hashlib.sha256((COMMIT+''.join(digest(p) for p in buildInputs)).encode()).hexdigest()
    if (OUTPUT/'prepared.txt').is_file() and (OUTPUT/'prepared.txt').read_text()==stamp: return
    CACHE.mkdir(parents=True,exist_ok=True)
    if not (SOURCE/'.git').is_dir():
        run('git','clone','--depth','1','--branch','v0.8.7','--filter=blob:none','--sparse','https://github.com/edwardkim/rhwp.git',str(SOURCE))
        run('git','-C',str(SOURCE),'sparse-checkout','set','rhwp-studio','assets/fonts')
    run('git','-C',str(SOURCE),'fetch','--depth','1','origin',COMMIT)
    run('git','-C',str(SOURCE),'checkout','--force',COMMIT)
    archive=CACHE/'rhwp-core-0.8.7.tgz'
    if not archive.is_file():
        with urllib.request.urlopen(PACKAGE,timeout=120) as src,archive.open('wb') as dst: shutil.copyfileobj(src,dst)
    expected=json.loads((ROOT/'web/hangul/engine-lock.json').read_text())['coreTarballSha256']
    if digest(archive)!=expected: raise ValueError('Pinned rhwp WASM SHA-256 mismatch')
    pkg=SOURCE/'pkg';pkg.mkdir(exist_ok=True)
    with tarfile.open(archive) as package:
        for entry in package.getmembers():
            if entry.isfile() and entry.name.startswith('package/') and '/' not in entry.name[8:]:
                with package.extractfile(entry) as src,(pkg/entry.name[8:]).open('wb') as dst: shutil.copyfileobj(src,dst)
    studio=SOURCE/'rhwp-studio'
    shutil.copy2(ROOT/'web/hangul/host-readonly.ts',studio/'src/host-readonly.ts')
    run('python3',str(ROOT/'web/hangul/patch-engine.py'),str(studio))
    main=studio/'src/main.ts';main.write_text(main.read_text()+'\n'+(ROOT/'web/hangul/engine-adapter.ts').read_text())
    config=studio/'vite.config.ts';config.write_text(config.read_text().replace('VitePWA({','VitePWA({ disable: true,'))
    # Bundle only fonts with explicit libre licenses; other document fonts use the engine's fallback.
    for pattern in ['Cafe24*','Happiness*']:
        for p in (SOURCE/'assets/fonts').glob(pattern): p.unlink()
    run('npm','ci','--ignore-scripts',cwd=studio)
    environment=dict(os.environ,RHWP_WITHOUT_HWPCTRL='1',RHWP_DISABLE_EXTERNAL_WEBFONTS='1')
    run('npx','tsc','--noEmit',cwd=studio,env=environment)
    run('npx','vite','build','--base=/engine/',cwd=studio,env=environment)
    stage=CACHE/'staged'
    if stage.exists(): shutil.rmtree(stage)
    stage.mkdir();assets=stage/'hangul';assets.mkdir();shutil.copytree(studio/'dist',assets/'engine')
    for p in inputs[:3]: shutil.copy2(p,assets/p.name)
    shutil.copy2(SOURCE/'LICENSE',assets/'RHWP-LICENSE.txt')
    shutil.copy2(SOURCE/'THIRD_PARTY_LICENSES.md',assets/'THIRD_PARTY_LICENSES.md')
    (stage/'prepared.txt').write_text(stamp)
    if OUTPUT.exists(): shutil.rmtree(OUTPUT)
    stage.rename(OUTPUT)
if __name__=='__main__': prepare()
