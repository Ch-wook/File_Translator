#!/usr/bin/env python3
"""Verify both ABI packages, full Android results and their corresponding source archive."""
import hashlib,json,os,pathlib,re,shutil,struct,subprocess,urllib.request,zipfile,xml.etree.ElementTree as ET
root=pathlib.Path.cwd();out=root/'artifacts/delivery';out.mkdir(parents=True,exist_ok=True)
ci=root/'artifacts/ci';ci.mkdir(parents=True,exist_ok=True)
tools=pathlib.Path(os.environ['ANDROID_HOME'])/'build-tools/35.0.0'
name='document-studio-0.8.11-preview';pkg='kr.documentstudio.qualitypreview';sha=os.environ.get('STUDIO_APK_BUILD_SHA',os.environ['GITHUB_SHA'])
# Recovery may add only delivery metadata; the preserved app code must be identical.
source_sha=os.environ['GITHUB_SHA']
if sha!=source_sha:
    subprocess.run(['git','merge-base','--is-ancestor',sha,'HEAD'],check=True)
    changes=subprocess.check_output(['git','diff','--name-only',sha,'HEAD'],text=True).splitlines()
    assert all(p=='README.md' or p=='scripts/package-mobile-release.py' or p.startswith(('docs/','.github/workflows/')) for p in changes),changes
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
    assert "name='"+pkg+"'" in manifest and "versionCode='21'" in manifest and "versionName='0.8.11-preview'" in manifest
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
    apks.append({'file':filename,'applicationId':pkg,'versionCode':21,'versionName':'0.8.11-preview','abi':abi,'bytes':apk.stat().st_size,
                 'sha256':digest(apk),'certificateSHA256':cert[0],'elf16k':True,'testOnly':False})
assert apks[0]['certificateSHA256']==apks[1]['certificateSHA256']
# Refuse a future incompatible update to this preview identity.
releases=json.loads(subprocess.check_output(['gh','api','repos/Ch-wook/File_Translator/releases?per_page=50'],text=True))
for release in releases:
    if release['draft'] or release['tag_name']=='v0.8.11-preview':continue
    for asset in release.get('assets',[]):
        if not asset['name'].endswith('-apk-metadata.json'):continue
        with urllib.request.urlopen(asset['browser_download_url'],timeout=60) as response: previous=json.load(response)
        for old in previous.get('apks',[]):
            if old['applicationId']==pkg:
                assert old['certificateSHA256']==apks[0]['certificateSHA256'],'Signing key differs from an existing preview. Configure the original secure STUDIO_KEYSTORE; do not publish an incompatible update.'
android=json.loads((ci/'android-summary.json').read_text())
assert os.environ['EMULATOR_OUTCOME']=='success' and android=={'tests':66,'failures':0,'errors':0,'skipped':0},android
install=json.loads((root/'artifacts/mobile-install/summary.json').read_text())
assert install['cleanInstallWithoutTestFlag'] and install['coexistsWith0810'] and install['priorApkHashUnchanged'] and install['priorPrivateDataPreserved'] and install['sameSignerReinstallPreservedPrivateData']
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
    subprocess.run(['git','diff','--binary','aa3414f1fae89f5f98d10dabb64df85bfd7fc1df','HEAD'],stdout=f,check=True)
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
conversion=root/'artifacts/native-android/native-conversion'
for filename in ('pptx.json','docx.json','xlsx.json','portable.json','signature.json','office-publish-recovery.json'):
    proof=json.loads((conversion/filename).read_text())
    assert all(value is True or key=='pages' for key,value in proof.items()),proof
signatures=json.loads((root/'artifacts/signature-conversion/signature-results.json').read_text())
assert len(signatures)==2 and all(r['savedAndReopened'] and r['tableStructurePreserved'] and r['damagedLibraryBlocked'] and r['failedLibraryPersistenceRetainsVisibleAndSavedEntries'] for r in signatures)
metadata={'commit':sha,'sourceArchiveCommit':source_sha,'physicalDeviceTested':False,'apks':apks}
(out/(name+'-apk-metadata.json')).write_text(json.dumps(metadata,indent=2))
(ci/'apk-engine-assets.json').write_text(json.dumps(engine_assets,indent=2))
summary={'commit':sha,'sourceArchiveCommit':source_sha,'version':'0.8.11-preview','applicationId':pkg,'androidEmulatorOutcome':os.environ['EMULATOR_OUTCOME'],
         'androidTests':android,'signatureConversionVerified':True,'signatureEngineTests':signatures,'unitTests':reports,'installation':install,'mobileEngineTests':mobile,'originalBrowserScenarios':len(old_browser),'javascriptTests':28,'independentPartialFormattingParser':True,'physicalDeviceTested':False,'nativeCalc':calc_result,'additionalBundledFontFamilies':6,'apks':apks}
