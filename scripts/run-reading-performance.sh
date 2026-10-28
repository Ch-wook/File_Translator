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
  adb shell am instrument -w -r -e class kr.documentstudio.ReadingPerformanceTest "$pkg.test/androidx.test.runner.AndroidJUnitRunner" | tee "$ROOT/$label/instrumentation.txt"
  adb pull "/sdcard/Android/data/$pkg/files/reading-performance/." "$ROOT/$label/"
  adb logcat -d > "$ROOT/$label/logcat.txt"
  python3 - "$ROOT/$label" <<'PY'
import json,pathlib,sys
root=pathlib.Path(sys.argv[1])
log=(root/'instrumentation.txt').read_text()
assert 'OK (3 tests)' in log and 'FAILURES!!!' not in log,log[-8000:]
for name in ('repository','native-readers','text-ui'):
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
import json,pathlib,statistics,os
root=pathlib.Path('artifacts/reading-performance')
data={label:{p.stem:json.loads(p.read_text()) for p in (root/label).glob('*.json')} for label in ('baseline','candidate')}
def med(label,group,key):return round(statistics.median(data[label][group][key]),3)
metrics={}
for group,key in [('repository','openMs'),('repository','checkpointMs'),('repository','unchangedOriginalSaveMs'),('repository','pdfGeometryMs'),('repository','pdfPageRenderMs'),('text-ui','modeDriverRoundTripMs'),('text-ui','menuDriverRoundTripMs')]:
    values={label:med(label,group,key) for label in data}
    metrics[key]=values|{'changePercent':round((values['candidate']/values['baseline']-1)*100,1)}
result={'baselineCommit':os.environ['BASELINE_SHA'],'candidateCommit':os.environ['GITHUB_SHA'],'environment':'same Android 35 x86_64 Pixel 7 emulator, SwiftShader, 3072 MiB; sequential baseline then candidate; not physical phone','nativeSamplesPerFormat':1,'textUiMeasurement':'includes Compose test idling; not raw input-to-first-frame','metrics':metrics,'records':data}
(root/'comparison.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
print('READING_PERFORMANCE_COMPARISON',json.dumps(result,ensure_ascii=False))
PY
