#!/usr/bin/env python3
"""Reproduce the public-coordinate advisory on the old API, then require the real fix."""
import pathlib,subprocess,xml.etree.ElementTree as E,json,shutil
root=pathlib.Path.cwd();out=root/'artifacts/quality-security/log-json-regression';out.mkdir(parents=True,exist_ok=True)
source=root/'core/build.gradle.kts';fixed=source.read_text()
assert 'org.apache.logging.log4j:log4j-api:2.25.5' in fixed
before=fixed.replace('org.apache.logging.log4j:log4j-api:2.25.5','org.apache.logging.log4j:log4j-api:2.24.3')
report=root/'core/build/test-results/test/TEST-kr.documentstudio.core.LogJsonSafetyTest.xml'
counts={}
try:
    for label,value in [('before',before),('after',fixed)]:
        source.write_text(value)
        with (out/(label+'.log')).open('w') as log:
            code=subprocess.run(['./gradlew',':core:test','--tests','kr.documentstudio.core.LogJsonSafetyTest','--configure-on-demand','--max-workers=2','--console=plain'],stdout=log,stderr=subprocess.STDOUT).returncode
        if not report.exists():
            print((out/(label+'.log')).read_text()[-14000:],flush=True)
            raise AssertionError('No regression XML; inspect compilation')
        parsed=E.parse(report).getroot();count={k:int(parsed.get(k,0)) for k in ('tests','failures','errors','skipped')}
        assert count['tests']==1 and count['errors']==count['skipped']==0,count
        assert (code!=0 and count['failures']==1) if label=='before' else (code==0 and count['failures']==0),count
        counts[label]=count;shutil.copy2(report,out/(label+'.xml'))
        print('LOG_JSON_SECURITY_REGRESSION',label,code,count,flush=True)
finally:source.write_text(fixed)
subprocess.run(['git','diff','--exit-code','--',str(source)],check=True)
(out/'summary.json').write_text(json.dumps(counts,indent=2))
