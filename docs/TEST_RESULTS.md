# 검증 결과 — 0.4.0

2026-10-17 (Asia/Seoul). 업데이트된 원격 e540106을 새로 복제한 뒤 실행 계획에 따라 구현하고 다시 검사했다. 코드 기준은 f561ea5이며 이후에는 결과 문서만 갱신했다. 이전 0.3.1 검사와 이번 결과를 합산하지 않는다.

## 최종 자동 회귀

**178개 통과(JVM 88 + Android 90), 실패·오류·건너뛰기 0.** 휴대폰 전체 게이트는 `artifacts/acceptance-20261017-104157/summary.json`에 있으며 status=passed, full_automated_gate=true다. 모든 집계 XML은 해당 실행에서 새로 생성됐다. lint 오류 0, 경고 31개. 경고의 세부 ID는 같은 폴더의 lint.xml을 따른다.

환경은 Windows/JDK 17, Android 15/API 35 x86_64, 원래 CodexBibleApi35 AVD의 포트 5556 읽기 전용 인스턴스다. 폰은 1080×2400 px/420 dpi/4 KB 페이지, 태블릿 창은 2560×1600 px/240 dpi다. 원래 AVD 사용자 자료를 변경하지 않았다. 태블릿에서도 **Android 90개 전체**를 새로 실행해 실패/오류/건너뛰기 0을 확인했다. 별도 `artifacts/tablet-regression/`에 보관하며 두 화면의 같은 검사를 고유 검사 수에 중복 합산하지 않는다.

| 환경 | 검사 묶음 | 통과 |
|---|---|---:|
| jvm | CoreTest | 15 |
| jvm | EncryptedBackupTest | 3 |
| jvm | FeatureTest | 7 |
| jvm | FormulaCatalogTest | 3 |
| jvm | FormulaLimitsTest | 3 |
| jvm | HangulFormattingTest | 2 |
| jvm | HangulTablesTest | 5 |
| jvm | InkAdvancedTest | 3 |
| jvm | InkAnchorsTest | 3 |
| jvm | InkErasureTest | 8 |
| jvm | InkTest | 2 |
| jvm | LegacyTest | 4 |
| jvm | MarkdownEditingTest | 1 |
| jvm | OfficeCompatibilityTest | 4 |
| jvm | OfficeObjectsTest | 6 |
| jvm | SheetDataFeaturesTest | 4 |
| jvm | SheetOperationsTest | 9 |
| jvm | SpreadsheetModelTest | 5 |
| jvm | TemporaryFilesTest | 1 |
| Android | BackupSafetyTest | 3 |
| Android | CellInkIntegrationTest | 1 |
| Android | CellInkUiTest | 1 |
| Android | DocumentActionsTest | 2 |
| Android | DocumentIntegrationTest | 25 |
| Android | DocumentTreeTest | 3 |
| Android | EditingWorkflowTest | 8 |
| Android | EditorUiTest | 6 |
| Android | EncryptedBackupIntegrationTest | 2 |
| Android | EncryptedBackupUiTest | 1 |
| Android | FeatureAcceptanceUiTest | 5 |
| Android | InkAdvancedPersistenceTest | 2 |
| Android | InkAdvancedUiTest | 1 |
| Android | InkInputTest | 2 |
| Android | MarkdownTasksTest | 1 |
| Android | MarkdownUiTest | 1 |
| Android | OcrIntegrationTest | 3 |
| Android | OcrUiTest | 1 |
| Android | OfficeBridgeTest | 6 |
| Android | OfficeProfessionalFeaturesTest | 3 |
| Android | PdfAnnotationsTest | 4 |
| Android | PdfImageEditingTest | 4 |
| Android | PdfInkAppearanceTest | 1 |
| Android | PdfMarkupUiTest | 1 |
| Android | SheetDataUiTest | 1 |
| Android | SlideMemoryTest | 1 |
| Android | SplitEditorUiTest | 1 |

추가 기능은 분할 문서/SAF 폴더, PDF 문자 주석/관리/가로 보기, 필압/보정/프리셋/레이어/종이, Markdown/HWPX, 셀 연결 필기, OCR, 암호 백업/임시 정리, 시트 규칙/표다. 실제 UI 입력·Undo/Redo·저장/새 URI 재열기와 POI/hwpxlib/PDFBox 독립 검사를 연결했다. 상세는 [실행 계획](EXECUTION_PLAN_20261017.md)과 [인수 확인표](FEATURE_ACCEPTANCE.md)를 따른다.

