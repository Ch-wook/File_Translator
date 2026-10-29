# 문서 스튜디오 0.8.5-preview 작업 보고서·인수인계

작성 기준: 2026-10-27 UTC  
대상: https://github.com/Ch-wook/File_Translator  
문서 개정: 20261027-r1

## 1. 현재 결론

모바일 공통 편집 UI와 실제 글꼴·선택 서식 연결을 개선한 **0.8.5-preview**의 코드 push, APK 빌드, GitHub Release 게시를 완료했다. 앱은 원본 문서를 보기로 연 뒤 사용자가 **편집하기**를 선택해야 수정할 수 있다. 기존 Rhwp 0.8.7·Collabora/LibreOffice·PDF 엔진과 파일 저장 구조를 유지했다.

이 보고서는 완료된 앱 작업을 정리하고 다음 작업자가 이어갈 수 있게 작성한 문서다. 이번 문서 작성·첨부 요청에서는 앱을 다시 빌드하거나 Android 검사를 새로 실행하지 않는다. 아래 앱 검증 수치는 명시한 기존 성공 실행의 결과이며, 문서 배포 검사는 별도 워크플로우로 확인한다.

## 2. 과거 기록과 현재 확인 사실

| 구분 | 사실 |
|---|---|
| 과거 미푸시 0.8.0 | 이전 환경의 소스·패치·커밋을 회수하지 못했다. 원본 복구본으로 표시하지 않는다. 환경 장애가 파일 삭제나 손상을 증명하지는 않는다. |
| 이번 UI 작업의 기준 | 확인 가능한 원격 0.8.4의 63944c500c5437e478f235598158159a36d99428에서 이어서 수정했다. |
| 실제 APK 빌드 커밋 | 10ab10e01e027c35af3083e5d22a9e14b02c4f50 |
| 앱 소스 ZIP·배포 태그 | 8c1ddc7156136d191a94323c49b51847c554da6e / v0.8.5-preview |
| 태그 소스와 APK 빌드의 관계 | 앱·엔진 소스 차이는 없고 문서·CI만 갱신했다. 소스 ZIP의 Git 추적 파일 500개를 태그와 바이트 단위로 대조했다. |
| 본 보고서 작성 전 저장소 상태 | 두 작업 브랜치와 PR #1의 head는 9af7ede06c0f66f70d9958cca3a7babae0c8ae3d였다. 이 커밋은 최종 결과·공개 다운로드 확인서·실제 Android 화면을 포함한다. |
| 실제 휴대폰 종합 검증 | 미완료. Android 35 x86_64 에뮬레이터와 실제 엔진 브라우저 검증을 물리 ARM64 휴대폰 검증으로 표시하지 않는다. |

