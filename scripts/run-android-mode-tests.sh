#!/usr/bin/env bash
set -uo pipefail
mkdir -p artifacts/ci artifacts/native-android
./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true "-Pandroid.testInstrumentationRunnerArguments.class=${MODE_TEST_CLASSES:-kr.documentstudio.P0ModeUiTest,kr.documentstudio.OfficeDefaultRoutingTest,kr.documentstudio.NativeHangulModeSmokeTest,kr.documentstudio.NativeOfficeModeSmokeTest}" --max-workers=2 --console=plain 2>&1 | tee artifacts/ci/android-emulator.log
mode_test_status=${PIPESTATUS[0]}
adb pull /sdcard/Android/data/kr.documentstudio/files/native-hangul-smoke artifacts/native-android/ || true
adb pull /sdcard/Android/data/kr.documentstudio/files/native-office-mode-smoke artifacts/native-android/ || true
adb logcat -d -s AndroidRuntime:E > artifacts/ci/android-runtime.log || true
python3 - <<'PY'
import pathlib,xml.etree.ElementTree as ET,json
reports=[]
for p in pathlib.Path('app/build/outputs/androidTest-results').rglob('TEST-*.xml'):
    root=ET.parse(p).getroot()
    result={key:int(root.get(key,0)) for key in ('tests','failures','errors','skipped')}
    reports.append(result)
    for case in root.iter('testcase'):
        for failure in list(case.findall('failure'))+list(case.findall('error')):
            print('ANDROID_TEST_FAILURE',case.attrib,(failure.text or '')[:12000])
summary={key:sum(r[key] for r in reports) for key in ('tests','failures','errors','skipped')}
pathlib.Path('artifacts/ci/android-summary.json').write_text(json.dumps(summary,indent=2))
print('ANDROID_TEST_SUMMARY',json.dumps(summary))
for p in pathlib.Path('artifacts/native-android').rglob('*.json'):
    print('NATIVE_EVIDENCE',p,p.read_text()[:18000])
for p in pathlib.Path('artifacts/native-android').rglob('*failure.xml'):
    print('NATIVE_FAILURE_UI',p,p.read_text()[:24000])
assert summary['tests']>0 and summary['failures']==0 and summary['errors']==0,summary
PY
xml_status=$?
if [ "$mode_test_status" -ne 0 ]; then exit "$mode_test_status"; fi
exit "$xml_status"
