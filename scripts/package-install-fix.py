#!/usr/bin/env python3
"""Verify both ABI packages, full Android results and their corresponding source archive."""
import hashlib,json,os,pathlib,re,shutil,struct,subprocess,urllib.request,zipfile,xml.etree.ElementTree as ET
root=pathlib.Path.cwd();out=root/'artifacts/delivery';out.mkdir(parents=True,exist_ok=True)
ci=root/'artifacts/ci';ci.mkdir(parents=True,exist_ok=True)
tools=pathlib.Path(os.environ['ANDROID_HOME'])/'build-tools/35.0.0'
name='document-studio-0.8.2-preview';pkg='kr.documentstudio.preview';sha=os.environ['GITHUB_SHA']
def digest(path):
    with path.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def elf_alignments(z,entry):
    with z.open(entry) as f:
        header=f.read(64);endian='<' if header[5]==1 else '>';wide=header[4]==2
        offset=struct.unpack_from(endian+('Q' if wide else 'I'),header,32 if wide else 28)[0]
        size,count=struct.unpack_from(endian+'HH',header,54 if wide else 42)
        f.seek(offset);programs=f.read(size*count)
    aligns=[]
    for i in range(count):
        values=struct.unpack_from(endian+('IIQQQQQQ' if wide else 'IIIIIIII'),programs,i*size)
        if values[0]==1:aligns.append(values[-1])
    assert aligns and all(v>=16384 for v in aligns),(entry.filename,aligns)
    return aligns
apks=[];engine_assets=[]
for abi in ('arm64-v8a','x86_64'):
    apk=root/'artifacts/install-build'/(abi+'.apk')
    signature=subprocess.check_output([str(tools/'apksigner'),'verify','--verbose','--print-certs',str(apk)],text=True)
    (ci/(abi+'-signature.txt')).write_text(signature)
    subprocess.run([str(tools/'zipalign'),'-c','-P','16','4',str(apk)],check=True)
    manifest=subprocess.check_output([str(tools/'aapt2'),'dump','badging',str(apk)],text=True)
    (ci/(abi+'-manifest.txt')).write_text(manifest)
    xml=subprocess.check_output([str(tools/'aapt2'),'dump','xmltree',str(apk),'--file','AndroidManifest.xml'],text=True)
    (ci/(abi+'-manifest-tree.txt')).write_text(xml)
    assert "name='"+pkg+"'" in manifest and "versionCode='12'" in manifest and "versionName='0.8.2-preview'" in manifest
    assert 'testOnly' not in xml,xml
    permissions=subprocess.check_output([str(tools/'aapt2'),'dump','permissions',str(apk)],text=True)
    assert 'android.permission.INTERNET' not in permissions
    cert=re.findall(r'certificate SHA-256 digest: (.+)',signature);assert len(cert)==1
    with zipfile.ZipFile(apk) as z:
        assert z.testzip() is None
        libraries=[p for p in z.infolist() if p.filename.startswith('lib/') and p.filename.endswith('.so')]
        assert {p.filename.split('/')[1] for p in libraries}=={abi}
        assert 'lib/'+abi+'/libandroidapp.so' in z.namelist()
        aligns={p.filename:elf_alignments(z,p) for p in libraries}
        for p in (root/'.engine-cache/hangul/runtime').rglob('*'):
            if not p.is_file():continue
            entry='assets/'+p.relative_to(root/'.engine-cache/hangul/runtime').as_posix()
            data=p.read_bytes();assert z.read(entry)==data,entry
            if abi=='arm64-v8a':engine_assets.append({'path':entry,'sha256':hashlib.sha256(data).hexdigest()})
        assert engine_assets
        assert z.read('assets/dist/studio-policy.js')==(root/'officeruntime/src/main/assets/dist/studio-policy.js').read_bytes()
    filename=name+'-'+abi+'.apk';shutil.copy2(apk,out/filename)
    apks.append({'file':filename,'applicationId':pkg,'versionCode':12,'versionName':'0.8.2-preview','abi':abi,'bytes':apk.stat().st_size,
                 'sha256':digest(apk),'certificateSHA256':cert[0],'elf16k':True,'testOnly':False})
assert apks[0]['certificateSHA256']==apks[1]['certificateSHA256']
# Refuse a future incompatible update to this preview identity.
with urllib.request.urlopen('https://api.github.com/repos/Ch-wook/File_Translator/releases?per_page=50',timeout=60) as response: releases=json.load(response)
for release in releases:
    if release['draft'] or release['tag_name']=='v0.8.2-preview':continue
    for asset in release.get('assets',[]):
        if not asset['name'].endswith('-apk-metadata.json'):continue
        with urllib.request.urlopen(asset['browser_download_url'],timeout=60) as response: previous=json.load(response)
        for old in previous.get('apks',[]):
            if old['applicationId']==pkg:
                assert old['certificateSHA256']==apks[0]['certificateSHA256'],'Signing key differs from an existing preview. Configure the original secure STUDIO_KEYSTORE; do not publish an incompatible update.'
