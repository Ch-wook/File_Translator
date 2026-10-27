#!/usr/bin/env python3
"""Verify both ABI packages, full Android results and their corresponding source archive."""
import hashlib,json,os,pathlib,re,shutil,struct,subprocess,urllib.request,zipfile,xml.etree.ElementTree as ET
root=pathlib.Path.cwd();out=root/'artifacts/delivery';out.mkdir(parents=True,exist_ok=True)
ci=root/'artifacts/ci';ci.mkdir(parents=True,exist_ok=True)
tools=pathlib.Path(os.environ['ANDROID_HOME'])/'build-tools/35.0.0'
name='document-studio-0.8.5-preview';pkg='kr.documentstudio.compactpreview';sha=os.environ['GITHUB_SHA']
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
    assert "name='"+pkg+"'" in manifest and "versionCode='15'" in manifest and "versionName='0.8.5-preview'" in manifest
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
        for shared in (root/'shared/editor-ui/dist').glob('*'):
            if shared.is_file():
                assert z.read('assets/dist/'+shared.name)==shared.read_bytes()
                assert z.read('assets/hangul/'+shared.name)==shared.read_bytes()
        assert z.read('assets/unpack/user/fonts/NotoSansKR.ttf')==(root/'app/src/main/assets/fonts/NotoSansKR.ttf').read_bytes()
        assert z.read('assets/dist/studio-policy.js')==(root/'officeruntime/src/main/assets/dist/studio-policy.js').read_bytes()
    filename=name+'-'+abi+'.apk';shutil.copy2(apk,out/filename)
    apks.append({'file':filename,'applicationId':pkg,'versionCode':15,'versionName':'0.8.5-preview','abi':abi,'bytes':apk.stat().st_size,
                 'sha256':digest(apk),'certificateSHA256':cert[0],'elf16k':True,'testOnly':False})
assert apks[0]['certificateSHA256']==apks[1]['certificateSHA256']
# Refuse a future incompatible update to this preview identity.
with urllib.request.urlopen('https://api.github.com/repos/Ch-wook/File_Translator/releases?per_page=50',timeout=60) as response: releases=json.load(response)
for release in releases:
    if release['draft'] or release['tag_name']=='v0.8.5-preview':continue
    for asset in release.get('assets',[]):
        if not asset['name'].endswith('-apk-metadata.json'):continue
        with urllib.request.urlopen(asset['browser_download_url'],timeout=60) as response: previous=json.load(response)
        for old in previous.get('apks',[]):
            if old['applicationId']==pkg:
                assert old['certificateSHA256']==apks[0]['certificateSHA256'],'Signing key differs from an existing preview. Configure the original secure STUDIO_KEYSTORE; do not publish an incompatible update.'
android=json.loads((ci/'android-summary.json').read_text())
assert os.environ['EMULATOR_OUTCOME']=='success' and android=={'tests':27,'failures':0,'errors':0,'skipped':0},android
install=json.loads((root/'artifacts/mobile-install/summary.json').read_text())
assert install['cleanInstallWithoutTestFlag'] and install['coexistsWith084'] and install['priorApkHashUnchanged'] and install['priorPrivateDataPreserved'] and install['sameSignerReinstallPreservedPrivateData']
mobile=json.loads((root/'artifacts/hangul-mobile/mobile-engine-results.json').read_text())
assert {r['format'] for r in mobile}=={'hwp','hwpx'} and len(mobile)==2,mobile
for r in mobile:
    assert r['partialSelection'] and r['savedAndReopened'] and r['autosave'] and r['atomicPasteUndoRedo'] and r['pendingFormatSaved'],r
    assert r['structure']['mergeSplitCycles']==3 and all(v is True or k=='mergeSplitCycles' for k,v in r['structure'].items()),r
old_browser=json.loads((root/'artifacts/document-engines/hangul-browser-results.json').read_text());assert len(old_browser)==5
js=(ci/'javascript.log').read_text()
js_counts={key:int(value) for key,value in re.findall(r'^(?:#|ℹ)\s+(tests|pass|fail|cancelled|skipped)\s+(\d+)\s*$',js,re.M)}
assert js_counts=={'tests':20,'pass':20,'fail':0,'cancelled':0,'skipped':0},(js_counts,js)
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
layout=json.loads((root/'artifacts/mobile-ui/compact-web-metrics.json').read_text())
assert len(layout)==3
for sample in layout:
    assert sample['closed']['header']['height']==48 and sample['closed']['#edit-dock']['height']==48
    assert sample['closed']['#engine']['height']==sample['viewport']['height']-96