## 실제 전문 엔진

OfficeProfessionalFeaturesTest는 앱 내부의 실제 네이티브 엔진을 실행한다. Writer 제목 1/번호/각주 텍스트/추적 삽입·설정, Calc 셀 12/30·SUM(A1:B1)의 수식과 캐시 42/통화 서식, Impress 슬라이드 추가·레이아웃 관계 유지를 저장한 OOXML에서 확인한다. Impress의 특정 레이아웃별 시각적 배치 일치까지 검사한 것은 아니다. debug 전용 동일 UID 검사 provider를 사용하며 WebView 원격 디버깅은 켜지 않는다. 메뉴가 존재한다는 이유로 모든 고급 기능·외부 프로그램 호환성이 검증된 것으로 세지 않는다.

## 출시와 전달 파일

0.4.0/code 5의 APK/AAB를 기존 비공개 출시 키로 서명했다. apksigner/jarsigner, 권한·디버그 provider 제외, 16 KB ZIP 정렬과 36개 ELF LOAD 정렬·APK/AAB 라이브러리 일치를 확인했다. 실제 16 KB 기기 실행/Play 업로드는 하지 않았다. `artifacts/test-results/release-verification.json`에 인증서 지문/해시/정렬 근거가 있다.

격리 에뮬레이터에서 debug 테스트 앱을 출시 APK로 교체 설치하고 TXT 생성·입력·복구 상태 저장·프로세스 강제 종료/재시작·재열기를 확인했다. 실제 릴리스 앱의 입력 바이트와 화면은 `artifacts/release-smoke/`에 있다.

전달 ZIP을 D 드라이브의 새 폴더에 풀어 고정 엔진 캐시/로컬 SDK 경로만 제공하고 JVM 88개, debug APK와 검사 APK를 새로 빌드했다. 최종 ZIP의 253개 빌드 입력이 이 새 빌드 소스와 바이트 단위로 일치하며 키/암호/빌드/캐시를 제외했다. 결과 문서만 이후 갱신했다. `artifacts/test-results/source-verification.json`, `artifacts/source-rebuild.log`, 최종 `artifacts/SHA256SUMS.txt`를 따른다.

## 실패 실행과 제한

첫 전체 실행은 178개 중 UI 3개가 실패했다. 앞선 검사의 접힌 펜 도구/지우개 설정 의존성과 OCR 버튼 추가로 화면 밖으로 이동한 미리보기 조작을 발견했다. 검사별 입력 설정 초기화와 실제 스크롤 후 클릭으로 고친 뒤 위 전체 게이트와 태블릿 전체를 통과했다. 초기 전문 엔진 테스트의 모바일 Base64 규약, 셀 TSV 가져오기 UI, 시트 테스트의 출력 파일 준비도 보완했다. 에뮬레이터 연결 없음/설치 공간 부족으로 실행하지 못한 시도도 성공으로 합산하지 않았다.

한글 전문 조판/변환, 정밀 조판 뒤 문단·셀 필기 재배치, PDF/A 전체 적합성, 실제 Galaxy/S펜·Bluetooth·다른 API/16 KB, 외부 Microsoft Office/한컴 코퍼스와 공개 출시 감사는 남아 있다. [남은 인수 조건](PROFESSIONAL_VALIDATION.md)을 참고한다. 전체 원문이 완벽히 구현됐다는 판정은 아니다.

이전 Windows 빌드 중간 파일 삭제는 자동 승인 검토에서 정책상 차단되어 수행하지 않았다. 별도 D 드라이브 빌드를 사용했으며 이를 다른 수단으로 삭제하지 않았다.

---

## 이전 0.3.1 기록 (현재 실행 횟수에 합산하지 않음)

# 검증 결과 — 0.3.1

2026-10-16(Asia/Seoul). 이번 편집 흐름 재검증은 Windows/JDK 17, Android 15/API 35 x86_64 에뮬레이터 StudioReviewApi35에서 수행했습니다. 이전 0.3.1 검증 환경은 CodexBibleApi35입니다. 실제 기기 검증과 구분합니다. API 36 에뮬레이터는 앱 실행 전 시스템 UI 응답 없음이 발생해 이번 UI 통과 환경에 포함하지 않았습니다.

