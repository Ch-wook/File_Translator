#!/usr/bin/env python3
"""Inspect published APKs and record actual PackageManager installation outcomes."""
import hashlib,json,os,pathlib,re,struct,subprocess,sys,urllib.request,zipfile,shutil
ROOT=pathlib.Path.cwd(); OUT=ROOT/'artifacts/install'; OUT.mkdir(parents=True,exist_ok=True)
DOWNLOADS=ROOT/'artifacts/install-apks'; DOWNLOADS.mkdir(parents=True,exist_ok=True)
VERSIONS={'081':'https://github.com/Ch-wook/File_Translator/releases/download/v0.8.1-preview/document-studio-0.8.1-preview-debug.apk',
          '060':'https://github.com/Ch-wook/File_Translator/releases/download/v0.6.0/document-studio-release.apk'}
def command(args,name,expected=None):
    p=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
    (OUT/(name+'.txt')).write_text(p.stdout)
    print(name,'EXIT',p.returncode,p.stdout[:5000],flush=True)
    if expected is not None: assert p.returncode==expected,(name,p.stdout)
    return p
def inspect():
    tools=pathlib.Path(os.environ['ANDROID_HOME'])/'build-tools/35.0.0'
    reports={}
    for version,url in VERSIONS.items():
        apk=DOWNLOADS/(version+'.apk')
        with urllib.request.urlopen(url,timeout=120) as src,apk.open('wb') as dest: shutil.copyfileobj(src,dest,1024*1024)
        with apk.open('rb') as src: digest=hashlib.file_digest(src,'sha256').hexdigest()
        if version=='081': assert digest=='d4645ec62579e0c5a8abcede7430487cb949850ce929bce2ad30d990c9587d22'
        signature=command([str(tools/'apksigner'),'verify','--verbose','--print-certs',str(apk)],version+'-signature',0).stdout
        manifest=command([str(tools/'aapt2'),'dump','badging',str(apk)],version+'-manifest',0).stdout
        xml=command([str(tools/'aapt2'),'dump','xmltree',str(apk),'--file','AndroidManifest.xml'],version+'-manifest-tree',0).stdout
        libs=[];abis={}
        with zipfile.ZipFile(apk) as z:
            assert z.testzip() is None
            for entry in z.infolist():
                if not entry.filename.startswith('lib/') or not entry.filename.endswith('.so'): continue
                abi=entry.filename.split('/')[1];abis[abi]=abis.get(abi,0)+entry.file_size
                with z.open(entry) as f:
                    header=f.read(64); endian='<' if header[5]==1 else '>'; wide=header[4]==2
                    offset=struct.unpack_from(endian+('Q' if wide else 'I'),header,32 if wide else 28)[0]
                    size,count=struct.unpack_from(endian+'HH',header,54 if wide else 42)
                    f.seek(offset);programs=f.read(size*count)
                alignments=[]
                for i in range(count):
                    ph=struct.unpack_from(endian+('IIQQQQQQ' if wide else 'IIIIIIII'),programs,i*size)
                    if ph[0]==1: alignments.append(ph[-1])
                libs.append({'path':entry.filename,'bytes':entry.file_size,'compressed':entry.compress_type!=0,'loadAlignments':alignments,'elf16k':all(n>=16384 for n in alignments)})
        reports[version]={'sha256':digest,'bytes':apk.stat().st_size,'certificateSHA256':re.findall(r'certificate SHA-256 digest: (.+)',signature),
                          'package':next(l for l in manifest.splitlines() if l.startswith('package:')),
                          'sdk':[l for l in manifest.splitlines() if l.startswith(('sdkVersion:','targetSdkVersion:','native-code:'))],
                          'testOnly':'testOnly' in xml,'nativeBytesByABI':abis,'nativeLibraries':libs}
    (OUT/'apk-inspection.json').write_text(json.dumps(reports,indent=2))
    print('APK_INSPECTION',json.dumps(reports),flush=True)
def install():
    apk=DOWNLOADS/'081.apk';old=DOWNLOADS/'060.apk';results={}
    command(['adb','shell','getprop','ro.product.cpu.abilist'],'device-abis',0)
    command(['adb','shell','getconf','PAGE_SIZE'],'device-page-size',0)
    command(['adb','shell','df','-h','/data'],'device-disk',0)
    command(['adb','uninstall','kr.documentstudio'],'reset-test-device')
    fresh=command(['adb','install',str(apk)],'081-fresh-install')
    results['freshInstall']={'exitCode':fresh.returncode,'output':fresh.stdout}
    assert fresh.returncode==0,results
    launch=command(['adb','shell','am','start','-W','-n','kr.documentstudio/.MainActivity'],'081-launch')
    results['launch']={'exitCode':launch.returncode,'output':launch.stdout}
    command(['adb','shell','am','force-stop','kr.documentstudio'],'081-stop')
    same=command(['adb','install','-r',str(apk)],'081-same-signer-update')
    results['sameSignerUpdate']={'exitCode':same.returncode,'output':same.stdout}
    assert same.returncode==0,results
    command(['adb','uninstall','kr.documentstudio'],'remove-fresh-test-install',0)
    before=command(['adb','install',str(old)],'060-fresh-install')
    results['oldFreshInstall']={'exitCode':before.returncode,'output':before.stdout}
    if before.returncode==0:
        update=command(['adb','install','-r',str(apk)],'060-to-081-update')
        results['oldToNewUpdate']={'exitCode':update.returncode,'output':update.stdout}
        command(['adb','shell','dumpsys','package','kr.documentstudio'],'installed-package-after-update-attempt',0)
    command(['adb','logcat','-d','-s','AndroidRuntime:E'],'runtime-errors')
    (OUT/'installation-results.json').write_text(json.dumps(results,indent=2))
    print('INSTALLATION_RESULTS',json.dumps(results),flush=True)
if sys.argv[1]=='inspect': inspect()
elif sys.argv[1]=='install': install()
else: raise ValueError(sys.argv[1])
