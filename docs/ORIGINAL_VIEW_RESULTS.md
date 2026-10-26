# 검증 결과와 제한 — 0.8.1-preview

이 버전은 확인 가능한 원격 `2bfe225`에서 **재구현**했습니다. 이전 미푸시 0.8.0 소스·패치·커밋·APK는 회수하지 못했습니다. [출처 감사](ORIGINAL_VIEW_AUDIT.md)와 [실패 분석](FAILURE_ANALYSIS.md)을 확인하세요.

APK와 소스 ZIP은 [검증한 앱 빌드 커밋](https://github.com/Ch-wook/File_Translator/commit/2b1bfcd327a5eba2a48166adb33867dfee880938)에 정확히 대응합니다. 검사한 동일 APK를 다시 빌드하지 않고 배포합니다. 이후 배포 문서·CI만 추가했는지 게시 작업에서 검사하며, 태그는 위의 실제 빌드 커밋을 가리킵니다.

## 실제 수정 파일

- 공통 모드·열기/복원·저장 보호: `DocumentMode.kt`, `EditorViewModel.kt`, `DocumentRepository.kt`, `MainActivity.kt`, `StudioApp.kt`.
- 한글 엔진·네이티브 호스트: `HangulEditorActivity.kt`, `web/hangul/host-readonly.ts`, `patch-engine.py`, `engine-adapter.ts`, `host.js`, `host.css`, `index.html`.
- Office: `studio-policy.js`, `LOActivity.java`, `StudioOfficeActivity.java`, `COWebViewClient.java`, `DocumentViewState.java`, `OfficeEditPolicy.java`.
- TXT/MD/PDF 원본 보기: `FormatWorkspace.kt`, `PdfCanvasEditor.kt`, `VisualDocumentWorkspace.kt`.
- Android DOM 호환: `TextRuns.kt`. 관련 JVM·JavaScript·Android 시험과 엔진 준비·CI·출처/사용 안내 문서도 수정했습니다.

[기준 원격부터 앱 빌드까지 전체 변경](https://github.com/Ch-wook/File_Translator/compare/2bfe225ab040e3cb9251a8cafce9578ea3d9b645...2b1bfcd327a5eba2a48166adb33867dfee880938)에서 파일별 실제 diff를 확인할 수 있습니다.

## 실행 결과

| 검사 | 통과 수/결과 |
|---|---|
| core JUnit | 117 |
| Office Java 권한 검사 | 2 |
| 앱 Robolectric | 13 |
| JavaScript 권한·도구·위치 복원 | 21 |
| 실제 Rhwp/Chromium 시나리오 | 5 |
| TypeScript / 독립 HWP·HWPX 파서 / Android lint | 통과 |
| debug APK / AndroidTest APK 빌드 | 성공 |
| Android 35 x86_64 에뮬레이터 | 19건, 실패·오류·생략 0 |

최종 Actions 로그와 Release verification ZIP의 JUnit/JSON이 실행 결과의 근거입니다. `continue-on-error` 때문에 Actions 단계가 초록색이어도 Android 통과로 간주하지 않습니다. 게시 작업은 실제 Android outcome이 success이고 선택한 P0 인수 19건의 failures/errors/skipped가 0인지 재확인합니다.

## 형식별 실제 검사

| 형식 | 이번에 실행한 시나리오 |
|---|---|
| HWP/HWPX | 실제 Rhwp에서 한국어 텍스트 입력·undo/redo·저장/재열기, HWPX 표·기존 셀/굵게 일부 보존. Chromium 데스크톱/휴대폰 화면의 보기 입력·명령·개체 드래그 차단과 종료 쓰기 0회. Android WebView에서 원본 보기·명시적 모드 전환·두 확대율·보기 상태 파일 해시 보존·뒤로 닫기. |
| DOC/DOCX | 실제 Android Writer에서 VIEW 진입·입력 시도·닫기·원본/기준/작업 파일 해시 보존. 긴 DOCX를 스크롤한 뒤 보기↔편집 전환의 위치·확대 보존. |
| XLS/XLSX | 실제 Android Calc에서 VIEW·입력 시도·닫기·세 파일 해시 보존. XLSX 열 삽입 후 A1의 기존 내용이 B1에 유지되는지 검사. |
| PPT/PPTX | 실제 Android Impress에서 VIEW·입력 시도·닫기·세 파일 해시 보존. PPTX 슬라이드 추가 후 기존 텍스트와 2개 슬라이드 유지 검사. |
| PDF | Android에서 동일 페이지 캔버스 인스턴스를 유지하며 보기/명시적 편집을 전환하는 UI 검사. 지원 콘텐츠 편집·원본 보호는 별도 JVM 회귀 검사에 포함. |
| TXT | Android에서 보기의 입력 차단, 명시적 편집 후 한국어 문자열 저장·재열기. EditText.setText를 사용했으며 실제 한국어 IME 조합 검사가 아님. |

Office 수정 저장 3건은 **DOCX 쪽 나누기, XLSX 열 삽입, PPTX 슬라이드 추가**를 실제 UI 명령으로 실행했습니다. 네이티브 Save 확인 → 종료 요청 기록 → 파일 잠금 해제/해시 확인 → 승인된 작업 복사본 가져오기 → 원본 저장 → 독립 파서 및 엔진 재열기를 검사했습니다. 수정 전 원본과 이전 기준 파일이 임의 변경되지 않는지도 확인했습니다.

## 배포 파일 검사

- APK 서명·ZIP CRC·arm64-v8a/x86_64·WASM 포함·INTERNET 권한 부재.
- APK 안의 한글 엔진 자산을 시험한 생성 자산과, Office 정책을 해당 소스와 바이트 단위로 비교.
- 전체 추적 소스 ZIP의 파일 목록과 Git blob 해시 일치, 바이너리 변경 패치의 역적용 가능성, Git bundle 무결성.
- 원래 빌드의 SHA256SUMS로 재사용할 모든 파일 검증.
- Release를 초안으로 올린 뒤 모든 자산을 다시 내려받아 크기·SHA-256을 대조한 후 공개. 공개 태그의 커밋과 인증 없는 실제 다운로드도 대조.

검증 ZIP의 `artifacts/ci/summary.json`과 `android-summary.json`에서 빌드 커밋·모듈별 수·실제 Android outcome·실제 기기 미검증 여부를 확인할 수 있습니다. Android PNG/XML/JSON, 입력·출력 문서와 로그도 포함합니다. 공개 다운로드 검사는 별도 게시 Actions 로그와 `published-preview-download-verification` 자료에 기록됩니다.

## 남은 한계

실제 Android 휴대폰은 검증하지 않았습니다. 실제 한국어 IME 조합·터치 텍스트/개체 선택·드래그/크기 변경·회전·다양한 저장 제공자·대형/복잡한 문서는 추가 인수가 필요합니다.

Office 수정 저장 검사는 위의 단순 구조 편집 샘플 3종입니다. DOC/XLS/PPT 수정 저장, Office 임의 본문 입력 및 고급 표·이미지·수식·애니메이션 전체를 실기기에서 확인한 결과가 아닙니다. Android의 스크롤한 모드 전환 검사는 DOCX와 한글을 중심으로 수행했습니다.

이번 한글 엔진 왕복 샘플에는 그림이 없어 그림 보존을 통과 항목으로 보고하지 않습니다. Chromium의 한국어 텍스트 삽입과 Android EditText.setText도 실제 IME 조합을 대신하지 않습니다. 모든 파일·고급 요소의 무손실 보존이나 상용 프로그램과의 기능 동등성을 보장하지 않습니다.