## 자동 검사

| 환경 | 검사 묶음 | 통과 |
|---|---|---:|
| JVM | CoreTest | 15 |
| JVM | FeatureTest | 7 |
| JVM | FormulaCatalogTest | 3 |
| JVM | FormulaLimitsTest | 3 |
| JVM | HangulTablesTest | 5 |
| JVM | InkErasureTest | 8 |
| JVM | InkTest | 2 |
| JVM | LegacyTest | 4 |
| JVM | OfficeCompatibilityTest | 4 |
| JVM | OfficeObjectsTest | 6 |
| JVM | SheetOperationsTest | 9 |
| JVM | SpreadsheetModelTest | 5 |
| Android | DocumentIntegrationTest | 25 |
| Android | EditingWorkflowTest | 8 |
| Android | EditorUiTest | 6 |
| Android | OfficeBridgeTest | 6 |
| Android | BackupSafetyTest | 3 |
| Android | FeatureAcceptanceUiTest | 5 |
| Android | InkInputTest | 2 |
| Android | PdfImageEditingTest | 4 |
| Android | SlideMemoryTest | 1 |

**총 131개 통과(JVM 71 + Android 60), 실패·오류·건너뛰기 0.** `scripts/verify-editing.ps1`로 cleanTest/core:test, connectedDebugAndroidTest, lintDebug, assembleDebug, assembleDebugAndroidTest를 실행했습니다. lint 오류 0, 경고 22개입니다. 경고는 의존성 버전 안내 9개, UseKtx 13개이며 DrawAllocation 진단은 7개에서 0개로 줄었습니다. 이는 모든 간접 그리기 경로의 할당이나 하드웨어 지연이 없어졌다는 의미가 아닙니다.

최종 자동 검사 근거는 `artifacts/acceptance-20261016-180215/`의 summary.json·JVM/Android XML·lint·Gradle 로그입니다. 상태 passed, full_automated_gate=true이며 모든 집계 XML이 이번 실행에서 새로 생성되었습니다. 환경은 API 35/x86_64/4 KB 페이지, 720×1600 px/280 dpi입니다. debug APK SHA-256은 `DFC3A4C2F850085AB930C9D98C2065216C19B93ACF33C361B3EFF355189F8C2B`입니다. 생성물은 Git에 포함하지 않습니다. 이전 107개 재검증 근거는 `artifacts/editing-review/`에 별도로 유지합니다.

## 계획에 따른 기능 보완·인수 검사

[계획서](IMPLEMENTATION_PLAN.md)를 먼저 작성하고 [인수 확인표](FEATURE_ACCEPTANCE.md)에 원문의 세부 기능·지원 경로·증거·남은 검증을 연결했습니다. 이번에 24개 검사(JVM 9 + Android 15)를 추가했습니다.

| 문제·공백 | 변경과 검증 |
|---|---|
| ZIP 복원 뒤 실패 작업 파일/원본 백업이 다음 ZIP에서 누락 | 격리 보관·재내보내기·이름 충돌 시 두 자료 유지. 두 번 복원/재백업 및 안전하지 않은 경로 거부 검사 |
| 여러 Repository의 인덱스 수정 경합 | 프로세스 공용 Mutex. 12개 동시 생성/즐겨찾기의 누락 없음 검사 |
| 작업 중 ACTION_VIEW/SEND 무시 | 외부 요청 대기열과 허용되는 지속 URI 권한. 실제 onNewIntent 두 번·기존 입력/재생성 검사 |
| 함수 검색/완성 없음 | 16개 함수의 한국어/이름 검색·사용법/예제·접두어 완성. 예제 전부 계산, 실제 UI/저장 파일의 결과 검사 |
| HWPX 표 도구 없음 | 생성·행열·내용 보존 병합/분할·크기·서식·위치 라벨. 별도 hwpxlib의 스팬/크기/문단/서식 확인, 잘못된 범위/최소 행열 보호, UI 생성/Undo/입력/저장 |
| 기존 PDF 이미지 이동/크기 도구 없음 | 직접 이미지 출현 단위 변환/삭제. 공유 이미지의 다른 출현 유지, 0/90/180/270°·CropBox·실제 렌더 픽셀, 클리핑/오래된 선택/범위 거부, UI/Undo/파일 검사 |
| 필기 역사 기울기와 반복 할당 | 각 역사 기울기 보존, 완료 획 불변성, 도구/압력/투명도 렌더 검사. Paint/Path 재사용·샘플별 전체 복사 제거 |
| 슬라이드 이미지 수명·합계 메모리·숫자 순번 | 키별 새 장면과 IO 소유권, 공유 자원 재사용·800만 화소 한도, 해제 확인. 그림이 포함된 연속 구조 수정/Undo/재생성/그리기와 독립 POI의 숫자 순번 이동/크기/회전/이미지 바이트 검사 |

