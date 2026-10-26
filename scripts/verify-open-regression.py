#!/usr/bin/env python3
"""Prove metadata regression against the old implementation, then the committed fix."""
import json,pathlib,shutil,subprocess,xml.etree.ElementTree as ET
root=pathlib.Path.cwd();out=root/'artifacts/ci/open-regression';out.mkdir(parents=True,exist_ok=True)
source=root/'app/src/main/java/kr/documentstudio/data/DocumentRepository.kt'
fixed=source.read_text()
old=subprocess.check_output(['git','show','c7b7f3d49725ebbfb5328546f3b88d9bd58d3a3e:app/src/main/java/kr/documentstudio/data/DocumentRepository.kt'],text=True)
def method(text):
    a=text.index('    private fun displayName(uri: Uri): String {')
    b=text.index('    private fun normalizeImage(',a)
    return text[a:b]
broken=fixed.replace(method(fixed),method(old)).replace('runCatching { context.contentResolver.getType(uri) }.getOrNull()','context.contentResolver.getType(uri)')
assert broken!=fixed
command=['./gradlew',':app:testDebugUnitTest','--tests','kr.documentstudio.DocumentOpenMetadataTest','--max-workers=2','--console=plain']
report=root/'app/build/test-results/testDebugUnitTest/TEST-kr.documentstudio.DocumentOpenMetadataTest.xml'
results={}
try:
    for name,text in [('before',broken),('after',fixed)]:
        source.write_text(text)
        with (out/(name+'.log')).open('w') as log:
            code=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT).returncode
        assert report.is_file(),(name,'No JUnit result; inspect compilation log')
        parsed=ET.parse(report).getroot()
        result={k:int(parsed.get(k,0)) for k in ('tests','failures','errors','skipped')}
        shutil.copy2(report,out/(name+'.xml'))
        print('OPEN_METADATA_REGRESSION',name,code,result,flush=True)
        assert result['tests']==3 and result['skipped']==0,result
        if name=='before':assert code!=0 and result['failures']==3 and result['errors']==0,result
        else:assert code==0 and result['failures']==result['errors']==0,result
        results[name]=result
finally:source.write_text(fixed)
subprocess.run(['git','diff','--exit-code','--',str(source)],check=True)
(out/'summary.json').write_text(json.dumps(results,indent=2))
