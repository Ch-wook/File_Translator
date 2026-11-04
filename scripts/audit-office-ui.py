#!/usr/bin/env python3
"""Inspect the exact bundled Office DOM and font command APIs, without guessing upstream APIs."""
import hashlib,json,pathlib,re,urllib.request,zipfile
out=pathlib.Path('artifacts/office-ui-audit');out.mkdir(parents=True,exist_ok=True)
apk=pathlib.Path('.engine-cache/collabora-x86_64.apk');apk.parent.mkdir(exist_ok=True)
if not apk.is_file():
    with urllib.request.urlopen('https://www.collaboraoffice.com/downloads/Collabora-Office-Android-Snapshot/collabora-office-mobile-26.04.6.0-951-snapshot-x86_64-2026-10-15.apk',timeout=120) as src,apk.open('wb') as dst:
        while block:=src.read(1024*1024):dst.write(block)
with apk.open('rb') as f:assert hashlib.file_digest(f,'sha256').hexdigest()=='8ef8fc5aff5ab4296a7f54d0293e49807ca6a606ffe47a7330b1f20a677dd544'
with zipfile.ZipFile(apk) as z:
    html=z.read('assets/dist/cool.html').decode()
    ids=re.findall(r'<[^>]*\bid="(?:toolbar[^"]*|document[^"]*|main[^"]*|map|formula[^"]*|spreadsheet[^"]*|presentation[^"]*|mobile[^"]*|closebutton|sidebar[^"]*)"[^>]*>',html)
    print('OFFICE_DOM',json.dumps(ids,ensure_ascii=False))
    text=z.read('assets/dist/bundle.js').decode()
    needles=["postMobileError","postMobileDebug","_fitWidthZoom:function","getPartSizeTwips(e)"]
    report={}
    for needle in needles:
        hits=list(re.finditer(re.escape(needle),text))
        report[needle]=[text[max(0,m.start()-180):m.end()+4500] for m in hits[:2]]
        print('OFFICE_API',json.dumps({'name':needle,'count':len(hits),'snippets':report[needle]},ensure_ascii=False))
    (out/'api.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
    (out/'dom.json').write_text(json.dumps(ids,ensure_ascii=False,indent=2))


    for name in z.namelist():
        if name.startswith('assets/dist/') and name.endswith(('.js','.html')):
            content=z.read(name).decode(errors='replace')
            hits=list(re.finditer(re.escape('postMobileError'),content))
            if hits:
                print('BROWSER_DIAGNOSTIC_CALLS',json.dumps({'file':name,'count':len(hits),'snippets':[content[max(0,m.start()-180):m.end()+650] for m in hits[-4:]]},ensure_ascii=False))

    for method in ('canvasToDocumentPoint','documentToViewY','documentToViewX','applyZoom','_onPinchStart','_onPinchEnd'):
        pattern=re.escape(method)+r'(?:\([^)]*\)\s*\{|:function\([^)]*\)\s*\{)'
        hits=list(re.finditer(pattern,text))
        print('OFFICE_COORD_API',json.dumps({'name':method,'count':len(hits),'snippets':[text[m.start():m.end()+4500] for m in hits[:4]]},ensure_ascii=False))
    for layout in ('ViewLayoutFileBased','ViewLayout'):
        needle='this.type="'+layout+'"'
        hit=text.find(needle)
        if hit>=0:
            start=text.rfind('class ',0,hit)
            end=text.find('class ',hit)
            print('OFFICE_LAYOUT_CLASS',json.dumps({'name':layout,'source':text[start:min(end if end>=0 else len(text),start+25000)]},ensure_ascii=False))
