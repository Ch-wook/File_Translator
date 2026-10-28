#!/usr/bin/env python3
"""Verify a fresh mobile preview install alongside the published 0.8.6 and a data-preserving reinstall."""
import hashlib,json,os,pathlib,shutil,subprocess,urllib.request
ROOT=pathlib.Path.cwd();OUT=ROOT/'artifacts/mobile-install';OUT.mkdir(parents=True,exist_ok=True)
PKG='kr.documentstudio.workspacepreview';OLD='kr.documentstudio.editorspreview'
BASE='https://github.com/Ch-wook/File_Translator/releases/download/v0.8.6-preview/'
def digest(p):
    with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def run(args,name):
    p=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
    (OUT/(name+'.txt')).write_text(p.stdout);print(name,p.returncode,p.stdout[:3000],flush=True)
    assert p.returncode==0,(name,p.stdout)
    return p.stdout
def download(url,p):
    with urllib.request.urlopen(url,timeout=120) as src,p.open('wb') as dst:shutil.copyfileobj(src,dst,1024*1024)
def apk_hash(pkg):
    path=run(['adb','shell','pm','path',pkg],pkg+'-package-path').strip().removeprefix('package:')
    assert path.startswith('/data/app/') and '\n' not in path
    return run(['adb','shell','sha256sum',path],pkg+'-installed-apk-hash').split()[0]
metadata=OUT/'baseline-metadata.json';download(BASE+'document-studio-0.8.6-preview-apk-metadata.json',metadata)
prior=json.loads(metadata.read_text());assert prior['commit']=='728892f6071d2274aea1f4ca309f2b0785377440'
item=next(a for a in prior['apks'] if a['abi']=='x86_64')
assert item['applicationId']==OLD and item['versionCode']==16
assert item['certificateSHA256']=='311ae1b51047c3aa5ddcd66d30bf978c2a9f394e275890f292955881011732dd'
old=OUT/'baseline-086.apk';download(BASE+item['file'],old);assert digest(old)==item['sha256']
tool=pathlib.Path(os.environ['ANDROID_HOME'])/'build-tools/35.0.0/apksigner'
certificate=subprocess.check_output([str(tool),'verify','--print-certs',str(old)],text=True)
assert item['certificateSHA256'] in certificate
run(['adb','install',str(old)],'baseline-086-install')
before=apk_hash(OLD)
canary=OUT/'canary.txt';canary.write_text('KEEP_COMPACT_PREVIEW_DATA_087\n')
run(['adb','push',str(canary),'/data/local/tmp/studio-mobile-canary'],'upload-canary')
for pkg in (OLD,):
    run(['adb','shell','run-as',pkg,'mkdir','-p','files'],pkg+'-private-directory')
    run(['adb','shell','run-as',pkg,'cp','/data/local/tmp/studio-mobile-canary','files/mobile-install-canary'],pkg+'-create-canary')
new=ROOT/'artifacts/mobile-build/x86_64.apk'
run(['adb','install',str(new)],'mobile-fresh-install-with-086-present')
launch=run(['adb','shell','am','start','-W','-n',PKG+'/kr.documentstudio.MainActivity'],'mobile-launch')
assert 'Status: ok' in launch
run(['adb','shell','am','force-stop',PKG],'mobile-stop')
run(['adb','shell','run-as',PKG,'mkdir','-p','files'],'mobile-private-directory')
run(['adb','shell','run-as',PKG,'cp','/data/local/tmp/studio-mobile-canary','files/mobile-install-canary'],'mobile-create-canary')
run(['adb','install','-r',str(new)],'mobile-same-signer-reinstall')
for pkg in (OLD,PKG):
    value=run(['adb','shell','run-as',pkg,'cat','files/mobile-install-canary'],pkg+'-canary-after-install')
    assert value==canary.read_text()
after=apk_hash(OLD);assert before==after
packages=run(['adb','shell','pm','list','packages','kr.documentstudio'],'both-preview-packages')
assert 'package:'+OLD+'\n' in packages and 'package:'+PKG+'\n' in packages
old.unlink() # The baseline is public and need not inflate verification ZIPs.
result={'package':PKG,'installedABI':'x86_64','cleanInstallWithoutTestFlag':True,'launch':True,'coexistsWith086':True,
        'priorApkHashUnchanged':before==after,'priorPrivateDataPreserved':True,'sameSignerReinstallPreservedPrivateData':True,'physicalDeviceTested':False}
(OUT/'summary.json').write_text(json.dumps(result,indent=2))
print('MOBILE_INSTALLATION_RESULT',json.dumps(result),flush=True)
