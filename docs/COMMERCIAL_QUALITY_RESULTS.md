# 문서 스튜디오 0.8.11-preview — 전체 페이지·터치 뷰어 및 데이터 안정성

작성: 2026-11-04 UTC. **검증한 0.8.11-preview의 결과다. 상용 출시 완료 판정이 아니다.**

## 1. 전체 진단 결과

기준은 실제 원격0.8.10 소스 aa3414f1fae89f5f98d10dabb64df85bfd7fc1df다. 접근할 수 없던 미푸시0.8.0 aa3be049 원본을 복구한 것이 아니다. 작업 환경의 명령 실행은 시작 실패로 사용할 수 없어 GitHub의 소스와 Actions를 사용했다. 수정본은 작업 브랜치에 실제로 푸시했으며 main과 기존 Release를 보존한다.

| 확인한 문제 | 실제 원인·영향 |
|---|---|
| PDF에서 아래로 내려도 다음 쪽이 안 보임 | 기본값이 단일 페이지였고 연속 보기에는 실제 핀치·맞춤 컨트롤이 연결되지 않았다. |
| 페이지 번호가 마지막 쪽으로 바뀌지 않음 | PDF/Word에서 스크롤 상단 쪽만 사용해 화면 중심이나 짧은 마지막 쪽을 놓쳤다. |
| PowerPoint가 매우 작게 표시됨 | 네이티브 파일 기반 보기가 전체 슬라이드 스택 높이를 맞추고 있었다. |
| PPT 확대 후 다른 슬라이드가 보이는데 번호는 그대로임 | 엔진의 선택 번호가 실제 viewport와 달랐고, 확대·회전에 페이지 경계와 화면 스크롤 좌표를 혼용했다. 가시 면적 기반 번호, 문서 좌표의 확대 중심, 실제 스크롤·화면 크기 복원으로 수정했다. |
| Office 첫 열기에서 일찍 종료됨 | 브라우저 진단용 postMobileError를 네이티브 문서 로딩 실패로 오해해 Activity를 닫았다. 실제 번들의 logServer도 이 채널을 사용함을 확인했다. |
| 더블 탭 간헐적 무반응 | 두 번째 누름 시각 대신 뗀 시각을 비교했다. Android 입력 로그의 실제 탭 간격117ms를329ms로 오판했다. |
| HWP 읽기 터치·회전 | 읽기 정책의 pointerdown 차단과 제스처 경로가 충돌했다. 화면 회전은 Activity를 다시 만들었고 픽셀 위치만 복원하면 읽던 쪽이 달라졌다. |
| Markdown 보기 설정 무반응·빈 화면 | 보기 메뉴가 원문 입력기에만 연결됐다. 전역 자원403 차단이 본문을 대체할 수 있었고 선택 글꼴이 실제 CSS에 연결되지 않았다. |
| 숨긴 PDF 필기가 연속 보기에서 보일 수 있음 | 기존 InkLayers 표시 순서를 거치지 않고 모든 획을 그렸다. |
| 저장·복구 데이터 위험 | 저장 재시도가 이전 백업을 덮어쓸 수 있었고 여러 출력 경로의 복구 절차가 달랐다. |
| 서명 보관함 손상 | 손상 JSON을 빈 목록으로 취급하거나 저장 성공 전에 UI 목록을 바꿀 수 있었다. |
| 검사 서버 변경 | ubuntu-latest가26.04로 바뀌어 고정 Playwright가 시작되지 않았다. 앱 오류와 분리하고24.04로 고정했다. |

HWP 엔진은 실제로 모든 wasm.pageCount를 읽고 가시 영역을 렌더링한다. 첫 쪽만 파싱한다는 근거는 찾지 못했다. 사용자가 보고한 특정 문서 자체를 확보해 재현한 결과와 생성한20쪽 문서 검사는 구분한다.

## 2. 실제 수정 사항

### 보기·스크롤·확대