초기 검사에서 Android의 ZIP 선제 차단 예외와 ActivityScenario의 인텐트 식별 조건을 확인해 검사 자체도 보완했습니다. 실패한 실행의 보고서를 최종 통과 보고서로 재사용하지 않았습니다. URI 플래그의 lint 오류는 허용 상수로 분기하도록 수정했고 마지막 전체 게이트에서 오류 0을 확인했습니다.

수동으로 최종 APK의 휴대폰 창에서 `=su` 입력·SUM 완성 버튼·키보드와 함께 보이는 셀 표를 확인했습니다. 태블릿 창(1920×1200 px/240 dpi)에서는 HWPX 3×3 표를 실제 메뉴로 만들고 첫 셀에 `TABLE_OK`를 입력해 SAF Downloads의 `acceptance-table.hwpx`로 저장했습니다. 가져온 실제 파일(6,896바이트)의 section0.xml에서 rowCnt=3/colCnt=3/첫 셀 TABLE_OK를 확인했습니다. 샘플 SHA-256은 `A350709FE7405760BB6A9730856387BE662B16C4558A50FB861A8830D14D5059`입니다. 키보드 입력/저장됨 화면과 샘플도 위 근거 디렉터리에 보관합니다. 한컴 프로그램의 시각적 재열기 검사는 아닙니다.

원문의 미지원 전문 기능, 정밀 엔진 세부 메뉴의 독립 검증, 실제 Galaxy/S펜·외부 앱·PDF/A 표준 인증은 이 자동 게이트와 별개이며 확인표에 남겼습니다. “원문 기능 모두 완벽”으로 판정하지 않습니다.

## 편집 흐름 추가 점검

- 체크를 별도로 누르지 않은 셀 입력의 선택 변경·즉시 XLSX 저장·복구, 셀별 Undo/Redo와 화면 재생성.
- 실제 셀 서식 대화상자에서 숫자 형식을 변경한 뒤 `7.00`이 보이는지 확인. 키보드가 열린 작은 화면에서 표 높이가 0이 되는 문제를 발견해 계산/서식 버튼을 한 줄로 배치하고 높이 부족 시 안내·행열 이동 버튼을 접도록 수정했습니다. 범위 입력은 유지합니다.
- 범위 내용 지우기와 실행 취소 후 선택 셀 입력창 갱신, 마지막 XLSX/XLS 주소의 실제 표시와 XLS 범위를 넘는 입력 거부.
- 최근 목록·동일 제공자 URI에서 열린 탭을 다시 선택한 뒤 실행 취소 이력 유지.
- 포커스된 기본 텍스트 편집기의 Ctrl 단축키, 작업 중 Undo/홈 이동 거부, 명령 실패 직전 입력의 복구본 보존.
- 정밀 편집 시작 실패 후 복구 메뉴 표시, Undo 이후 경고 유지와 보관 후 재시도.
- 연속 설정 변경 40회와 마지막 선택의 기록, 새 ViewModel에서 같은 설정 복원.
- 기존 텍스트/필기/회전 검사로 홈 이동이 되돌아가는 상태 경합을 재현했습니다. 중단되는 IO 호출을 `UiState.copy` 인수에서 분리해 최신 상태에 결과를 반영하도록 수정한 뒤 같은 검사와 전체 45개를 통과했습니다.