- 작업 브랜치: codex/hangul-mobile-editing
- PR 브랜치: codex/document-engines
- [PR #1](https://github.com/Ch-wook/File_Translator/pull/1): 열려 있는 draft. main에 병합한 것으로 보고하지 않는다.
- [전체 코드 변경 비교](https://github.com/Ch-wook/File_Translator/compare/63944c500c5437e478f235598158159a36d99428...10ab10e01e027c35af3083e5d22a9e14b02c4f50)

## 3. 구현한 변경

### 공통 화면

- 상단은 뒤로·문서명·실행 취소/다시 실행·저장·더보기 한 줄이다.
- 하단은 자주 쓰는 도구 한 줄이며 상세 설정은 필요할 때 여는 높이 제한 패널이다. 내부 스크롤·내리기·닫기를 지원한다.
- 상시 안내와 중복 도구줄을 줄이고 기존 도구의 접근 위치를 옮겼다. 이미지 선택 등 문맥에 따라 도구가 바뀐다.
- 한글/Office는 shared/editor-ui/dist의 같은 웹 도구·글꼴 패널을 재사용한다. PDF/TXT/Markdown은 MobileEditorControls를 공유한다.
- 한글 눈금자는 기본 숨김이다. 최초 열기는 화면 너비에 맞추며 저장된 보기 위치가 있으면 배율·스크롤을 복원한다. 확대는 글자 크기나 문서 서식을 바꾸지 않는다.
- Office의 표·셀·슬라이드 구조 도구는 더보기에서도 접근할 수 있다.

412×915 브라우저 화면의 전후 측정에서 고정 도구 높이는 216px → 96px, 문서 iframe은 699px → 819px였다. Android에서는 상단·하단 48dp를 검사했다. 브라우저의 412×500 크기는 키보드 높이를 가정한 화면이며 Android IME 검사를 대신하지 않는다.

### 실제 편집·저장 연결

- 실제 번들 또는 엔진이 제공하는 글꼴의 검색·현재/최근 목록·미리보기를 연결했다. 미리보기 제한과 원본의 대체 글꼴을 구분한다.
- 선택 부분에만 서식을 적용하고 다른 글자의 글꼴·크기·색상·문단 속성을 불필요하게 초기화하지 않도록 했다.
- 패널을 연 채 새로 선택한 범위를 갱신한다. 저장 직전에 대기 중인 숫자 서식을 먼저 반영한다.
- 원본과 작업 복사본을 분리하고, 읽기 상태의 문서 쓰기·불필요한 재저장을 차단한다. 기존 충돌·백업·복구와 원래 파일 형식 저장 경로를 유지한다.
- 기존 파일 열기 수정(이름/MIME을 주지 않는 제공자, 배포용 HWP 읽기 보호, Activity RESUMED 이후 호스트 열기, 마지막 한글 호스트 종료 처리)을 유지한다.

### 형식별 범위

| 형식 | 완료·연결한 범위 | 제한·미구현 |
|---|---|---|
| HWP/HWPX | 부분 글꼴·크기·스타일·색/음영, 실제 자간·장평·위치, 문단·목록·표·그림·검색·기존 스타일과 공통 UI | 복잡한 요소는 엔진 보존 검사에 따름. 새 그림 자르기·교체·둥근 모서리, 새 맞춤법·수식·자동 목차·책갈피 도구는 완성 범위 밖 |
| DOC/DOCX | Writer의 글꼴·크기·색·문단·표·개체·삽입·저장과 공통 UI | 자간·장평·고급 문단은 기존 네이티브 대화상자. 별도의 글꼴만 기본값 복원 버튼은 없음 |
| XLS/XLSX | 실제 셀·시트·수식 입력줄·행/열·셀 서식, 공통 글꼴/크기 도구 | 워드 문단 도구를 셀 구조에 강제 적용하지 않음 |
| PPT/PPTX | 슬라이드·텍스트 상자·개체·슬라이드 명령, 공통 도구 | 모든 고급 개체·전환·애니메이션 보존을 보장하지 않음 |
| PDF | 원본 페이지·지원 본문/이미지·주석·양식·페이지 도구와 공통 UI | 기존 본문 임의 글꼴 교체·자간/장평·문단 재배치 미지원 |
| TXT | 직접 입력·선택·클립보드·기존 검색/바꾸기, 보기 글꼴·크기 | 글꼴·색상은 원본 텍스트 파일에 저장되는 서식이 아님 |
| Markdown | 구문 편집·표·목록·제목·링크·미리보기, 공통 UI | 리치 텍스트 글꼴·색상·셀 병합 저장 형식이 아님 |
| HTML | 기존 편집기 없음 | 이번 작업에서 새 지원 형식으로 추가하지 않음 |

구조 있는 문서를 추출 텍스트 입력창으로 대체하지 않았다. 인쇄·인쇄 미리보기·PDF 출력 기능을 새로 추가하지 않았다.

## 4. 확인한 실패 원인과 최종 수정

| 문제 | 확인 내용과 최종 처리 |
|---|---|
| Office 글꼴·크기·색상이 적용되지 않음 | Android의 filterExecCopyPaste가 일반 UNO 명령도 소비하면서 인수를 버렸다. 클립보드만 해당 경로를 사용하고, 서식 인수는 유지하며 크기는 실제 applyFontSize로 전달한다. |
| Office 검색으로 실제 선택이 만들어지지 않음 | 고정 엔진의 실제 app.searchService를 연결하고 네이티브 검색 응답을 기다린다. |
| 저장 직후 하단 도구를 누르지 못함 | Snackbar가 새 하단 도구를 가렸다. 알림을 문서 상단 안쪽으로 옮겼다. |
| 패널에 이전 선택이 남음 | 실제 선택 이벤트와 네이티브 EditText 선택 콜백으로 대상을 갱신했다. |
| 마지막 숫자 서식이 저장에서 빠질 수 있음 | 저장·보기 전환 전에 대기 중인 입력을 반영한다. |
| WebView 자동화의 오래된 객체·버튼 누락 | 접근성 캐시를 새로 조회하고 위치가 안정된 뒤 실제 터치를 주입한다. 가변 BySelector의 조건 중복 지정도 보조 코드에서 수정했다. |
| DOCX 쪽 나누기 검사에서 저장이 활성화되지 않음 | 명령 기록이 비어 있어 실제 버튼 터치가 전달되지 않았음을 확인했다. CSS 스크롤 패널의 버튼을 먼저 표시한 뒤 실제 터치하고, 네이티브 명령 발생을 별도 검사한다. 최종 코드의 일반 UNO 디스패치를 유지했다. |
| 에뮬레이터 Quickstep ANR | 별도 AOSP 런처 문제로 구분하고 해당 런처만 종료했다. 앱 ANR이나 실제 실패를 숨기거나 통과 처리하지 않았다. |

실패한 중간 빌드를 배포 결과로 사용하지 않았다. 최종 성공 실행의 Android 27건에서 실패·오류·건너뜀이 모두 0건이어야 APK 배포 단계로 진행했다.

## 5. 실제 검증 결과

[앱 빌드·전체 검증](https://github.com/Ch-wook/File_Translator/actions/runs/40494582093) — 2026-10-27 성공.

| 검증 | 결과·범위 |
|---|---|
| Android 35 x86_64 | 27건, 실패·오류·건너뜀 0건 |
| core 단위 | 122건 통과 |
| Office 단위 | 2건 통과 |
| app Robolectric | 19건 통과 — 단위 합계 143건 |
| JavaScript | 20건 통과 |
| TypeScript·lint | 통과 |
| 실제 Rhwp/Chromium | 7개 시나리오(기존 5 + 모바일 HWP/HWPX 2) 통과 |
| HWP/HWPX 저장 | 부분 서식·인접 글자·실제 자간/장평 폭 변화·표·그림·undo·자동 저장·저장/재열기 및 독립 파서 통과 |
| Android 입력·선택 | 테스트 IME의 실제 InputConnection 한글 조합, 반복 음절·선택 교체·표 셀 입력·클립보드, 두 포인터 확대 등을 확인 |
| Writer 부분 서식 | 실제 Noto Sans KR Thin·18pt·굵게·빨간색, 인접 글자와 모든 원문 문단 보존, 독립 DOCX XML 및 엔진 재열기 통과 |
| Office 구조 저장 | 실제 버튼·명령 기록으로 DOCX 쪽 나누기, XLSX 열 삽입, PPTX 슬라이드 추가 후 파서·엔진 재열기 통과 |
| TXT/MD/PDF | TXT 보기 글꼴의 선택·파일 바이트 유지, MD 새 선택 범위의 구문 저장/재열기, PDF 같은 캔버스의 보기 배율만 변경 |
| 설치·보존 | x86_64 일반 설치, 0.8.4 병행 설치·이전 APK/개인 데이터 보존, 동일 서명 재설치 확인 |
| APK 정적 검사 | 두 ABI 서명 일치, 16KiB ELF/APK 정렬, testOnly 없음, INTERNET 권한 없음, 엔진·공통 UI 자산 바이트 일치 |

실제 ARM64 휴대폰·삼성 키보드·Gboard의 종합 검증은 하지 못했다. DOC/XLS/PPT는 네이티브 읽기 표시를 확인했고, 구조 편집 저장 검사는 DOCX/XLSX/PPTX 샘플 기준이다. 모든 문서·고급 요소의 완전한 서식 보존이나 상용 프로그램과 같은 기능을 보장하지 않는다.

## 6. 실제 수정 파일

| 영역 | 파일 |
|---|---|
| 공통 웹 UI | shared/editor-ui/dist/studio-editor-ui.js, studio-editor-ui.css |
| 한글 | web/hangul/index.html, host.css, host.js, mobile-tools.js, engine-adapter.ts, studio-mobile.ts, studio-touch.ts |
| 네이티브 UI | app/src/main/java/kr/documentstudio/ui/MobileEditorControls.kt, FormatWorkspace.kt, StudioApp.kt, LineNumberEditText.kt, PdfCanvasEditor.kt |
| Office | officeruntime/src/main/assets/dist/studio-policy.js, COWebViewClient.java, LOActivity.java, officeruntime/build.gradle.kts |
| 앱·준비 | app/build.gradle.kts, app/src/main/AndroidManifest.xml, scripts/prepare-hangul-engine.py |
| 검증·배포 | CommonMobileUiTest.kt, NativeOfficeFontTest.kt, NativeUiActions.kt, 기존 Android 회귀 테스트, scripts/tests/mobile-ui-layout.cjs, hangul-mobile*.cjs, office-workspace.test.cjs, run-android-mode-tests.sh, package-mobile-release.py, test-mobile-installation.py, 관련 CI |

정확한 전체 경로·diff·실제 화면은 [상세 결과 보고서](https://github.com/Ch-wook/File_Translator/blob/codex/hangul-mobile-editing/docs/COMMON_MOBILE_UI_RESULTS.md)와 위 코드 비교 링크를 기준으로 확인한다.

## 7. 설치·Release·무결성

앱 이름: **문서 스튜디오 모바일 UI**  
applicationId: kr.documentstudio.compactpreview  
versionCode: 15 / versionName: 0.8.5-preview  
개발 서명 프리뷰이며 기존 앱을 보존하는 별도 설치 방식이다. Android 10 이상 64비트, Office는 Android 11 이상이 필요하다.

- [휴대폰용 ARM64 APK](https://github.com/Ch-wook/File_Translator/releases/download/v0.8.5-preview/document-studio-0.8.5-preview-arm64-v8a.apk): 404,673,144 bytes, 약 404.7 MB.
- [x86_64 APK](https://github.com/Ch-wook/File_Translator/releases/download/v0.8.5-preview/document-studio-0.8.5-preview-x86_64.apk): 416,559,634 bytes.
- [전체 소스 ZIP](https://github.com/Ch-wook/File_Translator/releases/download/v0.8.5-preview/document-studio-0.8.5-preview-source.zip): 8,159,369 bytes.
- [검증 ZIP](https://github.com/Ch-wook/File_Translator/releases/download/v0.8.5-preview/document-studio-0.8.5-preview-verification.zip)
- [APK·소스 등 기존 배포 파일 SHA256SUMS](https://github.com/Ch-wook/File_Translator/releases/download/v0.8.5-preview/SHA256SUMS)
- [Release](https://github.com/Ch-wook/File_Translator/releases/tag/v0.8.5-preview)

| APK | SHA-256 |
|---|---|
| ARM64 | ead1417f3dd87aba5d8bab2525b0c2f3d6b503df2c9a055d6853add787cfcb8d |
| x86_64 | 51de5e0476d27b3bc6bb0c28cb069feb6834ee6c0be1123ffd0cb541eedc4dbf |

[기존 배포·공개 다운로드 검증 실행](https://github.com/Ch-wook/File_Translator/actions/runs/40496987073)에서 원래 자산 9개의 크기·SHA-256을 인증 없는 다운로드로 확인했다. [기존 확인서](https://github.com/Ch-wook/File_Translator/blob/codex/hangul-mobile-editing/docs/releases/0.8.5-preview-download-verification.json).

이번 작업 문서 첨부는 기존 앱 태그·APK·소스·원래 SHA256SUMS와 분리한다. 문서 전용 워크플로우는 원래 9개 자산의 ID·크기·해시와 태그 보존, 추가 문서 파일의 실제 공개 다운로드를 검사한다. 문서 묶음은 독립 실행 프로젝트나 앱 소스 ZIP을 대신하지 않는다.

휴대폰에서는 위 APK/Release 링크를 브라우저로 열어 ARM64 파일을 설치한다. 기존 앱의 작업 중인 문서는 기존 앱에서 파일로 저장한 뒤 새 프리뷰의 파일 열기에서 선택한다. 이전 앱·개인 데이터를 삭제하는 절차를 전제로 하지 않는다.

## 8. 이어서 할 작업

1. 실제 ARM64 휴대폰에서 HWP/HWPX·DOC/DOCX·XLS/XLSX·PPT/PPTX·PDF의 보기 시작·원본 표시를 확인한다.
2. 삼성/Gboard로 빠른 조합 입력, 선택 핸들, 패널·키보드 전환, 표 셀 입력과 undo/redo를 확인한다.
3. 긴 문서·복잡한 표·그림·수식·슬라이드 샘플에서 원본 해시와 수정하지 않은 요소를 비교하며 저장·앱 재실행·재열기를 확인한다.
4. 고급 형식별 도구는 엔진 지원과 원형식 저장 보존을 검증한 범위부터 확장한다. 작동하지 않는 아이콘을 정상 버튼으로 노출하지 않는다.
5. 같은 applicationId의 다음 업데이트에는 서명 연속성이 필요하다. 비공개 키 복구·보관이 확인되지 않았다면 기존 설치를 덮어쓰는 업데이트로 안내하지 않는다.
6. 소스를 먼저 보존하고 수정한다. 확인되지 않은 과거 소스 복구, 물리 기기 통과, 환경 장애 원인, 지원 요청 접수를 완료 사실로 쓰지 않는다.

## 9. 재현·후속 개발 경로

기존 프로젝트와 엔진·저장 구조를 유지한다. 기준은 검증 커밋과 Release의 소스/패치/Git bundle이며, 문서 변경만으로 앱 버전을 올리거나 APK를 재빌드하지 않는다.

- 앱 빌드·검증: .github/workflows/mobile-release.yml
- 앱 배포: .github/workflows/publish-mobile-preview.yml
- 이번 문서 배포: .github/workflows/publish-work-report.yml
- 주요 도구: JDK 17, Node.js 24, Python 3.12, SDK 36, Build Tools 35.0.0, NDK 28.2.13676358, CMake 3.22.1.
- 실제 ARM64 기기가 없는 실행을 물리 휴대폰 검증으로 대체하지 않는다.

GitHub 웹에서 소스·PR·Release·Actions를 확인하거나, 연결된 ChatGPT GitHub 도구와 기존 Actions를 이용해 후속 작업을 진행할 수 있다. 토큰이나 서명키를 채팅·문서·명령행·Git URL에 넣지 않는다. 비공개 환경 식별자는 공개 보고서에 포함하지 않는다.

## 10. 관련 문서

- [분석 기준·구조 감사](https://github.com/Ch-wook/File_Translator/blob/codex/hangul-mobile-editing/docs/COMMON_MOBILE_UI_AUDIT.md)
- [형식별 결과·실제 Android 화면](https://github.com/Ch-wook/File_Translator/blob/codex/hangul-mobile-editing/docs/COMMON_MOBILE_UI_RESULTS.md)
- [사용 안내](https://github.com/Ch-wook/File_Translator/blob/codex/hangul-mobile-editing/docs/USER_GUIDE.md)
- [지원 범위](https://github.com/Ch-wook/File_Translator/blob/codex/hangul-mobile-editing/docs/SUPPORT_MATRIX.md)
- [문서 열기 오류 원인·검증](https://github.com/Ch-wook/File_Translator/blob/codex/hangul-mobile-editing/docs/DOCUMENT_OPEN_FIX.md)
- [기존 설치 실패 분석](https://github.com/Ch-wook/File_Translator/blob/codex/hangul-mobile-editing/docs/INSTALLATION_FIX.md)
