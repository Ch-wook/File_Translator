#!/usr/bin/env python3
"""Reproduce a dropped close against the old host, then test the real engine fix."""
import json,os,pathlib,subprocess
root=pathlib.Path.cwd()
runtime=root/'.engine-cache/hangul/runtime/hangul/host.js'
current=runtime.read_bytes()
old='8a1aeec2b8952c456c236d197804ee44371ce5e6'
baseline=subprocess.check_output(['git','show',old+':web/hangul/host.js'])
out=root/'artifacts/ci/close-race-regression';out.mkdir(parents=True,exist_ok=True)
env={**os.environ,'CLOSE_RACE_ONLY':'1'}
try:
    runtime.write_bytes(baseline)
    before=subprocess.run(['node','scripts/tests/hangul-mobile.cjs'],env=env,capture_output=True,text=True,timeout=240)
    (out/'before.log').write_text(before.stdout+before.stderr)
    assert before.returncode!=0 and 'CLOSE_RACE_REQUEST_WHILE_EXPORT_HELD' in before.stdout and 'Timeout 15000ms exceeded' in before.stderr,before.stdout+before.stderr
    expected=out/'before';expected.mkdir(exist_ok=True)
    for name in ('failure.json','failure.png','failure-dom.html'):
        file=root/'artifacts/hangul-mobile'/name
        if file.is_file():file.replace(expected/name)
    runtime.write_bytes(current)
    after=subprocess.run(['node','scripts/tests/hangul-mobile.cjs'],env=env,capture_output=True,text=True,timeout=240)
    (out/'after.log').write_text(after.stdout+after.stderr)
    assert after.returncode==0,after.stdout+after.stderr
    result=json.loads((root/'artifacts/hangul-mobile/mobile-engine-results.json').read_text())
    assert {r['format'] for r in result}=={'hwp','hwpx'} and all(r['queuedCloseJoinsAutosave'] and r['failedClosePreservesWork'] and r['duplicateCloseCoalesced'] and r['savedAndReopened'] for r in result),result
    proof={'baselineHost':old,'beforeDroppedCloseReproduced':True,'afterFormats':result,'runtimeRestored':True}
    (out/'summary.json').write_text(json.dumps(proof,indent=2))
    print('HANGUL_CLOSE_RACE_REGRESSION',json.dumps(proof),flush=True)
finally:
    runtime.write_bytes(current)