기존 UI 검사도 비동기 명령의 준비 완료를 기다리도록 보완했습니다. 비트맵 수명·백그라운드 계산·변경 없는 탭의 저장 생략은 코드 검토와 기존 화면/저장 회귀 검사로 점검했습니다. 장시간 메모리·프레임 시간·실제 스타일러스 지연 벤치마크를 수행했다는 의미는 아닙니다.

수동 화면 확인에서는 720×1600 px/280 dpi 휴대폰에서 키보드를 연 채 A1에 `42`를 입력하고 셀 표에서도 같은 값이 보이는 것을 확인했습니다. 별도 셀 적용 없이 상단 저장으로 실제 SAF Downloads 제공자에 `editing-review.xlsx`를 생성했으며, 저장 파일을 가져와 워크시트 XML의 A1=`42`를 확인했습니다. 1920×1200 px/240 dpi(1280×800 dp) 태블릿 창에서도 탐색 패널·입력창·표·저장 상태를 확인했습니다. 샘플 XLSX·화면 PNG·SHA-256은 artifacts/editing-review에 보관합니다.

아래 정밀 엔진 수동 편집과 출시 서명·배포 검사는 **이전 0.3.1 개발 기록**입니다. 이번 추가 검토는 debug APK 기준이며 출시 키 생성·release APK/AAB 재서명·스토어 제출은 수행하지 않았습니다.

이번 추가 검사는 다음을 확인합니다.

- PDF 연속 스크롤·이전/다음 이동·화면 재생성·같은 페이지의 필기 진입과 앵커 보존. 페이지 렌더와 목록 가상화를 사용합니다.
- 부분 획 지우기의 빠른 경로·화면 비율·필압/기울기 보간·도형 분할·앵커/스타일 보존. 실제 UI 제스처·Undo/Redo·설정/화면 재생성과 PDF를 새 파일로 재열었을 때의 조각/메타데이터·렌더된 빈 구간을 확인합니다.

- 범위 복사/채우기·겹침·삽입/삭제·정렬·같은/다른 시트 참조와 상대/절대 수식, 인용된 시트 이름·외부/공유 수식 보호.
- 실제 Android 클립보드 TSV 붙여넣기·Undo/Redo·화면 재생성, 날짜/백분율·1904 날짜, TEXT 날짜, 와일드카드 이스케이프와 악의적인 비교 작업량 제한.
- 독립 POI로 표 행열/병합/분할·이미지·머리글/바닥글·링크·슬라이드 개체 순서 재열기.
- 기존 PDF Tj/TJ 본문 교체와 후속 문자열 위치 보정, 원래 폰트가 지원하지 않는 글자 거부, 원본 보존. 북마크 추가/이름/삭제·페이지 이동/삭제 목적지와 작은 축소판.
- 정밀 엔진 JNI 로그 보호 초기화, 작업 복사본의 기존 편집 반영, 이전 베이스 불변·필기·revision·복구 유지, 활성 엔진 잠금 거부, 손상된 종료 파일의 이전 베이스 유지와 보관 후 재시작.
- 정밀 편집 중 종료 후 원본 URI를 다시 열어도 복구본을 우선하며, 다른 화면의 새 본문 수정은 가져오기로 덮어쓰지 않습니다.
- 기존 인코딩·ZIP/XML 제한·OOXML/OLE/HWPX 재열기·표준 Ink·양식·구조/필기 이동·원본 충돌·제공자 기록 실패/복원 실패·선택 인쇄·UI 회귀 검사.

## 실제 정밀 엔진 확인 — 기존 0.3.1 기록

본 앱의 정밀 편집에서 Word 한국어 페이지를 렌더하고 IntegratedWriterProof를 입력·저장·닫았습니다. 앱의 새 DOCX 베이스에서 실제 word/document.xml 문자열과 revision 증가를 확인했습니다. 별도 앱이나 서버를 사용하지 않았습니다.

Calc에서 A1=42, B1=SUM(A1,8)를 입력해 화면의 50과 XLSX의 실제 수식/결과를 확인했습니다. 문서 연속 열기 중 종료 지연을 발견해 엔진 프로세스를 문서 종료 후 정리하도록 수정했습니다. 종료 지연 시에도 마지막 저장 XLSX의 값·수식·새 revision이 복구됨을 확인했습니다. 최종 연속 종료/발표 화면 검증은 office-visual-smoke.json과 해당 PNG에 기록합니다.

