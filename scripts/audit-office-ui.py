#!/usr/bin/env python3
"""Inspect the exact bundled Office DOM and font command APIs, without guessing upstream APIs."""
import hashlib,json,pathlib,re,urllib.request,zipfile
out=pathlib.Path('artifacts/office-ui-audit');out.mkdir(parents=True,exist_ok=True)
apk=out/'baseline.apk'
with urllib.request.urlopen('https://github.com/Ch-wook/File_Translator/releases/download/v0.8.4-preview/document-studio-0.8.4-preview-x86_64.apk',timeout=120) as src,apk.open('wb') as dst:
    while block:=src.read(1024*1024):dst.write(block)
with apk.open('rb') as f:assert hashlib.file_digest(f,'sha256').hexdigest()=='cad44eedc7d433f7a902b875a442f925d02c20686ed81a15da5d6bfaab806d62'
with zipfile.ZipFile(apk) as z:
    html=z.read('assets/dist/cool.html').decode()
    ids=re.findall(r'<[^>]*\bid="(?:toolbar[^"]*|document[^"]*|main[^"]*|map|formula[^"]*|spreadsheet[^"]*|presentation[^"]*|mobile[^"]*|closebutton|sidebar[^"]*)"[^>]*>',html)
    print('OFFICE_DOM',json.dumps(ids,ensure_ascii=False))
    text=z.read('assets/dist/bundle.js').decode()
    needles=['applyFont:function','applyFont(','applyFontSize:function','applyFontSize(','getToolbarCommandValues','toolbarcommandvalues','CharFontName.FamilyName','FontHeight.Height','_onCommandValuesMsg:function','fontnamecombobox','fontNames','CharScaleWidth','Spacing','isRulerVisible()']
    report={}
    for needle in needles:
        hits=list(re.finditer(re.escape(needle),text))
        report[needle]=[text[max(0,m.start()-180):m.end()+1000] for m in hits[:5]]
        print('OFFICE_API',json.dumps({'name':needle,'count':len(hits),'snippets':report[needle]},ensure_ascii=False))
    (out/'api.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
    (out/'dom.json').write_text(json.dumps(ids,ensure_ascii=False,indent=2))
apk.unlink()
