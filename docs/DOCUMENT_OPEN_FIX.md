# 문서 열기 오류 수정 — 0.8.4-preview

## 확인한 원인

- 배포용 HWP의 편집 제한을 파일 형식 판별 단계에서 열기 거부로 처리했다. 기존 소스로 공개 HWP 43개를 비교한 결과, 42개는 호스트/엔진 모두 열렸지만 배포용 1개는 호스트에서 거부하고 실제 Rhwp 0.8.7에서는 2쪽으로 표시됐다. 모든 브라우저 보기에서 문서 쓰기는 0회였다.
- 파일 제공자의 `query(DISPLAY_NAME)`와 `getType`을 필수 작업으로 호출했다. 스트림을 읽을 수 있어도 부가 정보를 지원하지 않는 제공자에서 예외가 전파될 수 있었다. 이름 조회는 컬럼 위치 0을 가정했다.

이는 재현한 코드 결함이다. 사용자 문제 파일·형식·실제 오류 문구는 아직 확보하지 못했다. 해당 사용자 오류와 원인이 일치한다고 단정하지 않는다.

## 수정 범위

- 배포용 HWP는 형식을 인식하고 본래 보기 데이터를 실제 한글 엔진에 전달한다. 추출형 편집기는 사용하지 않는다.
- 읽기 전용 사유는 파일 헤더에서 계산한다. 외부 EDIT 요청도 VIEW로 처리하고, 호스트의 편집 버튼을 숨긴다. 네이티브 브리지의 편집 허용/쓰기 시작을 거부하며, 수정된 보호 작업 파일은 저장 기준으로 반영하지 않는다.
- 편집 가능한 일반 HWP/HWPX의 기존 기능과 보기→명시적 편집 전환은 유지한다.
- 이름/MIME 조회 실패는 선택 정보 누락으로 처리한다. 이름 컬럼을 실제 인덱스로 읽는다. 실제 스트림 권한·컨테이너·용량·암호화 검사는 유지한다.
- HWP MIME 별칭과 외부 EDIT intent 연결을 추가한다. 문서 저장·백업·충돌 보호 구조는 유지한다.

연속으로 문서를 열 때 먼저 닫힌 한글 창이 다른 열린 창까지 같은 엔진 프로세스와 함께 종료하지 않도록, 마지막 호스트 창을 닫을 때만 프로세스를 종료하도록 보호했습니다. 두 창의 종료 순서는 별도 단위 테스트로 검증합니다.

외부 파일로 앱을 처음 시작할 때 가져오기가 화면 재개보다 먼저 끝날 수 있으므로, 네이티브 문서 열기 요청은 MainActivity가 RESUMED가 된 뒤 처리합니다. 대기 중인 요청을 먼저 소비하지 않습니다.

Android 검사용 브리지는 종료 가능한 별도 엔진 프로세스에 불안정(unstable) 제공자 연결을 사용합니다. 안정 연결을 유지한 채 엔진이 정상 종료되면 Android가 의존 중인 검사/호스트 프로세스까지 종료할 수 있으므로, 닫힘은 새 브리지 요청 없이 화면의 WebView 소멸로 확인합니다.

## 실제 수정 파일

| 파일 | 변경 |
|---|---|
| [LegacyDocument.kt](../core/src/main/kotlin/kr/documentstudio/core/LegacyDocument.kt) | 배포용 HWP 형식 인식과 읽기 전용 사유, 추출 편집 차단 |
| [HangulConversion.kt](../core/src/main/kotlin/kr/documentstudio/core/HangulConversion.kt) | 배포용 HWP 변환 차단 |
| [DocumentRepository.kt](../app/src/main/java/kr/documentstudio/data/DocumentRepository.kt) | 선택적 이름/MIME 조회, 읽기 전용 세션·작업 반영 보호 |
| [EditorViewModel.kt](../app/src/main/java/kr/documentstudio/EditorViewModel.kt) | 보호 문서의 VIEW/READ_ONLY 전환, 외부 EDIT 제한 |
| [HangulEditorActivity.kt](../app/src/main/java/kr/documentstudio/HangulEditorActivity.kt) | 헤더에서 읽기 전용 판정, 쓰기 거부, 다른 열린 한글 창의 프로세스 보호 |
| [StudioApp.kt](../app/src/main/java/kr/documentstudio/ui/StudioApp.kt) | MainActivity RESUMED 뒤 열기 요청 처리 |
| [VisualDocumentWorkspace.kt](../app/src/main/java/kr/documentstudio/ui/VisualDocumentWorkspace.kt), [host.js](../web/hangul/host.js) | 읽기 전용 사유와 실제 가능한 도구만 표시 |
| [app/build.gradle.kts](../app/build.gradle.kts), [AndroidManifest.xml](../app/src/main/AndroidManifest.xml) | 별도 설치 ID/버전, HWP MIME 별칭·EDIT 연결 |
| 단위·Android 테스트, 검증/배포 스크립트 | 실패 재현, 외부 열기·저장 보호·설치·자산 무결성 확인 |

전체 경로와 정확한 diff는 해당 빌드 커밋 및 PR #1을 기준으로 확인합니다.

## 검증 방법