Impress의 실제 슬라이드에 IntegratedSlideProof를 입력·저장하고 앱의 PPTX 베이스에서 같은 문자열을 확인했습니다. 최종 수정본에서 저장 후 닫기·수정 없이 재열기 후 닫기·Word 수정 직후 수동 저장 없이 닫기, 엔진 프로세스 종료를 확인했습니다. 휴대폰 보기와 태블릿 편집 화면도 확인했습니다. 검사한 엔진 프로세스의 문서 표식·프로토콜 로그 유출 일치 건수는 0입니다. 실제 편집 샘플 3개와 SHA-256은 native-edit-samples 및 office-visual-smoke.json에 제공합니다.

추가 복구 검사에서는 원본 URI가 저장된 깨끗한 문서의 정밀 편집 종료 후 같은 URI를 다시 열어도 원본 대신 작업 복구본을 읽고, 외부 제공자 원본은 그대로 유지하는 것을 확인했습니다. 동시에 바뀐 기본 화면의 본문은 가져오기로 덮어쓰지 않습니다.

엔진 안의 모든 메뉴·수식·차트·전환·변경 추적 기능을 각각 검증한 것은 아닙니다. 엔진의 기능 제공과 문서 코퍼스 호환성 인증을 구분합니다. 실제 한컴/Word/Excel/PowerPoint 상용 앱의 모든 조판과 같음을 인증하지 않습니다.

출시 서명 APK에서도 정밀 편집 후 앱으로 돌아와 실제 Downloads 제공자에 저장한 DOCX의 XML을 확인했습니다. 휴대폰의 편집 모드→보기 모드→닫기에서 엔진 프로세스 종료와 앱 복귀를 확인했습니다. 세부 검증 시점과 해시는 release-office-saf-smoke.json에 기록합니다.

출시 서명 0.3.1 APK에서 실제 획을 부분 지우기로 나누고 두 페이지 PDF를 Downloads에 저장했습니다. 저장 파일의 Ink 주석 2개와 조각 사이 좌표 간격, 연속 스크롤·다음 페이지에서 같은 페이지 필기로 돌아오는 흐름을 확인했습니다. v031-phone-partial-eraser.png, v031-phone-continuous-pdf.png, native-edit-samples/partial-eraser-proof.pdf 및 v031-release-smoke.json에 증거를 보존합니다. 전달 소스 ZIP을 별도 검증 폴더에 추출해 고정 엔진 캐시로 assembleDebug 재빌드도 성공했습니다(11s).

## 배포 검사 — 기존 0.3.1 기록

release-verification.json에 서명·매니페스트·APK/AAB 네이티브 일치·16 KB 정렬 결과를 기록합니다. 공식 엔진의 두 ABI 30개 라이브러리는 별도 ELF LOAD 16 KB 정렬 검사에서도 통과했습니다. 실제 16 KB 기기 실행은 미실시입니다. 공식 두 APK 인증서 SHA-256을 검사했고, 공통 자산이 두 ABI에서 동일함을 확인했습니다. 원래 APK의 ART 프로필은 포함하지 않습니다.

최종 APK/AAB는 프로젝트의 기존 출시 키를 재사용합니다. Play 계정 접근·업로드·스토어 심사·실제 다운로드 용량 정책 검증은 수행하지 않았습니다. 큰 엔진을 포함하므로 공개 제출용 크기/전달 구성이 추가로 필요할 수 있습니다. debug와 release 서명은 다릅니다.

## 검증하지 않은 범위

실제 Galaxy Tab/S펜·하드웨어 필압/기울기/호버/손바닥, Bluetooth 키보드·마우스, API 29/36/37, 실제 16 KB 기기, 실제 멀티윈도우·접근성 전문 감사·대용량 벤치마크·대표 외부 문서 코퍼스는 미실시입니다. 폰/태블릿 크기 에뮬레이터 화면은 하드웨어 검증을 대신하지 않습니다.

한글 정밀 조판·OCR·PDF/A 적합성 검증·예측 입력·정밀 조판/셀에 부착하는 공통 필기는 구현되지 않았습니다. 악성 문서 완전 격리·암호화 백업·완전 무손실 인증도 제공하지 않습니다. 상세 상태는 SUPPORT_MATRIX.md를 기준으로 합니다.