- PDF: 다중 페이지 기본값을 세로 연속 보기로 바꿨다. 전체 페이지의 실제 크기·회전을 유지하고 보이는 주변 페이지만 렌더링한다. 현재/전체 쪽, 직접·첫/마지막·이전/다음 이동, 핀치 중심 확대·가로 이동·더블 탭·너비/페이지 맞춤, 위치 복원을 연결했다. 명시적인 단일 보기와 기존 편집 캔버스도 유지한다.
- PDF 성능: 문서 버전마다 PdfRenderer 하나를 재사용한다. 미저장 본문 변경은 해당 버전에 한 번만 작업 사본으로 만든다. 페이지당2M픽셀 예산, 비트맵 소유권·종료 잠금·UI 밖 정리, 재시도를 적용했다. 필기는 기존 InkLayers.ordered로 표시해 숨김과 순서를 지킨다.
- HWP/HWPX: 실제 페이지 가상 렌더링을 유지하며 모바일은 세로 단일 열로 연다. 쪽 번호·직접 이동·맞춤·다시 표시·읽기 팬/핀치/더블 탭을 연결했다. 회전 때 같은 WebView를 유지하고 페이지 및 문서 내부 좌표로 보던 위치를 복원한다. 눈금자는 기본 숨김이다.
- DOC/DOCX: Collabora의 실제 페이지 사각형을 이용해 현재 쪽을 계산하고 연속 탐색·페이지 이동·읽기 확대를 연결했다.
- PPT/PPTX: 실제 슬라이드 크기로 개별 슬라이드 맞춤을 계산한다. 읽기 맞춤 상태의 수평 드래그로 이동하고 확대 상태에서는 팬을 우선한다. 전체 슬라이드의 네이티브 세로 배치도 유지한다. 현재 슬라이드는 실제 화면과 페이지 사각형의 교차 면적으로 계산하며, 핀치·더블 탭은 손가락 중심의 문서 좌표를 보존한다. 회전 복원에는 실제 스크롤과 화면 크기를 사용한다.
- XLS/XLSX: 시트·셀 탐색을 유지하고 시트 목록·전환을 제공한다. 본문 페이지 UI를 강제하지 않는다. 실제 찾기로CV5000 같은 원거리 셀에 접근한다.
- Markdown: 동일 WebView에서 실제 앱 글꼴·보기 크기·맨 위/아래·핀치·위치 기억을 연결했다. 표시 변경으로 원문을 수정하지 않는다. 텍스트/테마 변경 없이 글꼴만 바꿀 때 Markdown을 재파싱하지 않는다. 스크립트·원격 이미지·임의 파일 접근은 계속 차단한다.
- TXT: 기존 전체 텍스트·줄바꿈·검색/바꾸기·인코딩·보기 설정·위치 복원을 유지한다. 글꼴/크기는 파일 서식으로 저장하지 않는다.

보기/편집은 기존 형식별 엔진을 유지한다. 보기 상태에서는 실제 변경 명령을 막고 명시적인 편집 버튼으로 전환한다. 문서 확대는 글꼴 크기 변경 명령과 다르다. Office는 브라우저 진단과 실제 네이티브 로드 오류를 구분하고, 실제 로딩이 끝나지 않으면90초 후 재시도 안내를 준다. 실제 로드 완료 전 PDF JNI 변환을 실행하지 않는다.

### 안전한 저장·종료·복구

ProviderWrites의 공통 저장 경로에 변경별 원본/최신 결과/해시/단계를 영속 보존한다. 프로세스 내 동기화·프로세스 간 잠금, AtomicFile·fsync, 대상 URI 별칭·원본 변경 검사, 기록 후 readback을 적용한다. 불확실한 재시도는 이전 백업을 덮어쓰지 않는다. 문서·Office 내보내기·PDF·변환·ZIP·암호화 백업의 기존 출력 경로를 연결했다.