`python3 scripts/prepare-open-fixtures.py`가 공개 hwplib의 배포용 샘플 한 개를 원본 Git blob/크기로 대조해 테스트 자산을 준비한다. 출처: [neolord0/hwplib](https://github.com/neolord0/hwplib), commit `6746c27f17ebf5277493206284aa32044a1839c4`, Apache-2.0. 입력은 변경하지 않는다.

- `scripts/verify-open-regression.py`: 동일 3개 제공자 테스트로 예전 이름/MIME 처리의 실패와 수정 후 통과를 확인한다. 본래 코드를 복원하고 Git 차이도 검사한다.
- core: 배포용 형식 인식·추출 편집 거부, 암호화 파일 거부.
- Robolectric: 이름/MIME 예외, 잘못된 컬럼 가정, 손상/권한 없는 입력 거부, 보호 문서 복사·재열기, 네이티브 쓰기 거부, 보호 작업 파일의 변경 반영 거부.
- Android `DocumentOpenRegressionTest`: 외부 ACTION_VIEW→메타데이터 없는 HWP/HWPX→실제 네이티브 VIEW/EDIT 전환→닫기→복원/원본 해시, ACTION_EDIT→배포용 HWP 읽기 전용→편집/쓰기 거부→재열기/해시.
- 기존 편집·IME·표·그림·저장/독립 파서 검증도 배포 완료 조건으로 유지한다.

## 실제 실행 결과 — 2026-10-27

[최종 빌드 및 Android 검사](https://github.com/Ch-wook/File_Translator/actions/runs/40424845558) **성공**. APK·소스 빌드 커밋: `4328146f0e0cc624d1dc41d262e6b649aea3dbc7`.

- Android 35 **x86_64 에뮬레이터**: 23건, 실패·오류·건너뜀 0건. 메타데이터 없는 HWP/HWPX 외부 VIEW→실제 화면의 편집/보기 전환→닫기→복원/원본 해시, 배포용 HWP의 외부 EDIT→실제 읽기 전용 화면→쓰기 거부→재열기/해시를 통과했다.
- 단위 검사 143건(core 122, Office 2, 앱 19), 실패·오류·건너뜀 0건. 두 한글 창 중 먼저 닫힌 창이 다른 문서의 엔진 프로세스를 종료하지 않는 검사 포함.
- 동일 제공자 검사 3건: 이전 처리 3건 실패, 수정 처리 3건 통과. 음성 재현 보고서와 성공 보고서를 각각 보존했다.
- 실제 Rhwp 브라우저 7개 시나리오, JavaScript 22건, TypeScript·lint 통과. HWP/HWPX 부분 서식·표·이미지의 엔진 저장/재열기와 독립 파서 검사를 통과했다. Office DOCX·XLSX·PPTX의 실제 저장/재열기 및 기존 P0 회귀도 Android 검사에 포함한다.
- testOnly 설치 옵션 없이 설치·실행, 기존 0.8.3 병행 설치, 이전 APK 해시/개인 데이터 유지, 새 앱의 같은 서명 재설치 후 개인 데이터 유지 통과.
- 두 ABI의 서명 인증서 일치, APK/ELF 16KiB 정렬, testOnly·INTERNET 권한·배포 APK 내 테스트 IME 부재, 실제 검사한 엔진과 APK 자산의 바이트 일치 통과.
- 소스 ZIP의 모든 추적 파일을 Git blob과 대조했고, 변경 패치 역적용 검사와 Git bundle 검증을 통과했다.

ARM64 APK: 398,868,497 bytes, SHA-256 `bf235ae82fd550a99398a3dadd817e025c0daed3c2865725b25965bf8abd570e`.

x86_64 APK: 410,754,987 bytes, SHA-256 `cad44eedc7d433f7a902b875a442f925d02c20686ed81a15da5d6bfaab806d62`.

개발 서명 인증서 SHA-256: `2b25d82e5ad45128702cfe8e7f60c72e92c01a958212a034cb15ba45b3f53755`. ARM64는 정적 패키지 검사를 수행했으며 실제 ARM64 휴대폰에 설치한 결과로 표시하지 않는다.

[0.8.4-preview Release](https://github.com/Ch-wook/File_Translator/releases/tag/v0.8.4-preview) 게시 완료. [배포 작업](https://github.com/Ch-wook/File_Translator/actions/runs/40426934736)이 성공했으며, APK 2개를 포함한 자산 9개를 인증 없이 다시 다운로드해 크기·SHA-256을 모두 대조했다. [파일별 공개 다운로드 확인 기록](releases/0.8.4-preview-download-verification.json).

APK·소스 빌드 커밋은 `4328146f0e0cc624d1dc41d262e6b649aea3dbc7`, 태그 커밋은 `27075b5afc85feaaa5955576648907d9fc0bbc43`이다. 태그에는 문서·배포 자동화만 보완했고 앱 코드 차이가 없음을 배포 과정에서 검사했다. 소스 ZIP은 8,105,828 bytes, 변경 패치는 344,699 bytes, Git bundle은 8,335,077 bytes, 검증 ZIP은 3,917,469 bytes이다. 초기 43개 HWP 원인 비교 진단은 [이전 진단 실행](https://github.com/Ch-wook/File_Translator/actions/runs/40417174770)이며 위 최신 검사의 개수에 합산하지 않는다.

## 설치와 제한

기존 개인 서명키는 확보하지 못했다. 사용자가 선택한 기존 앱 보존 방식에 따라 `kr.documentstudio.openpreview` / **문서 스튜디오 열기 수정**으로 제공한다. 기존 앱을 삭제하지 않아도 된다. 0.8.3 서명으로 업데이트했다고 표현하지 않는다.

Android 35 x86_64 에뮬레이터 검증과 실제 휴대폰 검증을 구분한다. 실기기·삼성 키보드·Gboard 검증은 미완료다. 암호화 문서는 여전히 지원하지 않으며, 모든 문서의 호환성이나 모든 열기 오류의 해결을 보장하지 않는다.