font_result=json.loads((root/'artifacts/native-android/native-office-font/summary.json').read_text())
assert all(font_result[k] is True for k in ['selectedNativeFont','independentSizeAndColor','unselectedPreserved','nativeSaved','parserReopened','engineReopened','originalProtected'])
metadata={'commit':sha,'physicalDeviceTested':False,'apks':apks}
(out/(name+'-apk-metadata.json')).write_text(json.dumps(metadata,indent=2))
(ci/'apk-engine-assets.json').write_text(json.dumps(engine_assets,indent=2))
summary={'commit':sha,'version':'0.8.5-preview','applicationId':pkg,'androidEmulatorOutcome':os.environ['EMULATOR_OUTCOME'],
         'androidTests':android,'unitTests':reports,'installation':install,'mobileEngineTests':mobile,'originalBrowserScenarios':len(old_browser),'javascriptTests':20,'independentPartialFormattingParser':True,'physicalDeviceTested':False,'apks':apks}
(ci/'summary.json').write_text(json.dumps(summary,indent=2))
with zipfile.ZipFile(out/(name+'-verification.zip'),'w',zipfile.ZIP_DEFLATED) as z:
    for folder in ('artifacts/open-fixtures','artifacts/ci','artifacts/mobile-install','artifacts/hangul-mobile','artifacts/mobile-ui','artifacts/native-android','artifacts/document-engines',
                   'core/build/test-results','officeruntime/build/test-results','app/build/test-results','app/build/outputs/androidTest-results','app/build/reports','core/build/visual-fixtures'):
        for p in (root/folder).rglob('*'):
            if p.is_file():z.write(p,p.relative_to(root))
(out/'README.txt').write_text("문서 스튜디오 모바일 UI 0.8.5-preview\n\n휴대폰: document-studio-0.8.5-preview-arm64-v8a.apk\nx86_64 APK는 해당 아키텍처와 에뮬레이터용입니다. Android 10+ 64비트, Office는 Android 11+입니다.\n\n기존 앱의 서명키가 없어 별도 프리뷰 ID kr.documentstudio.compactpreview로 제공합니다. 기존 0.8.4 앱과 문서를 삭제하지 마세요. 새 프로젝트가 아니라 동일 저장소와 문서 엔진을 개선한 빌드입니다. 앱 이름은 문서 스튜디오 모바일 UI입니다.\n\n상단 48px 한 줄과 하단 48px 한 줄, 닫을 수 있는 상세 패널, 실제 글꼴 검색·미리보기·최근 목록을 적용했습니다. HWP/HWPX, DOC/DOCX, XLS/XLSX, PPT/PPTX, PDF, TXT, Markdown에 형식별 기능을 연결했습니다. 지원하지 않는 HTML 편집기는 추가하지 않았습니다.\n\nHWP/HWPX 자간·장평은 실제 서식으로 저장합니다. Office 고급 자간·장평은 네이티브 글자 모양 대화상자를 사용합니다. TXT/Markdown의 보기 글꼴은 파일 서식이 아닙니다. PDF 기존 본문은 원래 글꼴을 보존하며 글꼴 교체·문단 재배치는 지원하지 않습니다. 이미지 자르기·교체 등 확인하지 않은 고급 개체 기능은 추가하지 않았습니다.\n\nAndroid 35 x86_64 에뮬레이터에서 설치·기존 앱 보존·27개 기기 테스트·저장 재열기를 검증합니다. 실제 ARM64 휴대폰, 삼성/Gboard 키보드는 미검증입니다. ARM64 APK는 서명·패키지·ELF·자산 무결성 검사를 수행합니다. 자세한 결과와 제한은 소스의 docs/COMMON_MOBILE_UI_RESULTS.md 및 verification.zip을 확인하세요.\n\n개발 서명 프리뷰입니다. 이전 미푸시 0.8.0 원본 복구본이 아닙니다.\n\n빌드 커밋: "+sha+'\n')
(out/'SHA256SUMS').write_text(''.join(digest(p)+'  '+p.name+'\n' for p in sorted(out.iterdir()) if p.is_file()))
print('MOBILE_RELEASE_VERIFIED',json.dumps(summary),flush=True)
