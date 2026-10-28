#!/usr/bin/env python3
"""Verify both ABI packages, full Android results and their corresponding source archive."""
import hashlib,json,os,pathlib,re,shutil,struct,subprocess,urllib.request,zipfile,xml.etree.ElementTree as ET
root=pathlib.Path.cwd();out=root/'artifacts/delivery';out.mkdir(parents=True,exist_ok=True)
ci=root/'artifacts/ci';ci.mkdir(parents=True,exist_ok=True)
tools=pathlib.Path(os.environ['ANDROID_HOME'])/'build-tools/35.0.0'
name='document-studio-0.8.7-preview';pkg='kr.documentstudio.workspacepreview';sha=os.environ['GITHUB_SHA']
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
    assert "name='"+pkg+"'" in manifest and "versionCode='17'" in manifest and "versionName='0.8.7-preview'" in manifest
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
        font_manifest=json.loads((root/'config/studio-fonts.json').read_text())
        checked_fonts=[]
        for family in font_manifest['families']:
            for item in family['files']:
                filename=item['filename']
                folder='font-licenses' if filename.endswith('.txt') else 'fonts'
                data=(root/'.engine-cache/studio-fonts/assets'/folder/filename).read_bytes()
                assert len(data)==item['size'] and hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()==item['gitBlob']
                assert z.read('assets/'+folder+'/'+filename)==data
                if folder=='fonts':
                    assert z.read('assets/unpack/user/fonts/'+filename)==data
                    checked_fonts.append(dict(family=family['family'],file=filename,sha256=hashlib.sha256(data).hexdigest()))
        assert len(checked_fonts)==15
        (ci/('bundled-fonts-'+abi+'.json')).write_text(json.dumps(checked_fonts,indent=2))
        assert z.read('assets/unpack/user/fonts/NotoSansKR.ttf')==(root/'app/src/main/assets/fonts/NotoSansKR.ttf').read_bytes()
        assert z.read('assets/dist/studio-policy.js')==(root/'officeruntime/src/main/assets/dist/studio-policy.js').read_bytes()
    filename=name+'-'+abi+'.apk';shutil.copy2(apk,out/filename)
    apks.append({'file':filename,'applicationId':pkg,'versionCode':17,'versionName':'0.8.7-preview','abi':abi,'bytes':apk.stat().st_size,
                 'sha256':digest(apk),'certificateSHA256':cert[0],'elf16k':True,'testOnly':False})
assert apks[0]['certificateSHA256']==apks[1]['certificateSHA256']
# Refuse a future incompatible update to this preview identity.
with urllib.request.urlopen('https://api.github.com/repos/Ch-wook/File_Translator/releases?per_page=50',timeout=60) as response: releases=json.load(response)
for release in releases:
    if release['draft'] or release['tag_name']=='v0.8.7-preview':continue
    for asset in release.get('assets',[]):
        if not asset['name'].endswith('-apk-metadata.json'):continue
        with urllib.request.urlopen(asset['browser_download_url'],timeout=60) as response: previous=json.load(response)
        for old in previous.get('apks',[]):
            if old['applicationId']==pkg:
                assert old['certificateSHA256']==apks[0]['certificateSHA256'],'Signing key differs from an existing preview. Configure the original secure STUDIO_KEYSTORE; do not publish an incompatible update.'
android=json.loads((ci/'android-summary.json').read_text())
assert os.environ['EMULATOR_OUTCOME']=='success' and android=={'tests':39,'failures':0,'errors':0,'skipped':0},android
install=json.loads((root/'artifacts/mobile-install/summary.json').read_text())
assert install['cleanInstallWithoutTestFlag'] and install['coexistsWith086'] and install['priorApkHashUnchanged'] and install['priorPrivateDataPreserved'] and install['sameSignerReinstallPreservedPrivateData']
mobile=json.loads((root/'artifacts/hangul-mobile/mobile-engine-results.json').read_text())
assert {r['format'] for r in mobile}=={'hwp','hwpx'} and len(mobile)==2,mobile
for r in mobile:
    assert r['partialSelection'] and r['savedAndReopened'] and r['autosave'] and r['atomicPasteUndoRedo'] and r['pendingFormatSaved'],r
    assert r['structure']['mergeSplitCycles']==3 and all(v is True or k=='mergeSplitCycles' for k,v in r['structure'].items()),r
old_browser=json.loads((root/'artifacts/document-engines/hangul-browser-results.json').read_text());assert len(old_browser)==5
js=(ci/'javascript.log').read_text()
js_counts={key:int(value) for key,value in re.findall(r'^(?:#|ℹ)\s+(tests|pass|fail|cancelled|skipped)\s+(\d+)\s*$',js,re.M)}
assert js_counts=={'tests':28,'pass':28,'fail':0,'cancelled':0,'skipped':0},(js_counts,js)
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
    subprocess.run(['git','diff','--binary','a7c76a8f6f9b5d9e06817ea2c0089dd17a2d00b4','HEAD'],stdout=f,check=True)
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
assert font_result['independentBoldItalicUnderline'] is True and font_result['bundledFamilies']==6
calc_result=json.loads((root/'artifacts/native-android/native-office-font/calc-summary.json').read_text())
assert calc_result['nativeFormulaResults']==15 and all(calc_result[k] is True for k in ['nativePaste','nativeCellFontBoldSize','nativeNewSheet','providerSaved','parserReopened','engineReopened','originalProtected']),calc_result
metadata={'commit':sha,'physicalDeviceTested':False,'apks':apks}
(out/(name+'-apk-metadata.json')).write_text(json.dumps(metadata,indent=2))
(ci/'apk-engine-assets.json').write_text(json.dumps(engine_assets,indent=2))
summary={'commit':sha,'version':'0.8.7-preview','applicationId':pkg,'androidEmulatorOutcome':os.environ['EMULATOR_OUTCOME'],
         'androidTests':android,'unitTests':reports,'installation':install,'mobileEngineTests':mobile,'originalBrowserScenarios':len(old_browser),'javascriptTests':28,'independentPartialFormattingParser':True,'physicalDeviceTested':False,'nativeCalc':calc_result,'additionalBundledFontFamilies':6,'apks':apks}