미수정 문서는 불필요하게 직렬화·원본 저장하지 않는다. 자동 복구본과 원본 저장 상태를 구분하고, 최신 체크포인트/저장 결과를 확인한 뒤 종료한다. 저장 중 닫기 요청을 결합하며 오류 시 재시도·별도 사본 보관·취소를 제공한다. 중단 저장의 이전 원본과 최신 편집본을 각각 새 파일로 내보낼 수 있다.

SAF 제공자 전체에서 원자적 교체·외부 앱과의 compare-and-swap·클라우드 영구 반영을 보장하지 않는다. readback은 현재 읽히는 바이트 검증이다. 전원 차단·모든 제공자·모든 저장 공간 부족 상황을 검증한 결과도 아니다.

### 서명·홈·오류·공통 UI

서명 PNG의 헤더/끝/디코딩/크기와 목록·획·좌표를 검증한다. 손상 보관함을 빈 목록으로 덮어쓰지 않고 이전 정상본·원시 자료를 보존한다. 내 서명 목록은 native 저장 성공 뒤에 바뀐다. 기존 PNG 자필서명·표 셀 삽입·이동/크기·HWPX 저장/재열기·PDF 연동을 유지한다. 인증서 전자서명이나 완전한 벡터 직접 필기로 표시하지 않는다.

최근 원본 파일을 다시 확인하고 미저장 작업 사본을 우선한다. 홈의 검색/정렬/숨기기·복원, 작은 화면/큰 글자/시스템 테마, 독립된 문서·서명 복구 영역을 보완했다. 선택한 변환 결과의 실제 형식을 다음 변환 옵션과 분리하고 저장 중 중복·취소 충돌을 막는다.

쉬운 한국어 오류와 조치를 제공한다. 새 로컬 진단은 분류·시각·앱/API·엔진 정보만 기록하며 문서명/경로/예외 원문을 남기지 않는다. Office 초기 진단도 고정된 단계만 보관한다. 외부 오류 수집 서버를 추가하지 않았다.

## 3. 변경 코드와 호환성

| 영역 | 주요 파일 |
|---|---|
| 저장/복구 | data/ProviderWrites.kt, DocumentUris.kt, DocumentRepository.kt, ConversionFiles.kt; EditorViewModel.kt; SaveRecoveryDialog.kt |
| PDF 보기 | data/PdfEngine.kt, PdfReadRenderer.kt; ui/PdfContinuousReader.kt, PdfCanvasEditor.kt, FormatWorkspace.kt |
| 한글 보기 | HangulEditorActivity.kt; web/hangul/engine-adapter.ts, studio-touch.ts, host.js, host.css, index.html |
| Office | officeruntime/.../studio-policy.js, LOActivity.java, COWebView.java, DocumentViewState.java, StudioDocumentHost.java |
| Markdown/공통 화면 | MarkdownPreview.kt, WorkspaceHome.kt, StudioTheme.kt, StudioApp.kt, FolderBrowser.kt, WorkspaceComponents.kt; shared/editor-ui/dist |
| 서명/안내 | SignatureStore.kt, UserProblems.kt, SafeDiagnostics.kt, StudioApplication.kt, web/hangul/signature.js |
| 실제 검증 | ReadingUxTest, ViewerNavigationTest, ConversionIntegrationTest, SaveProcessRecoveryTest, ProviderWriteRecoveryTest, SignatureStoreTest, QualityUiTest; prepare-viewer-fixtures.py, viewer-pages.cjs |
| 배포 | mobile-release.yml, publish-quality-preview.yml, reading-performance.yml, quality-security.yml, package-mobile-release.py |

[전체 변경 파일 목록](releases/0.8.11-preview-changed-files.json). 기존 Kotlin/Compose, Rhwp0.8.7, Collabora/LibreOffice26.04.6, PDFBox Android, CommonMark, hwplib/hwpxlib 모델과 저장 경로를 유지한다. 구조 있는 문서를 추출 텍스트 입력기로 대체하지 않았다. 인쇄 기능을 추가하지 않았다.

## 4. 실제 검증 결과

