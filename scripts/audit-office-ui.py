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
    needles=["_onDoubleTap","_onDoubleClick","doubletap","applyZoom(e){","addHandler\",\"touchGesture"]
    report={}
    for needle in needles:
        hits=list(re.finditer(re.escape(needle),text))
        report[needle]=[text[max(0,m.start()-180):m.end()+4500] for m in hits[:2]]
        print('OFFICE_API',json.dumps({'name':needle,'count':len(hits),'snippets':report[needle]},ensure_ascii=False))
    (out/'api.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
    (out/'dom.json').write_text(json.dumps(ids,ensure_ascii=False,indent=2))

