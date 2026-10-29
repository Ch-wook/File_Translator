#!/usr/bin/env python3
"""Limited release checks and public-coordinate OSV lookup. Not a security certification."""
import datetime,hashlib,json,os,pathlib,re,subprocess,time,urllib.request,zipfile
root=pathlib.Path.cwd();out=root/'artifacts/quality-security';out.mkdir(parents=True,exist_ok=True)
patterns={'github-token':rb'(?:ghp_[A-Za-z0-9]{36}|github_pat_[A-Za-z0-9_]{50,})',
          'private-key':rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----','aws-access-key':rb'AKIA[A-Z0-9]{16}'}
findings=[]
for name in subprocess.check_output(['git','ls-files','-z']).split(b'\0'):
    if not name:continue
    p=root/os.fsdecode(name)
    if not p.is_file() or p.stat().st_size>4*1024*1024:continue
    data=p.read_bytes()
    if b'\0' in data:continue
    for kind,pattern in patterns.items():
        for hit in re.finditer(pattern,data):findings.append({'path':os.fsdecode(name),'line':data[:hit.start()].count(b'\n')+1,'kind':kind})
(out/'credential-patterns.json').write_text(json.dumps({'patterns':list(patterns),'findings':findings},indent=2))
assert not findings,findings
tool=pathlib.Path(os.environ['ANDROID_HOME'])/'build-tools/35.0.0/aapt2'
apk=root/'app/build/outputs/apk/release/app-release-unsigned.apk'
assert apk.is_file() and apk.stat().st_size>100000000
manifest=subprocess.check_output([str(tool),'dump','xmltree',str(apk),'--file','AndroidManifest.xml'],text=True)
permissions=subprocess.check_output([str(tool),'dump','permissions',str(apk)],text=True)
(out/'release-manifest.txt').write_text(manifest);(out/'release-permissions.txt').write_text(permissions)
assert 'android.permission.INTERNET' not in permissions
for test in ('SaveCrashFixtureService','FailingProvider','HangulProbeProvider','OfficeProbeProvider','DocumentOpenFixtureProvider','KoreanImeFixture'):
    assert test not in manifest,test
assert not re.search(r'debuggable[^\n]*(?:0xffffffff|true)',manifest)
assert 'allowBackup' in manifest and '0x0' in manifest
with zipfile.ZipFile(apk) as z:
    assert z.testzip() is None
    dex=[z.read(p) for p in z.namelist() if re.fullmatch(r'classes\d*\.dex',p)]
    for test in (b'Lkr/documentstudio/SaveCrashFixtureService;',b'Lkr/documentstudio/FailingProvider;',b'Lkr/documentstudio/fixtures/KoreanImeFixture;'):
        assert all(test not in b for b in dex),test
inventory=root/'app/build/quality/release-runtime-maven.txt'
modules=[line.strip().split(':') for line in inventory.read_text().splitlines() if line.strip()]
assert len(modules)>20 and all(len(row)==3 for row in modules)
queries=[{'package':{'ecosystem':'Maven','name':g+':'+a},'version':v} for g,a,v in modules]
advisories=[];query_error=None
def request(url,payload=None):
    data=None if payload is None else json.dumps(payload).encode()
    for attempt in range(3):
        try:
            req=urllib.request.Request(url,data=data,headers={'Content-Type':'application/json','User-Agent':'DocumentStudio-public-coordinate-audit'})
            with urllib.request.urlopen(req,timeout=60) as response:return json.load(response)
        except Exception:
            if attempt==2:raise
            time.sleep(2)
try:
    for offset in range(0,len(queries),100):
        batch=queries[offset:offset+100];result=request('https://api.osv.dev/v1/querybatch',{'queries':batch})
        assert len(result['results'])==len(batch)
        for query,matches in zip(batch,result['results']):
            for item in matches.get('vulns',[]):
                detail=request('https://api.osv.dev/v1/vulns/'+item['id'])
                advisories.append({'module':query['package']['name'],'version':query['version'],'id':item['id'],
                    'aliases':detail.get('aliases',[]),'summary':detail.get('summary',''),'severity':detail.get('severity',[]),
                    'withdrawn':detail.get('withdrawn'),'references':detail.get('references',[]),'affected':detail.get('affected',[])})
except Exception as error:query_error=type(error).__name__
coverage={'timeUTC':datetime.datetime.now(datetime.timezone.utc).isoformat(),'mavenModules':len(modules),
    'queryCompleted':query_error is None,'queryFailure':query_error,'advisories':advisories,
    'coverageLimit':'Resolved release Maven coordinates only. Local hwplib patch, bundled Collabora native code, Rhwp/Wasm, CanvasKit, fonts, OS/SDK behavior need separate review.'}
(out/'dependency-advisories.json').write_text(json.dumps(coverage,ensure_ascii=False,indent=2))
(out/'release-runtime-maven.txt').write_text(inventory.read_text())
result={'releaseUnsignedBuild':True,'releaseDebugFixturesAbsent':True,'releaseInternetPermission':False,
    'credentialPatternFindings':len(findings),'runtimeMavenModules':len(modules),'advisoryQueryCompleted':query_error is None,
    'advisoryMatches':len(advisories),'commercialReady':False,'unsignedAPKBytes':apk.stat().st_size,
    'blockers':['Physical ARM64/device and TalkBack validation','Stable production signing and store/account setup','Full native/Wasm/advisory triage','PDFBox Android 2.0.27 security and malformed-file review','Privacy/ML Kit/system behavior and legal review','Crash/ANR and frame-time field evidence']}
(out/'summary.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
print('QUALITY_SECURITY_AUDIT',json.dumps(result,ensure_ascii=False),flush=True)
for item in advisories:print('PUBLIC_DEPENDENCY_ADVISORY',item['module'],item['version'],item['id'],item['summary'][:400],flush=True)
assert query_error is None,query_error
