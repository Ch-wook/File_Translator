#!/usr/bin/env python3
"""Install the preview beside an earlier signed app, then verify a real in-place reinstall."""
import hashlib,json,pathlib,subprocess,urllib.request,shutil
ROOT=pathlib.Path.cwd();OUT=ROOT/'artifacts/install-fixed';OUT.mkdir(parents=True,exist_ok=True)
PKG='kr.documentstudio.preview'
def run(args,name,check=True):
    p=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
    (OUT/(name+'.txt')).write_text(p.stdout);print(name,p.returncode,p.stdout[:3500],flush=True)
    if check: assert p.returncode==0,(name,p.stdout)
    return p.stdout
def base_hash():
    path=run(['adb','shell','pm','path','kr.documentstudio'],'original-package-path').strip().removeprefix('package:')
    assert path.startswith('/data/app/') and '\n' not in path,path
    return run(['adb','shell','sha256sum',path],'original-package-hash').split()[0]
old=ROOT/'artifacts/install-build/old-060.apk'
if not old.is_file():
    with urllib.request.urlopen('https://github.com/Ch-wook/File_Translator/releases/download/v0.6.0/document-studio-release.apk',timeout=120) as src,old.open('wb') as dst: shutil.copyfileobj(src,dst,1024*1024)
with old.open('rb') as f: assert hashlib.file_digest(f,'sha256').hexdigest()=='033f5b3ad5fe25312606a1f313cee4e5da19d990f57c6d9fcee2ff3bb16d1fc8'
run(['adb','install',str(old)],'old-original-install')
before=base_hash()
new=ROOT/'artifacts/install-build/x86_64.apk'
run(['adb','install',str(new)],'preview-fresh-install-with-original-present')
launch=run(['adb','shell','am','start','-W','-n',PKG+'/kr.documentstudio.MainActivity'],'preview-launch')
assert 'Status: ok' in launch,launch
run(['adb','shell','am','force-stop',PKG],'preview-stop')
canary=OUT/'canary.txt';canary.write_text('KEEP_EXISTING_PREVIEW_DATA_082\n')
run(['adb','push',str(canary),'/data/local/tmp/preview-install-canary'],'upload-canary')
run(['adb','shell','run-as',PKG,'mkdir','-p','files'],'preview-private-directory')
run(['adb','shell','run-as',PKG,'cp','/data/local/tmp/preview-install-canary','files/install-canary'],'create-preview-canary')
run(['adb','install','-r',str(new)],'preview-same-certificate-update')
value=run(['adb','shell','run-as',PKG,'cat','files/install-canary'],'preview-data-after-update')
assert value==canary.read_text(),value
after=base_hash();assert before==after,(before,after)
packages=run(['adb','shell','pm','list','packages','kr.documentstudio'],'both-packages')
assert 'package:kr.documentstudio\n' in packages and 'package:'+PKG+'\n' in packages,packages
result={'package':PKG,'installedABI':'x86_64','cleanInstallWithoutTestFlag':True,'launch':True,'coexistsWithOriginal060':True,
        'originalApkHashBefore':before,'originalApkHashAfter':after,'sameSignerReinstallPreservedPrivateData':True,'physicalDeviceTested':False}
(OUT/'summary.json').write_text(json.dumps(result,indent=2))
print('FIXED_INSTALLATION_RESULT',json.dumps(result),flush=True)
