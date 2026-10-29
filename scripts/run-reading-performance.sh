#!/usr/bin/env bash
set -euo pipefail
ROOT=artifacts/reading-performance
AAPT="$ANDROID_HOME/build-tools/35.0.0/aapt2"
adb shell getprop > "$ROOT/emulator-properties.txt"
for label in baseline candidate; do
  apk="$ROOT/apks/$label.apk"
  pkg=$("$AAPT" dump badging "$apk" | sed -n "s/package: name='\([^']*\)'.*/\1/p")
  adb install -r "$apk"
  adb install -r "$ROOT/apks/$label-test.apk"
  adb shell am force-stop com.android.launcher3
  mkdir -p "$ROOT/$label"
  for sample in 1 2 3; do
    adb shell am force-stop "$pkg"
    adb shell am start -W -n "$pkg/kr.documentstudio.MainActivity" > "$ROOT/$label/startup-$sample.txt"
  done
  adb shell am force-stop "$pkg"
  set +e
  adb shell am instrument -w -r -e class kr.documentstudio.ReadingPerformanceTest,kr.documentstudio.NativeReadingPerformanceTest "$pkg.test/androidx.test.runner.AndroidJUnitRunner" | tee "$ROOT/$label/instrumentation.txt"
  instrument_status=${PIPESTATUS[0]}
  set -e
  adb logcat -d > "$ROOT/$label/logcat.txt"
  adb pull "/sdcard/Android/data/$pkg/files/reading-performance/." "$ROOT/$label/"
  test "$instrument_status" -eq 0
  python3 - "$ROOT/$label" <<'PY'
import json,pathlib,sys
root=pathlib.Path(sys.argv[1])
log=(root/'instrumentation.txt').read_text()
assert 'OK (4 tests)' in log and 'FAILURES!!!' not in log,log[-8000:]
for name in ('repository','native-readers','text-ui','viewport-memory'):
    result=json.loads((root/(name+'.json')).read_text())
    assert result['physicalDevice'] is False
print('READING_BENCHMARK_RECORDS',root)
PY
  adb shell am force-stop "$pkg"
  adb uninstall "$pkg.test"
  adb uninstall "$pkg"
  adb logcat -c
done
python3 - <<'PY'
import json,pathlib,statistics,os,hashlib,shutil,re
root=pathlib.Path('artifacts/reading-performance')
data={label:{p.stem:json.loads(p.read_text()) for p in (root/label).glob('*.json')} for label in ('baseline','candidate')}
def med(label,group,key):return round(statistics.median(data[label][group][key]),3)
metrics={}
for group,key in [('repository','openMs'),('repository','checkpointMs'),('repository','unchangedOriginalSaveMs'),('repository','modifiedOriginalSaveMs'),('repository','pdfGeometryMs'),('repository','pdfPageRenderMs'),('text-ui','modeDriverRoundTripMs'),('text-ui','menuDriverRoundTripMs'),('viewport-memory','largeTextModeDriverMs')]:
    values={label:med(label,group,key) for label in data}
    metrics[key]=values|{'changePercent':round((values['candidate']/values['baseline']-1)*100,1)}
startup={label:[int(re.search(r'TotalTime:\s*(\d+)',p.read_text()).group(1)) for p in (root/label).glob('startup-*.txt')] for label in data}
result={'activityLaunchTotalTimeMs':startup,'apkSha256':{p.name:hashlib.file_digest(p.open('rb'),'sha256').hexdigest() for p in (root/'apks').glob('*.apk')},'baselineCommit':os.environ['BASELINE_SHA'],'candidateCommit':os.environ['GITHUB_SHA'],'environment':'same Android 35 x86_64 Pixel 7 emulator, SwiftShader, 3072 MiB; sequential baseline then candidate; not physical phone','nativeSamplesPerFormat':1,'textUiMeasurement':'includes Compose test idling; not raw input-to-first-frame','metrics':metrics,'records':data}
(root/'comparison.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
print('READING_PERFORMANCE_COMPARISON',json.dumps(result,ensure_ascii=False))
shutil.rmtree(root/'apks')
PY
