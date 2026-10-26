#!/usr/bin/env python3
"""Verify both ABI packages, full Android results and their corresponding source archive."""
import hashlib,json,os,pathlib,re,shutil,struct,subprocess,urllib.request,zipfile,xml.etree.ElementTree as ET
root=pathlib.Path.cwd();out=root/'artifacts/delivery';out.mkdir(parents=True,exist_ok=True)
ci=root/'artifacts/ci';ci.mkdir(parents=True,exist_ok=True)
tools=pathlib.Path(os.environ['ANDROID_HOME'])/'build-tools/35.0.0'
name='document-studio-0.8.4-preview';pkg='kr.documentstudio.openpreview';sha=os.environ['GITHUB_SHA']
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
    apk=root/'artifacts/mobile-build'/(abi+'.apk')
    signature=subprocess.check_output([str(tools/'apksigner'),'verify','--verbose','--print-certs',str(apk)],text=True)
    (ci/(abi+'-signature.txt')).write_text(signature)
    subprocess.run([str(tools/'zipalign'),'-c','-P','16','4',str(apk)],check=True)
    manifest=subprocess.check_output([str(tools/'aapt2'),'dump','badging',str(apk)],text=True)
    (ci/(abi+'-manifest.txt')).write_text(manifest)
    xml=subprocess.check_output([str(tools/'aapt2'),'dump','xmltree',str(apk),'--file','AndroidManifest.xml'],text=True)
    (ci/(abi+'-manifest-tree.txt')).write_text(xml)
    assert "name='"+pkg+"'" in manifest and "versionCode='14'" in manifest and "versionName='0.8.4-preview'" in manifest
    assert 'testOnly' not in xml and 'KoreanImeFixture' not in xml,xml
    permissions=subprocess.check_output([str(tools/'aapt2'),'dump','permissions',str(apk)],text=True)
    assert 'android.permission.INTERNET' not in permissions
    cert=re.findall(r'certificate SHA-256 digest: (.+)',signature);assert len(cert)==1
    with zipfile.ZipFile(apk) as z:
        assert z.testzip() is None
        for dex in z.namelist():
            if re.fullmatch(r'classes\d*\.dex',dex):
                assert b'Lkr/documentstudio/fixtures/KoreanImeFixture;' not in z.read(dex),'Test-only IME leaked into main APK'
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
    apks.append({'file':filename,'applicationId':pkg,'versionCode':14,'versionName':'0.8.4-preview','abi':abi,'bytes':apk.stat().st_size,
                 'sha256':digest(apk),'certificateSHA256':cert[0],'elf16k':True,'testOnly':False})
assert apks[0]['certificateSHA256']==apks[1]['certificateSHA256']
# Refuse a future incompatible update to this preview identity.
with urllib.request.urlopen('https://api.github.com/repos/Ch-wook/File_Translator/releases?per_page=50',timeout=60) as response: releases=json.load(response)
for release in releases:
    if release['draft'] or release['tag_name']=='v0.8.4-preview':continue
    for asset in release.get('assets',[]):
        if not asset['name'].endswith('-apk-metadata.json'):continue
        with urllib.request.urlopen(asset['browser_download_url'],timeout=60) as response: previous=json.load(response)
        for old in previous.get('apks',[]):
            if old['applicationId']==pkg:
                assert old['certificateSHA256']==apks[0]['certificateSHA256'],'Signing key differs from an existing preview. Configure the original secure STUDIO_KEYSTORE; do not publish an incompatible update.'
android=json.loads((ci/'android-summary.json').read_text())
assert os.environ['EMULATOR_OUTCOME']=='success' and android=={'tests':23,'failures':0,'errors':0,'skipped':0},android
install=json.loads((root/'artifacts/mobile-install/summary.json').read_text())
assert install['cleanInstallWithoutTestFlag'] and install['coexistsWith083'] and install['priorApkHashUnchanged'] and install['priorPrivateDataPreserved'] and install['sameSignerReinstallPreservedPrivateData']
mobile=json.loads((root/'artifacts/hangul-mobile/mobile-engine-results.json').read_text())
assert {r['format'] for r in mobile}=={'hwp','hwpx'} and len(mobile)==2,mobile
for r in mobile:
    assert r['partialSelection'] and r['savedAndReopened'] and r['autosave'] and r['atomicPasteUndoRedo'],r
    assert r['structure']['mergeSplitCycles']==3 and all(v is True or k=='mergeSplitCycles' for k,v in r['structure'].items()),r
