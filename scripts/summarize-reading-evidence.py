#!/usr/bin/env python3
"""Print bounded CI-only synthetic-fixture measurements and failure diagnostics."""
import json,pathlib,re
root=pathlib.Path('artifacts/reading-performance')
for label in ('baseline','candidate'):
    folder=root/label
    for p in sorted(folder.glob('*.json')):
        print('READING_RECORD',label,p.name,p.read_text(),flush=True)
    for p in sorted(folder.glob('*-memory.txt')):
        print('READING_MEMORY',label,p.name,'\n'.join(l for l in p.read_text().splitlines() if re.search(r'TOTAL|Native Heap|Dalvik Heap|Graphics',l)),flush=True)
    for p in folder.glob('*.xml'):
        print('READING_FAILURE_UI',label,p.name,p.read_text()[:24000],flush=True)
    log=folder/'instrumentation.txt'
    if log.exists() and 'OK (3 tests)' not in log.read_text():
        path=folder/'logcat.txt'
        if path.exists():
            lines=[l for l in path.read_text(errors='replace').splitlines() if re.search(r'AndroidRuntime|FATAL EXCEPTION|ActivityTaskManager|Invalid private|office-lifecycle|libreoffice|NativeLoad|Chromium',l,re.I)]
            print('READING_FAILURE_LOGCAT',label,'\n'.join(lines[-160:]),flush=True)