android=json.loads((ci/'android-summary.json').read_text())
assert os.environ['EMULATOR_OUTCOME']=='success' and android=={'tests':19,'failures':0,'errors':0,'skipped':0},android
install=json.loads((root/'artifacts/install-fixed/summary.json').read_text())
assert install['cleanInstallWithoutTestFlag'] and install['coexistsWithOriginal060'] and install['sameSignerReinstallPreservedPrivateData']
reports={}
for module in ('core','officeruntime','app'):
    totals={k:0 for k in ('tests','failures','errors','skipped')}
    for p in (root/module/'build/test-results').rglob('TEST-*.xml'):
        result=ET.parse(p).getroot()
        for k in totals:totals[k]+=int(result.get(k,0))
    assert totals['tests']>0 and totals['failures']==totals['errors']==totals['skipped']==0,(module,totals)
    reports[module]=totals
subprocess.run(['git','archive','--format=zip','-o',str(out/(name+'-source.zip')),'HEAD'],check=True)
tracked={}
for entry in subprocess.check_output(['git','ls-tree','-r','-z','HEAD']).split(b'\0'):
    if not entry:continue
    meta,path=entry.split(b'\t',1);mode,kind,blob=meta.split();assert kind==b'blob'
    tracked[path.decode()]=blob.decode()
with zipfile.ZipFile(out/(name+'-source.zip')) as z:
    assert z.testzip() is None
    assert {p.filename for p in z.infolist() if not p.is_dir()}==set(tracked)
    for path,blob in tracked.items():
        data=z.read(path);assert hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()==blob,path
with (out/(name+'-changes.patch')).open('wb') as f:
    subprocess.run(['git','diff','--binary','06760179e350a408f8fbfbdc10e86f1ed156db6b','HEAD'],stdout=f,check=True)
subprocess.run(['git','apply','--check','--reverse',str(out/(name+'-changes.patch'))],check=True)
subprocess.run(['git','bundle','create',str(out/(name+'-history.bundle')),'--all'],check=True)
subprocess.run(['git','bundle','verify',str(out/(name+'-history.bundle'))],check=True)
metadata={'commit':sha,'physicalDeviceTested':False,'apks':apks}
(out/(name+'-apk-metadata.json')).write_text(json.dumps(metadata,indent=2))
(ci/'apk-engine-assets.json').write_text(json.dumps(engine_assets,indent=2))
summary={'commit':sha,'version':'0.8.2-preview','applicationId':pkg,'androidEmulatorOutcome':os.environ['EMULATOR_OUTCOME'],
         'androidTests':android,'unitTests':reports,'installation':install,'physicalDeviceTested':False,'apks':apks}
(ci/'summary.json').write_text(json.dumps(summary,indent=2))
with zipfile.ZipFile(out/(name+'-verification.zip'),'w',zipfile.ZIP_DEFLATED) as z:
    for folder in ('artifacts/ci','artifacts/install-fixed','artifacts/previous-install','artifacts/native-android','artifacts/document-engines',
                   'core/build/test-results','officeruntime/build/test-results','app/build/test-results','app/build/outputs/androidTest-results','app/build/reports','core/build/visual-fixtures'):
        for p in (root/folder).rglob('*'):
            if p.is_file():z.write(p,p.relative_to(root))
(out/'README.txt').write_text('문서 스튜디오 0.8.2-preview — 설치 충돌 수정\n\n휴대폰용: '+name+'-arm64-v8a.apk\nx86_64는 에뮬레이터/해당 아키텍처용입니다. 두 APK는 독립 실행 가능한 단일 APK입니다.\nAndroid 10+의 64비트 기기가 필요하며 Office는 Android 11+입니다.\n\n기존 kr.documentstudio와 인증서가 달라 교체 업데이트할 수 없었습니다. 새 kr.documentstudio.preview는 기존 앱과 함께 설치되며 기존 앱/데이터를 지우지 않습니다. 앱 이름은 문서 스튜디오 프리뷰입니다. 기존 작업을 옮기려면 이전 앱의 문서·필기 ZIP 백업/새 앱 복원을 사용하세요.\n개발 서명 프리뷰이며 실제 휴대폰 종합 검증은 미완료입니다. ARM64는 정적 APK/ELF 검사를, x86_64는 실제 Android 설치·병행 설치·데이터 보존 재설치 및 전체 P0 19건을 검증했습니다.\n이전 미푸시 0.8.0의 복구본이 아닌 원격 기준 재구현입니다.\n\n커밋: '+sha+'\n')
(out/'SHA256SUMS').write_text(''.join(digest(p)+'  '+p.name+'\n' for p in sorted(out.iterdir()) if p.is_file()))
print('INSTALL_FIX_VERIFIED',json.dumps(summary),flush=True)