old_browser=json.loads((root/'artifacts/document-engines/hangul-browser-results.json').read_text());assert len(old_browser)==5
js=(ci/'javascript.log').read_text()
js_counts={key:int(value) for key,value in re.findall(r'^(?:#|ℹ)\s+(tests|pass|fail|cancelled|skipped)\s+(\d+)\s*$',js,re.M)}
assert js_counts=={'tests':22,'pass':22,'fail':0,'cancelled':0,'skipped':0},(js_counts,js)
assert 'MOBILE_INDEPENDENT_ROUND_TRIP' in (ci/'roundtrip.log').read_text()
regression=json.loads((ci/'open-regression/summary.json').read_text())
assert regression=={'before':{'tests':3,'failures':3,'errors':0,'skipped':0},'after':{'tests':3,'failures':0,'errors':0,'skipped':0}},regression
evidence=root/'artifacts/native-android/native-document-open'
for item in ('metadata-free-hwp.json','metadata-free-hwpx.json','distribution-read-only.json'):
    proof=json.loads((evidence/item).read_text())
    assert all(v is True or k=='originalSha256' for k,v in proof.items()),proof
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
    subprocess.run(['git','diff','--binary','244b94507d8bda5dbf248877f17ed88b745035f2','HEAD'],stdout=f,check=True)
subprocess.run(['git','apply','--check','--reverse',str(out/(name+'-changes.patch'))],check=True)
subprocess.run(['git','bundle','create',str(out/(name+'-history.bundle')),'--all'],check=True)
subprocess.run(['git','bundle','verify',str(out/(name+'-history.bundle'))],check=True)
metadata={'commit':sha,'physicalDeviceTested':False,'apks':apks}
(out/(name+'-apk-metadata.json')).write_text(json.dumps(metadata,indent=2))
(ci/'apk-engine-assets.json').write_text(json.dumps(engine_assets,indent=2))
summary={'commit':sha,'version':'0.8.4-preview','applicationId':pkg,'androidEmulatorOutcome':os.environ['EMULATOR_OUTCOME'],
         'androidTests':android,'unitTests':reports,'installation':install,'mobileEngineTests':mobile,'originalBrowserScenarios':len(old_browser),'javascriptTests':22,'independentPartialFormattingParser':True,'physicalDeviceTested':False,'apks':apks}
(ci/'summary.json').write_text(json.dumps(summary,indent=2))
with zipfile.ZipFile(out/(name+'-verification.zip'),'w',zipfile.ZIP_DEFLATED) as z:
    for folder in ('artifacts/open-fixtures','artifacts/ci','artifacts/mobile-install','artifacts/hangul-mobile','artifacts/native-android','artifacts/document-engines',
                   'core/build/test-results','officeruntime/build/test-results','app/build/test-results','app/build/outputs/androidTest-results','app/build/reports','core/build/visual-fixtures'):
        for p in (root/folder).rglob('*'):
            if p.is_file():z.write(p,p.relative_to(root))
(out/'README.txt').write_text('문서 스튜디오 열기 수정 0.8.4-preview\n\n휴대폰: '+name+'-arm64-v8a.apk\nx86_64 APK는 에뮬레이터/해당 아키텍처용입니다. 각각 독립 실행 가능한 단일 APK입니다.\nAndroid 10+ 64비트, Office는 Android 11+가 필요합니다.\n\n기존 서명키를 확보하지 못했으므로 새 앱 ID kr.documentstudio.openpreview로 제공합니다. 기존 0.8.3 앱 kr.documentstudio.mobilepreview 및 이전 앱을 삭제할 필요가 없습니다. 앱 이름은 문서 스튜디오 열기 수정입니다. 기존 문서·필기 ZIP 백업은 새 앱의 복원 기능으로 가져올 수 있습니다.\n\n수정: 파일 제공자가 이름/MIME 조회를 지원하지 않아도 읽을 수 있는 문서 바이트로 형식을 판별합니다. 배포용 HWP는 원래 제한을 유지한 읽기 전용으로 열고 편집·변경 저장을 차단합니다. 암호화·손상 문서를 허용하도록 검사를 해제하지 않았습니다.\n\n검증 결과는 verification.zip을 확인하세요. Android 35 x86_64에서 설치·기존 앱 보존·재설치, HWP/HWPX 외부 열기와 배포용 HWP 보호를 포함한 Android 23건 및 기존 편집·저장 후 재열기 검사를 요구합니다. 실제 휴대폰 검증은 미완료입니다. ARM64는 APK/ELF/엔진 자산 정적 검사를 수행했습니다.\n\n파일별 미지원 요소와 보호 제한은 docs/HANGUL_MOBILE_EDITING.md, 이번 수정의 원인·검증은 docs/DOCUMENT_OPEN_FIX.md를 확인하세요. 사용자의 원본 문제 파일은 제공되지 않았으므로 모든 열기 오류가 해결됐다는 의미는 아닙니다.\n개발 서명 프리뷰입니다. 이전 미푸시 0.8.0 복구본이 아닌 원격 소스에서 이어 작업했습니다.\n\n빌드 커밋: '+sha+'\n')
(out/'SHA256SUMS').write_text(''.join(digest(p)+'  '+p.name+'\n' for p in sorted(out.iterdir()) if p.is_file()))
print('MOBILE_RELEASE_VERIFIED',json.dumps(summary),flush=True)