(ci/'summary.json').write_text(json.dumps(summary,indent=2))
with zipfile.ZipFile(out/(name+'-verification.zip'),'w',zipfile.ZIP_DEFLATED) as z:
    for folder in ('artifacts/open-fixtures','artifacts/ci','artifacts/mobile-install','artifacts/hangul-mobile','artifacts/signature-conversion','artifacts/mobile-ui','artifacts/native-android','artifacts/document-engines',
                   'core/build/test-results','officeruntime/build/test-results','app/build/test-results','app/build/outputs/androidTest-results','app/build/reports','core/build/visual-fixtures'):
        for p in (root/folder).rglob('*'):
            if p.is_file():z.write(p,p.relative_to(root))
(out/'README.txt').write_text("문서 스튜디오 품질 개선 0.8.11-preview\n\n휴대폰: document-studio-0.8.11-preview-arm64-v8a.apk\nx86_64는 해당 아키텍처·에뮬레이터용입니다. Android10+ 64비트, Office는Android11+입니다.\n\n별도 프리뷰: 문서 스튜디오 품질 개선 / kr.documentstudio.qualitypreview. 기존 서명키를 사용할 수 없어 기존 앱·비공개 데이터를 보존합니다. 이전 앱을 삭제하지 마세요. 저장·백업한 파일로 이동할 수 있으며 내 서명·복구본은 자동 이관하지 않습니다.\n\n문서·PDF·백업·변환 저장에 공통 저장 저널을 적용했습니다. 중단된 저장의 이전 원본·최신 편집본을 저장 복구에서 각각 새 파일로 보관할 수 있습니다. 자동 복구본은 원본 저장과 별개입니다. SAF에는 외부 제공자 전체에 통하는 원자적 교체·동시 앱 잠금·클라우드 영구 반영 보장이 없습니다.\n\n손상된 서명 보관함을 빈 목록으로 덮어쓰지 않습니다. 실제 PNG·크기·획 검증, 이전 정상본 복원·자료 내보내기를 추가했습니다. PNG 자필 서명이며 인증서 전자서명이 아닙니다.\n\n최근 원본 재확인, 최근 목록 숨기기·복원·정렬, 큰 글자·작은 화면 배치, 시스템 어두운 색상, 안전한 오류 안내·로컬 진단을 추가했습니다. 변환 결과의 실제 형식을 다음 변환 설정과 분리하고 저장 중 중복·취소를 제한했습니다. 기존 7개 형식의 편집·서명·변환·저장 엔진을 유지합니다.\n\n앱은 INTERNET 권한을 요청하지 않습니다. Google ML Kit·운영체제의 개인정보 동작과 전체 라이선스·보안은 별도 검토 대상입니다. 진단에는 문서명·경로·예외 원문을 기록하지 않습니다. 내부 저장소 전체를 별도 암호화하지는 않습니다.\n\n한글 PDF는 페이지 이미지 방식입니다. OCR·편집 가능한 PDF→Word·DOCX↔HWPX·직접 벡터 필기·일괄 변환은 이번에 추가하지 않았습니다. 실제 ARM64 휴대폰·TalkBack·S펜·장시간/프레임 성능·정식 서명·스토어 검증은 미완료입니다. 상용 출시 완료가 아닌 개발 서명 프리뷰이며, 과거 미푸시0.8.0 원본 복구본이 아닙니다.\n\n통과·실패·성능 수치·보안 제한은 work-report.md와 verification.zip을 확인하세요.\n빌드 커밋: "+sha+'\n')
(out/'SHA256SUMS').write_text(''.join(digest(p)+'  '+p.name+'\n' for p in sorted(out.iterdir()) if p.is_file()))
print('MOBILE_RELEASE_VERIFIED',json.dumps(summary),flush=True)
