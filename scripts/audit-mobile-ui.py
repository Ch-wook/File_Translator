#!/usr/bin/env python3
"""Read the published baseline, original screenshots and actual bundled fonts."""
import base64,hashlib,json,pathlib,re,struct,urllib.request,zipfile
root=pathlib.Path.cwd();out=root/'artifacts/ui-audit';out.mkdir(parents=True,exist_ok=True)
base='https://github.com/Ch-wook/File_Translator/releases/download/v0.8.4-preview/'
def fetch(name,target,sha=None):
    with urllib.request.urlopen(base+name,timeout=120) as src,target.open('wb') as dst:
        while block:=src.read(1024*1024):dst.write(block)
    if sha:
        with target.open('rb') as f:assert hashlib.file_digest(f,'sha256').hexdigest()==sha
metadata=out/'baseline-metadata.json';fetch('document-studio-0.8.4-preview-apk-metadata.json',metadata)
meta=json.loads(metadata.read_text());assert meta['commit']=='4328146f0e0cc624d1dc41d262e6b649aea3dbc7'
item=next(a for a in meta['apks'] if a['abi']=='x86_64')
apk=out/'baseline.apk';fetch(item['file'],apk,item['sha256'])
verification=out/'baseline-verification.zip'
fetch('document-studio-0.8.4-preview-verification.zip',verification,'667588b9008597ef7277028dd288019d85c061287238b9d12a4b5d32b8bf4180')
def names(data):
    results=set()
    starts=[0] if data[:4]!=b'ttcf' else list(struct.unpack_from('>'+str(min(struct.unpack_from('>I',data,8)[0],32))+'I',data,12))
    for start in starts:
        for index in range(struct.unpack_from('>H',data,start+4)[0]):
            tag,_,offset,length=struct.unpack_from('>4sIII',data,start+12+index*16)
            if tag!=b'name':continue
            _,count,storage=struct.unpack_from('>HHH',data,offset)
            for i in range(count):
                platform,encoding,lang,kind,n,pos=struct.unpack_from('>HHHHHH',data,offset+6+i*12)
                if kind not in (1,16):continue
                try:
                    value=data[offset+storage+pos:offset+storage+pos+n].decode('utf-16-be' if platform in (0,3) else 'mac_roman')
                    if value and len(value)<160:results.add(value)
                except (UnicodeError,LookupError):pass
    return sorted(results)
fonts=[]
with zipfile.ZipFile(apk) as z:
    for name in z.namelist():
        if name.startswith('assets/hangul/'):
            target=root/'artifacts/ui-baseline-runtime'/name.removeprefix('assets/')
            target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(z.read(name))
        if name.startswith('assets/') and name.lower().endswith(('.ttf','.ttc','.otf')):
            try:family=names(z.read(name))
            except Exception as e:family=['UNPARSED:'+type(e).__name__]
            fonts.append({'asset':name,'families':family})
    office=[n for n in z.namelist() if n.startswith('assets/dist/') and n.endswith(('.html','.js'))]
    print('OFFICE_FILES',json.dumps([n for n in office if n.endswith('.html') or z.getinfo(n).file_size>500000]))
    for name in office:
        if name.endswith('cool.html'):print('OFFICE_HTML',z.read(name).decode(errors='replace')[:18000])
        if not name.endswith('.js') or z.getinfo(name).file_size<500000:continue
        text=z.read(name).decode(errors='replace')
        for needle in ('toggleRuler','ShowRuler','CharFontName','CharHeight','commandvalues','fontlist','fontList','textselection','getToolbarCommandsValues','_onCommandValuesMsg'):
            matches=list(re.finditer(re.escape(needle),text))
            for match in matches[:2]:print('OFFICE_SYMBOL',name,needle,text[max(0,match.start()-300):match.end()+750])
(out/'actual-fonts.json').write_text(json.dumps(fonts,ensure_ascii=False,indent=2))
print('ACTUAL_OFFICE_FONTS',json.dumps([f for f in fonts if not f['asset'].startswith('assets/hangul/')],ensure_ascii=False))
with zipfile.ZipFile(verification) as z:
    pictures=[n for n in z.namelist() if n.endswith('.png')]
    print('BASELINE_SCREENSHOTS',json.dumps(pictures,ensure_ascii=False))
    for name in z.namelist():
        if name.startswith('core/build/visual-fixtures/') and name.endswith(('.hwp','.hwpx','.docx','.xlsx','.pptx')):
            target=root/'core/build/visual-fixtures'/pathlib.PurePosixPath(name).name
            target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(z.read(name))
    chosen=[]
    for pattern in ('hwpx-edit','docx','xlsx','pptx'):
        found=next((n for n in pictures if pattern in n and n not in chosen),None)
        if found:chosen.append(found)
    for name in chosen:
        target=out/('baseline-'+pathlib.PurePosixPath(name).name);target.write_bytes(z.read(name))
        print('UI_BASELINE_PNG',json.dumps({'source':name,'base64':base64.b64encode(target.read_bytes()).decode()}))
apk.unlink();verification.unlink()