(ci/'summary.json').write_text(json.dumps(summary,indent=2))
with zipfile.ZipFile(out/(name+'-verification.zip'),'w',zipfile.ZIP_DEFLATED) as z:
    for folder in ('artifacts/open-fixtures','artifacts/ci','artifacts/mobile-install','artifacts/hangul-mobile','artifacts/mobile-ui','artifacts/native-android','artifacts/document-engines',
                   'core/build/test-results','officeruntime/build/test-results','app/build/test-results','app/build/outputs/androidTest-results','app/build/reports','core/build/visual-fixtures'):
        for p in (root/folder).rglob('*'):
            if p.is_file():z.write(p,p.relative_to(root))
(out/'README.txt').write_text("""문서 스튜디오 작업 도구 0.8.7-preview

휴대폰: document-studio-0.8.7-preview-arm64-v8a.apk
x86_64 APK는 해당 아키텍처와 에뮬레이터용입니다. Android 10+ 64비트, Office는 Android 11+입니다.

기존 서명키가 없어 별도 프리뷰 ID kr.documentstudio.workspacepreview로 제공합니다. 기존 0.8.6 앱과 문서를 삭제하지 마세요. 같은 프로젝트의 개선 빌드이며 앱 이름은 '문서 스튜디오 작업 도구'입니다. 기존 앱의 비공개 작업 복구본은 자동 이관하지 않습니다. 기존 앱에서 다른 이름으로 저장한 문서를 새 앱에서 열 수 있습니다.

나눔고딕(NanumGothic)·나눔명조(NanumMyeongjo)·고운돋움·고운바탕·Lato·Cousine의 실제 글꼴 파일과 OFL 라이선스를 추가했습니다. 엔진이 실제 제공하는 글꼴만 목록에 표시합니다. 검색·미리보기·최근·즐겨찾기를 제공합니다. 맑은 고딕·Arial 등 별도 배포 권한이 없는 글꼴을 포함한 것으로 표시하지 않습니다.

파일별 하단 도구를 나누고 상단/하단을 각각 48px 한 줄로 유지합니다. Excel 셀·수식·시트, PowerPoint 슬라이드·도형·이미지, PDF 선택·주석·텍스트·페이지, Markdown 문법·미리보기, TXT 편집·찾기·보기 설정으로 접근합니다. 상세 설정은 필요할 때만 엽니다.

TXT·Markdown 글꼴은 화면 표시 설정이며 파일 서식이 아닙니다. Markdown 굵게·기울임·취소선의 해제, 제목 1~6, 목록 전환·번호, 이미지 링크·구분선은 실제 원문 문법으로 저장합니다. TXT 찾기·바꾸기, 줄 번호·자동 줄바꿈·통계를 보완했습니다. PDF 기존 글꼴 교체·문단 재배치와 검증하지 않은 고급 편집을 지원한다고 표시하지 않습니다.

원본과 작업 복사본을 분리합니다. 자동 저장은 작업 복구본 보존이며 외부 원본 저장 완료와 다릅니다. 원본 반영은 저장 동작과 성공 확인이 필요합니다.

Android 35 x86_64 에뮬레이터에서 39개 테스트와 기존 앱 보존 설치·동일 APK 재설치를 검증합니다. Word 선택 서식의 독립 적용, Excel 실제 붙여넣기·15개 수식 결과·셀 서식·새 시트, PowerPoint 슬라이드 추가와 텍스트 서식, PDF 주석·이미지·필기, Markdown·TXT 저장과 재열기를 포함합니다. HWP/HWPX는 실제 브라우저 엔진, 독립 파서, Android 입력 테스트를 수행합니다.

실제 ARM64 휴대폰과 삼성/Gboard 키보드는 미검증입니다. ARM64 APK는 빌드·서명·패키지·16KiB ELF/ZIP 정렬·자산 검사를 수행합니다. 모든 고급 기능이나 모든 기존 문서의 무손실 저장을 보장한 결과가 아닙니다. 추가 변경: PowerPoint 슬라이드 목록·이동·삭제 확인, Excel 셀 입력 완료·취소와 숨겨진 시트/마지막 표시 시트 보호. 상세 범위는 docs/WORKSPACE_TOOLS_RESULTS.md와 verification.zip을 확인하세요.

개발 서명 프리뷰입니다. 이전 미푸시 0.8.0 원본 복구본이 아닙니다.

빌드 커밋: """+sha+'\n')
(out/'SHA256SUMS').write_text(''.join(digest(p)+'  '+p.name+'\n' for p in sorted(out.iterdir()) if p.is_file()))
print('MOBILE_RELEASE_VERIFIED',json.dumps(summary),flush=True)