| 검사 | 현재 결과 |
|---|---|
| 동일 앱 Android 집중10건 | [41819668985](https://github.com/Ch-wook/File_Translator/actions/runs/41819668985) 통과, 실패/오류/건너뜀0. 네 형식의 실제 핀치·읽기 더블 탭·회전·원본 보존, PDF100/숨김 필기, Markdown 실제 글꼴 픽셀, PPT 실제 가시 영역·확대 중심 포함. [원자료](releases/0.8.11-preview-viewer-focused-android.json). |
| 최종 HWPX20 브라우저 | [41820125408](https://github.com/Ch-wook/File_Translator/actions/runs/41820125408) 통과. 실제20개 화면 Canvas 픽셀 해시가 다르고 한 손가락·핀치·더블 탭·마지막 위치 재열기·문서 쓰기0·원본 해시 유지. 첫 준비1198ms는 Chromium1회 값이다. |
| PPT 실제 가시 영역·문서 초점 | [41818169879](https://github.com/Ch-wook/File_Translator/actions/runs/41818169879) 통과. 핀치·더블 탭·가로/세로 회전 후 실제 30번째 슬라이드 유지, 확대 중심 오차40twip 이내. [좌표 원자료](releases/0.8.11-preview-ppt-viewer.json). |
| PPT 확대 후 화면 맞춤 | [41821406403](https://github.com/Ch-wook/File_Translator/actions/runs/41821406403) 추가1건 통과. 실제 마지막 슬라이드·39% 맞춤 복귀, 본문 영역의 어두운 글자 픽셀431개(2px 간격 표본). 실제 캡처에서도 마지막 두 슬라이드의 VIEWER_SLIDE_29/30을 확인했다. 동일 앱 코드에 강화한 테스트만 적용했다. 70건과 중복 합산하지 않는다. [원자료](releases/0.8.11-preview-slide-fit-android.json). |
| 최종 전체70건·단위·설치·재열기 | [41820125408](https://github.com/Ch-wook/File_Translator/actions/runs/41820125408) 통과. Android70, core128/Office2/app45(합계175), JavaScript28, 기존 실제 엔진 브라우저9개 시나리오와 별도 HWPX20쪽 보기, TypeScript·lint·독립 파서·두 ABI APK 서명/ELF 검사 통과. 실패/오류/건너뜀0. [최종 원자료](releases/0.8.11-preview-build-verification.json). |
| 동일 앱·동일 환경 성능 비교 | [41819668944](https://github.com/Ch-wook/File_Translator/actions/runs/41819668944) 통과. 앱 코드는 최종 빌드와 같다. 메뉴·큰 TXT·PDF 재사용은 해당 측정에서 개선됐고, 일반 모드·수정 저장·일부 종료는 느려졌다. [전체 원시 값](releases/0.8.11-preview-performance.json). |
| 동일 앱 Release 구조·공개 Maven 권고 | [41819669179](https://github.com/Ch-wook/File_Translator/actions/runs/41819669179) 통과. unsigned Release 실제 빌드, 검사 구성요소/INTERNET 제외, 제한된 자격정보 패턴0, 공개 Maven157개 조회·권고 일치0. 전체 보안 인증이 아니다. |
| 2026-11-04 npm 검사 | [41813288172](https://github.com/Ch-wook/File_Translator/actions/runs/41813288172): production 분류0, 전체22패키지(고8/중9/저5) 미해결. [범위·검토·남은 조치](NODE_DEPENDENCY_AUDIT_REVIEW.md). |
| 실제 ARM64 휴대폰/Gboard/S펜/TalkBack | 미실행. |

Android 조건은 API35 x86_64 Pixel7, SwiftShader, RAM3072MiB다. 브라우저 휴대폰 화면은412×915이며 실제 휴대폰 검증과 다르다. 최신 전체 실행은 Ubuntu24.04로 고정한다. b6419f88/b68d3195의 기본 검사 뒤에 실제 PPT 좌표 이동을 추가로 발견했고 e23a7a26에서 문서 초점·실제 가시 슬라이드 검사까지 통과했다. 오래된 네이티브 선택 번호만으로 위치 유지까지 통과로 처리하지 않는다. 전체 CI를 Ubuntu24.04로 고정했다. 최종 빌드는 `f72c0aaa4f1c7fc15561eea9404ee372cd3488fa`다. 검사한 APK 바이트를 재빌드·재서명하지 않고 배포한다. 이후의 차이는 강화한 PPT 테스트·문서·CI이며 앱 코드·엔진은 같다.

PPTX는 표/이미지가 포함된10개 슬라이드를 변환하고 독립 PDF 파서로 쪽 수·16:9 비율·문자 순서와 편집 후11쪽을 확인한다. DOCX는2쪽·머리말/꼬리말·표 문자를, XLSX는2시트·표시/수식 결과를 확인한다. 모든 표/그림의 시각적 배치가 완전히 동일한지 검증한 결과는 아니다. 한글은 실제 Android 자필 획→PNG 삽입→HWPX 저장→엔진/독립 파서 재열기→서명 포함PDF를 검사한다. 저장 실패 주입·중간 프로세스 종료·부분 쓰기/롤백 실패 시 이전/최신 사본 보존도 포함한다. 위 시나리오는 최종70건 실행과 독립 파서에서 통과했다. 설치는 API35 x86_64에서 이전0.8.10 앱과 함께 설치하고 검사용 비공개 파일 보존·동일 서명 재설치를 확인했다. ARM64 실기기 설치 검사가 아니다.

성능 측정은 같은 에뮬레이터에서0.8.10과 현재 후보를 순차 실행한다. 전체 앱 peak RAM·60fps·배터리·모든 버튼100ms·모든 화면300ms를 측정/충족했다고 주장하지 않는다.

### 수정 전후 성능 — 실제 값과 범위

같은 API35 x86_64 Pixel7 에뮬레이터, SwiftShader, RAM3072MiB에서 이전0.8.10을 먼저, 후보를 다음에 실행했다. 측정 소스는 `8fae41c228a6cf86178f5da33669ee3d6cc4a08c`, 최종 APK 빌드와 앱 코드·엔진·벤치마크 소스가 같다. 이후 차이는 Office viewport 테스트의 기대값·추가 PPT 화면 맞춤 검사·문서·CI다. 다음 값은 ms이며 5회 중앙값, 시작은3회, 큰 TXT는7회다. 버튼 수치는 Compose 대기까지 포함하며 입력→첫 프레임 지연이 아니다.

| 항목 | 0.8.10 | 후보 | 판단 |
|---|---:|---:|---|
| Activity 시작 TotalTime | 2475 | 2848 | 증가; 첫 페이지 표시 시간과 다름 |
| 180KB TXT 저장소 열기 | 20.951 | 19.635 | 이 샘플에서6.3% 감소 |
| 미변경 체크포인트 | 0.511 | 0.336 | 양쪽 모두 실제 state 쓰기0회 |
| 미변경 원본 저장 요청 | 2.253 | 1.878 | 원본 해시 유지 |
| 수정한 TXT 원본 저장 | 110.229 | 127.680 | 15.8% 증가; 추가 개선 필요 |
| 일반 TXT 편집 전환 | 200.830 | 297.327 | 48.0% 증가; 추가 개선 필요 |
| TXT 메뉴 열기 | 371.444 | 250.947 | 이 샘플에서32.4% 감소 |
| 516KB TXT 편집 전환 | 150.517 | 130.719 | 이 샘플에서13.2% 감소 |
| PDF 크기 계산 | 0.647 | 0.750 | 증가 |
| PDF 기존 단일 쪽 렌더링 | 3.696 | 4.068 | 증가 |

후보 안에서 80개 빈 PDF 쪽 중10개를800px·2M픽셀 한도로 렌더링할 때 매번 여는 경로13.083ms→공유 렌더러9.279ms(중앙값,29.1% 감소)였다. 복잡한 실제 PDF 전체의 속도나100쪽 전체 첫 표시 시간이 아니다.

| 네이티브 형식, 각1회 | 엔진 준비 이전→후보 | 미변경 종료 이전→후보 |
|---|---:|---:|
| DOCX | 7956→7532 | 406→299 |
| XLSX | 7287→7132 | 313→409 |
| PPTX | 5548→5847 | 361→574 |
| HWPX | 6164→6205 | 324→615 |

위 엔진 준비는 Activity 실행부터 실제 읽기 도구 활성화까지이며 첫 픽셀 지연과 다르다. 종료는 프로세스 종료 및 내구성 있는 종료 표시 확인까지 포함한다. 1회 값은 일반 성능 향상 근거가 아니며 특히 XLSX/PPTX/HWPX 종료는 추가 프로파일링 대상이다. 네 형식 모두 원본·작업 문서 해시 유지, 수정 표시 없음, 헤더48CSSpx, 2초 idle 헤더 변경0회였다.

메모리는 peak가 아닌 특정 시점의 PSS 표본이다. 저장소 검사 후147914→144084KiB였고, 단일 페이지 모드로 PDF를 세 번 열고 닫은 뒤 후보 PSS는145981/146305/146588KiB였다. 표시 비트맵은 양쪽 모두1024×1325·5427200bytes이며 닫힌 비트맵은 전부 recycle됐다. 연속 보기 전체의 최대 메모리, 장시간 누수 부재, 전체 앱 메모리 감소를 증명한 결과는 아니다. 배터리·60fps·실기기·모든 버튼100ms/화면300ms 목표는 미측정이다.

## 5. 현재 완성도와 지원 제한

| 형식/기능 | 실제 제공 경로 | 부분 지원·미지원 |
|---|---|---|
| DOCX/DOC | 실제 Collabora 문서·선택 서식·표/그림·저장·PDF | 모든 고급 개체/서식 무손실 보장 아님 |
| XLSX/XLS | 실제 셀/시트·서식·수식 계산·저장·PDF | 전체 함수·차트·조건부 서식 검증 아님 |
| PPTX/PPT | 실제 슬라이드·텍스트/개체·저장·PDF | 고급 효과·애니메이션 편집 전수 검증 아님 |
| HWPX/HWP | Rhwp 원본·선택 서식·자간/장평·표/그림·PNG 서명·저장 | 한컴 호환성 인증 아님; 복잡한 미지원 요소 제한 |
| PDF | 모든 페이지·검색·주석/필기·구조·지원 범위 본문 수정 | Word처럼 임의 본문 재배치 아님; 일부 폰트/자간/장평 제한; 연속 보기의 페이지당2M픽셀 예산으로 높은 배율의 선명도 제한 |
| Markdown | 전체 원문/서식 보기·문법 도구·검색/저장 | 임의 글꼴/색상을 원문 서식으로 저장하지 않음; 원격 이미지 미다운로드 |
| TXT | 전체 텍스트·한글·선택·검색/바꾸기·인코딩/저장 | 보기 글꼴·크기는 파일 서식 아님; 기존2MiB 제한 |
| 변환 | DOCX/XLSX/PPTX/HWPX/HWP→PDF, 기존 MD/TXT 변환, PDF 이미지/TXT | 한글 PDF는 페이지 이미지. 편집 가능한 PDF→Office, DOCX↔HWPX, 전체 OCR/일괄 변환은 미구현/추가 검증 |
| 서명/필기 | 기존 PNG 자필 서명과 별도 필기 | 인증서 전자서명·완전한 직접 벡터 필기 개체 전체는 미구현 |

기존 실제 번들 글꼴과 한국어 추가6패밀리를 유지한다. 이번에는 Markdown 보기에서 실제 공급·적용을 연결했다. 라이선스 없는 맑은 고딕/Arial 등의 이름만 추가해 같은 모양을 보여주지 않는다. 형식과 엔진의 실제 저장 능력에 맞는 도구만 제공한다.

## 6. 남은 위험과 출시 전 작업

1. 실제 ARM64 휴대폰의 설치, Samsung/Gboard 조합·S펜·TalkBack·회전/백그라운드·장시간·메모리/저장 공간 부족 검사.
2. 외부 저장 제공자별 충돌·권한 회수·오프라인·전원 차단·복구 검증. SAF의 보편적 원자 교체/원격 영구 반영 보장은 없음.
3. 전체 native/Wasm/최종 번들 의존성·취약 문서 퍼징·운영 개인정보/ML Kit IPC·라이선스 법적 검토. npm22건 개발/빌드 권고는 미해결.
4. 운영 서명키·안전한 CI 비밀·스토어/AAB·안정된 업데이트 경로. 이번은 기존 앱을 보존하는 별도 개발 서명 프리뷰다. 앱 내부 자료 전체를 별도 암호화하지 않으며 기존 .dsbackup 내보내기만 AES-256-GCM을 사용한다.
5. 읽기만 한 특정 사용자 파일의 첫 쪽 현상, 고급 문서 요소·변환 품질·메뉴 지연/프레임/peak RAM의 추가 측정. 생성한 샘플 통과를 모든 파일 무손실 보장으로 확대하지 않는다.

앱은 INTERNET 권한이나 새 문서 업로드 서버를 사용하지 않는다. 이것만으로 ML Kit·Google Play 서비스·OS 전체에서 통계/IPC 처리가 전혀 없다고 단정하지 않는다. 앱 삭제/데이터 삭제는 앱 내부 복구본·서명을 지우므로 기존 앱을 삭제하지 말고 외부 저장/백업한 파일로 옮겨야 한다.

Maven에서 확인했던 Log4j API2.24.3의 CVE-2026-49844는2.25.5로 갱신하고 실제 JSON 숫자 회귀와 원본 NOTICE를 보존했다. Rhwp MIT, Collabora/MPL 및 포함 라이브러리, PDFBox Apache, OFL 폰트의 기존 고지를 유지한다. 전체 보안·라이선스 인증을 마쳤다는 뜻은 아니다.

## 배포 파일과 추적 정보

[휴대폰 ARM64 APK](https://github.com/Ch-wook/File_Translator/releases/download/v0.8.11-preview/document-studio-0.8.11-preview-arm64-v8a.apk) · [전체 소스 ZIP](https://github.com/Ch-wook/File_Translator/releases/download/v0.8.11-preview/document-studio-0.8.11-preview-source.zip) · [Release·검증 ZIP·해시·패치·Git 이력](https://github.com/Ch-wook/File_Translator/releases/tag/v0.8.11-preview) · [PR #1](https://github.com/Ch-wook/File_Translator/pull/1)

앱은 **문서 스튜디오 품질 개선 / kr.documentstudio.qualitypreview**, versionCode21다. Android10+ 64비트, Office는Android11+다. ARM64 APK430294148bytes(약430.3MB), x86_64 APK442180638bytes다. 두 APK는 동일한 개발 인증서이며 testOnly가 아니다. 16KiB ELF 정렬은 정적 검사 결과이며 실제16KiB ARM64 기기 검증과 다르다. 기존 앱을 삭제하지 말고 외부 저장·백업한 파일로 옮긴다. 앱 내부 복구본과 내 서명은 자동 이관하지 않는다.

원본0.8.10 Release와 main은 보존한다. 최종 소스 ZIP은 태그의 모든 추적 바이트와 대조하며 APK 빌드 뒤 앱 코드 변경이 없는지 별도 검사한다. 공개 전10개 자산 업로드 크기·해시, 공개 뒤 실제 익명 다운로드의 크기·SHA-256·태그를 검증하는 절차를 실행한다. 공개 다운로드의 최종 결과는 별도 확인서와 Actions에 기록한다.
