# 기술 설계와 개발 범위 — 0.5.0

검토일: 2026-10-18. 구현된 기능은 오프라인 우선이며, 전문 오피스 전체 기능과 조판 품질은 아직 달성하지 않았습니다.

## 기술 스택

| 영역 | 선택 |
|---|---|
| 플랫폼 | Android 전용, minSdk 29 / compileSdk·targetSdk 36 |
| 언어·빌드 | Kotlin 2.2.20 / JDK 17 / AGP 8.13.0 / Gradle 9.1.0 |
| UI | Compose BOM 2025.09.01 / Material 3 / Activity 1.11.0 |
| 상태·비동기 | MVVM, StateFlow, Lifecycle 해결 버전 2.9.4, Coroutines 1.10.2 |
| 의존성 주입 | 생성자 주입, AndroidViewModel |
| 복구·설정 | 버전 1 JSON + AtomicFile, SharedPreferences, IO 디스패처 |
| 파일 | SAF, ContentResolver, 지속 URI 권한 |
| PDF | OS PdfRenderer + PDFBox Android 2.0.27.0 |
| 바이너리 | POI 5.4.1의 HWPF/HSSF/HSLF + hwplib 1.1.10 |
| XML 문서 | ZIP + namespace-aware DOM, PackageDocument/PackageFeatures |
| Markdown | CommonMark 0.25.0 + GFM Tables, 제한 WebView/서식 텍스트 |
| 필기 | Android MotionEvent + Canvas |

Hilt/Room/DataStore 대신 현재 규모에 맞는 생성자 주입·AtomicFile·SharedPreferences를 사용했습니다. app/core/officeruntime 세 Gradle 모듈과 기능 패키지로 분리했습니다.

## 형식별 엔진과 실제 편집 단위

| 형식 | 실제 엔진 | 편집 단위 |
|---|---|---|
| TXT/MD | TextContent, LineNumberEditText, CommonMark | 문자열·인코딩·서식 미리보기 |
| PDF | PdfRenderer, PDFBox, Android PdfDocument | 표준 Ink·페이지·양식·본문 추가 |
| DOCX | PackageDocument/PackageFeatures | 기존 w:t, run/paragraph 속성, 문단/표/용지 |
| XLSX | PackageDocument/PackageFeatures/Formula | 셀·서식·시트·병합·열/행·수식 |
| PPTX | PackageDocument/PackageFeatures/SlideEngine | 슬라이드·런·직접 도형·좌표 |
| HWPX | PackageDocument/PackageFeatures/HangulTables | hp:t, header 글자/문단 속성, 용지, 직사각형 표 구조 |
| DOC/XLS/PPT | Apache POI binary modules | 기존 런·셀, 원본 OLE 저장 |
| HWP | hwplib | 일반 문자 구간·원본 바이너리 저장 |

문서 파서는 전문 조판 엔진과 다릅니다. 기본 Word/HWP 화면은 런 편집이고 기본 PPTX 렌더러는 직접 개체 일부만 그립니다. Word/Excel/PowerPoint 정밀 편집은 별도의 실제 엔진을 사용합니다. 미수정 ZIP 파트/OLE 스트림 보존은 복잡한 원본을 완전히 재현한다는 의미가 아닙니다.

## 조사·비교

| 후보 | 검토 결과 |
|---|---|
| AndroidX PDF | 공식 PDF 보기/상호작용 구성요소. 광범위한 본문 편집 엔진과 같지 않으며 이번 검증 조합은 OS PdfRenderer. [공식 릴리스](https://developer.android.com/jetpack/androidx/releases/pdf) |
| AndroidX Ink | 저지연 입력·획 데이터 후보. 이번 엔진은 MotionEvent/Canvas이며 예측 입력은 미구현. [공식 릴리스](https://developer.android.com/jetpack/androidx/releases/ink) |
| PDFium 계열 | 렌더링 후보이나 별도 네이티브 ABI/페이지 크기 검증 필요. OS PdfRenderer로 별도 PDFium 바이너리 번들을 피함. [원본](https://github.com/barteksc/PdfiumAndroid) |
| PDFBox Android | Apache 2.0. PDF 구조·주석·페이지·양식 쓰기에 채택. 오래된 포트의 보안/업스트림 차이는 감사 과제. [원본](https://github.com/TomRoush/PdfBox-Android) |
| Apache POI | Apache 2.0. OLE 처리 경로를 Android에서 실제 검사해 채택. OOXML 모듈은 JVM 독립 검증용이며 앱에 포함하지 않음. 전체 AWT 렌더링을 Android에서 제공하지 않음. [공식 구성](https://poi.apache.org/components/index.html), [Android 호환성 참고](https://github.com/centic9/poi-on-android) |
| OOXML 직접 처리 | 제한된 수정·미수정 파트 보존에 적합하나 조판·차트·테마 전체 엔진은 별개 |
| hwplib/hwpxlib | Apache 2.0. HWP 런타임·HWPX 변환/쓰기 런타임·빈 템플릿·별도 HWPX 읽기 검사에 사용. [hwplib](https://github.com/neolord0/hwplib), [hwpxlib](https://github.com/neolord0/hwpxlib) |
| 한컴 공개 명세 | HWP 바이너리/OWPML 구조 자료이며 전문 편집 SDK 자체는 아님. [공식 명세](https://www2.hancom.com/support/downloadCenter/hwpOwpml), [HWPX 구조](https://tech.hancom.com/hwpxformat/) |
| Collabora/LibreOffice | Android/iOS의 실제 로컬 오피스 엔진 후보. MPL 2.0 및 여러 구성요소 라이선스 검토·대형 네이티브 빌드/제품 통합 필요. 0.3.1에서 공식 Android 바이너리와 대응 라이브러리를 통합. [모바일](https://www.collaboraonline.com/collabora-office-android-ios/), [라이선스 고지](https://www.collaboraonline.com/collabora-office-for-android-ios-credits/) |
| ONLYOFFICE | 모바일 WebView 통합은 문서 서버/스토리지/콜백 및 상용 모바일 배포 조건 검토 필요. 현재 미통합. [모바일 통합](https://api.onlyoffice.com/docs/docs-api/get-started/how-it-works/mobile-integration) |
| Aspose/Nutrient/Apryse | 문서 처리·렌더·편집 SDK 후보. 모듈·재배포 범위에 따른 견적/라이선스 필요. 설치/구매하지 않음. [Aspose](https://docs.aspose.com/words/java/licensing-android/), [Nutrient](https://www.nutrient.io/guides/android/faq/licensing/), [Apryse](https://apryse.com/products/mobile-sdk) |

전문 기능에 상용 SDK만이 유일한 방법이라는 뜻은 아닙니다. LibreOffice 계열 통합이나 자체 개발도 가능하지만 이번 버전은 실제 Android 엔진을 별도 프로세스에 통합했습니다.

## 오프라인·비용

구현된 엔진은 기기 내부에서 실행하며 앱 INTERNET 권한·문서 서버·문서 업로드가 없습니다. OCR SDK는 통계 구성요소를 포함할 수 있으나 앱 INTERNET 권한 제거로 직접 전송을 차단합니다. Markdown WebView는 JavaScript/네트워크/파일/콘텐츠 접근과 링크 이동을 막습니다. Drive 등 SAF 제공자 자체의 네트워크 동작은 제공자에 따릅니다.

채택한 오픈소스의 구매 비용은 없습니다. 개발·감사·호환성 검증 비용은 별도입니다. 상용 SDK의 가격은 배포 범위별 견적 대상이며 금액을 임의로 제시하지 않습니다. ONLYOFFICE 방식은 서버 운영비도 필요합니다.

## 필기 설계

Stroke는 UUID·앵커·도구·색·정규화 폭·투명도·좌표/필압/기울기 점 목록입니다. MotionEvent 역사 샘플과 hover/버튼/지우개를 처리합니다. 펜 종류별 폭을 다르게 그리고 올가미 교차/포함 검사·정규화 이동/확대/회전으로 획을 편집합니다. 올가미 계산은 Default 디스패처에서 처리합니다. 범위를 벗어나는 변환은 거부합니다.

펜 전용·스타일러스 우선, 활성 스타일러스 중 손가락 무시, 손가락 스크롤, 두 손가락 확대를 제공합니다. 하드웨어 손바닥 분류나 예측 입력을 대신하는 것은 아닙니다.

PDF는 CropBox·회전을 적용해 표준 InkList/appearance로 저장합니다. 앱 고유 주석만 분리·재구성해 중복을 방지하고 다른 앱의 주석을 남깁니다. 원래 필압·도구·투명도는 추가 메타데이터로 복원합니다. 표준 appearance는 일정 폭입니다. 페이지 순서·삭제·추출·회전에 맞춰 앵커/점/폭도 변경합니다.

PPTX 필기는 슬라이드 좌표에 연결됩니다. 다른 문서는 문서/시트/구역별 고정 페이지이며 DOCX 조판 페이지나 XLSX 셀 부착이 아닙니다. 원본 외 별도 데이터임을 UI에 표시하고 ZIP과 PDF 내보내기를 제공합니다.

## 저장·복구·호환성

1. 편집 후 약 700 ms 입력 휴지와 background 전환에 AtomicFile 작업 JSON을 기록합니다. 원본은 자동 덮어쓰지 않습니다.
2. Undo/Redo는 탭당 Session 스냅샷 최대 30개, 같은 문서의 같은 입력 대상에 대한 타이핑만 단조 시계 기준 750 ms로 묶습니다. 구조 편집은 새 불변 베이스 파일과 revision을 만들어 이전 구조도 되돌립니다. 열린 문서를 최근 목록/같은 URI로 다시 선택할 때 기존 탭과 이력을 유지합니다.
3. 저장은 임시 파일 생성 → 엔진 재열기 → 제공자 원본 백업/SHA-256 충돌 검사 → rwt 쓰기 → 읽기 SHA-256 확인 순서입니다.
4. 실패 시 원본 복원을 시도합니다. 복원까지 실패해도 독립 provider-backup.bin과 복구 작업을 남깁니다. SAF 제공자의 원자적 교체를 보장하지 않습니다.
5. 제공자 기록과 앱 상태 기록은 한 트랜잭션이 아닙니다. 중간 종료 시 충돌 경고 후 다른 이름으로 복구할 수 있습니다.
6. XML의 미수정 파트는 내용 바이트를 복사합니다. 변경 XML은 DOM 직렬화됩니다. 수식 캐시/calcChain을 지워 외부 재계산을 요청합니다.
7. OLE는 라이브러리가 쓴 스트림을 원본과 합쳐 미해석 스트림을 보존합니다. 라이브러리가 해석한 레코드 내부의 완전 무손실은 검증하지 않았습니다.
8. ZIP 백업은 베이스·편집·필기·정밀 편집 실패 자료·제공자 원본 백업을 포함하며 가져오기는 새 ID/원본 URI 없음 상태로 복원합니다. 실패 자료는 `preserved-backup/`에 격리해 외부 pending을 실행하지 않고 다음 백업에서 원래 ZIP 경로로 다시 내보냅니다. 새 자료와 충돌하면 SHA-256을 포함한 별도 이름으로 이전 자료도 보존합니다. ZIP 파일 128 MiB/해제 합계 256 MiB/256파트 한도와 경로 화이트리스트를 검사합니다. ZIP 자체는 암호화되지 않습니다.
9. 외부 원본 삭제를 추적하지 않고 복구본을 유지합니다. 앱 데이터 삭제 시 로컬 작업은 사라집니다. 불변 구조 베이스는 보수적으로 보존해 문서 편집 횟수에 따라 내부 저장 공간이 증가할 수 있습니다.

## 제한·보안

입력 최대 128 MiB, 텍스트 2 MiB, ZIP 해제 총 48 MiB/5,000 파트/XML 8 MiB, 바이너리 입력 16 MiB, 텍스트 런 10,000개, PDF 2,000페이지, 필기 총 200,000점 제한입니다. PDF 페이지는 각각 1,600×2,200 이하로 렌더링합니다. 단일 필기 보기는 한 장을 유지하고 연속 보기는 필요한 목록 항목의 비트맵을 유지하며 두 개의 렌더 슬롯을 사용합니다. XLSX는 표시 창만 가상화하고 파싱은 시트 전체입니다. PDF 이미지 추가는 800만 화소까지입니다. 초대용량 스트리밍·전체 성능 인증은 미구현입니다.

DTD/엔티티, ZIP 경로 탈출/중복/과대 압축, 암호화·전자서명·매크로를 차단합니다. 악성 문서 완전 격리나 PDF/A 적합성 검사기를 포함하지 않습니다. PDFBox 포트 감사와 실기기 검증이 남습니다.

## 소스 구조

```text
core/src/main/kotlin/kr/documentstudio/core/
  Document.kt                 형식·텍스트·필기·History
  PackageDocument.kt          ZIP 읽기·런/셀 쓰기
  PackageFeatures.kt          원본 서식·구조 변경
  LegacyDocument.kt           DOC/XLS/PPT/HWP 바이너리
  Formula.kt                  제한 로컬 수식
  InkGeometry.kt              도형·올가미·변환
  Templates.kt                새 문서
core/src/main/resources/templates/blank.hwpx
app/src/main/java/kr/documentstudio/
  EditorViewModel.kt          탭·명령·Undo/Redo·복구
  data/DocumentRepository.kt  SAF·저장·백업·불변 베이스
  data/PdfEngine.kt           PDF 구조·표준 Ink·추가 본문
  data/ExportEngine.kt        PDF 출력·잉크 그리기
  data/SlideEngine.kt         기본 DrawingML 표시
  data/MarkdownRender.kt      Markdown AST 서식
  ui/StudioApp.kt             홈·형식 편집·설정
  ui/DocumentTools.kt         실제 서식/구조 명령
  ui/DrawingPage.kt           필기 도구·선택·확대
  ui/InkView.kt               MotionEvent·Canvas
  ui/SlidePreview.kt          기본 개체 이동·슬라이드 쇼
  ui/DocumentPrintAdapter.kt  시스템 인쇄·선택 페이지
app/src/debug/                시험 제공자 — release 미포함
core/src/test/                독립 POI/hwpxlib 검증
app/src/androidTest/          ContentResolver·장애·UI 검사
scripts/ docs/                빌드·패키징·설계·사용 안내
```

## 단계별 결과와 후속 우선순위

| 단계 | 이번 결과 | 남은 주요 작업 |
|---|---|---|
| 1 앱·텍스트 | 열기/편집/탭/복구/인코딩/미리보기 | 큰 문서 증분 편집·접근성 |
| 2 PDF·필기 | 페이지/양식/본문 추가/Ink/올가미 | OCR·PDF/A 검증·저지연 실측 |
| 3 Word·Excel | 원본 런/서식/표/셀/수식/시트 | 정밀 엔진 호환성 코퍼스·셀 부착 필기 |
| 4 PPT | 개체/슬라이드·기본 발표/PDF | 정밀 렌더 코퍼스·노트/전환별 동작 검증 |
| 5 한글·구형 | HWPX/HWP/DOC/XLS/PPT 실제 편집 저장 | 복잡한 개체·한컴/Office 문서 코퍼스·변환 |
| 6 품질 | 복구 장애 주입·Undo 구조·태블릿 화면 | Galaxy/S펜·멀티윈도우·보안·대용량 |
| 7 배포 | debug·서명 release APK/AAB·소스 | 소유자 Play 제출·실기기/정책 검사 |

전체 전문 편집 완성 일정은 미확정입니다. 대표 파일 코퍼스와 실기기 검증을 확보하기 전 근거 없는 납기를 약속하지 않습니다.



## 0.3 정밀 엔진 통합과 추가 구현

officeruntime은 공식 Collabora Office 26.04.6.0 build 951, commit cf7b84af545e7112ee7ea69d7ae1ccbd2b38e4e9의 Java/res와 두 ABI의 공식 바이너리를 사용합니다. 출처·수정 내역·MPL 원문은 officeruntime/PROVENANCE.md에 있습니다. prepare-office-engine.ps1이 고정 SHA-256을 검사하고 자산을 준비하며 소스 ZIP에는 대형 바이너리 캐시를 넣지 않습니다. 최초 빌드는 다운로드가 필요합니다.

StudioOfficeActivity는 비공개 :office 프로세스에서 앱 전용 파일만 엽니다. file lock으로 동시 가져오기를 막고 저장 성공 응답을 최대 20초 기다린 뒤 BYE로 문서 쓰기를 종료합니다. .closed 표식 후 결과를 가져옵니다. 비정상 종료 시 파일 잠금 해제와 형식 유효성을 확인하고 이전 베이스·원본 URI/hash·필기를 보존합니다. 엔진 내부 세부 이력과 앱의 불변 베이스 이력은 별개입니다. 분리 프로세스는 같은 UID이며 악성 문서에 대한 별도 권한 격리는 아닙니다.

엔진의 Java/콘솔 로그를 버리고 Android 프로세스 로깅 콜백을 차단합니다. 이 API는 Android 11 이상에서 제공되어 API 29에서는 기본 도구만 사용합니다. [NDK 로깅 API](https://developer.android.com/ndk/reference/group/logging). JNI 브리지는 NDK 28의 정적 C++ 런타임으로 빌드해 공식 엔진의 libc++_shared.so를 교체하지 않습니다. AI 메뉴를 숨기고 네트워크 요청을 차단합니다.

추가 코어: SheetReferences/SheetOperations/TabularText가 참조 갱신·TSV·범위 작업을 처리하고 WordTables/OfficeObjects가 표·이미지·링크·머리글을 수정합니다. CellFormatting은 POI 숫자/날짜 서식을 사용합니다. Formula의 조건 와일드카드는 정규식 대신 제한된 비교 작업량으로 처리합니다. 표시 창 계산은 Default 디스패처에서 수행합니다.

PdfBodyText는 가로 Tj/TJ의 기존 폰트 인코딩을 이용해 실제 내용 스트림을 바꾸고 TJ 보정으로 후속 문자열 위치를 유지합니다. 복잡한/태그/중첩/세로 텍스트는 제외합니다. PdfBookmarks는 원래 COS 속성을 보존해 트리와 페이지 목적지를 수정하며 축소판은 두 개 동시 렌더와 작은 비트맵 제한을 사용합니다.

정밀 편집은 별도 고정 필기를 본문/셀 위치에 자동 부착하지 않습니다. 한글 정밀 조판·OCR·PDF/A 인증·예측 입력·실제 하드웨어/대용량 검증은 남아 있습니다. 최신 세부 상태는 SUPPORT_MATRIX.md를 기준으로 합니다.

엔진 WebView는 office 전용 데이터 폴더를 사용해 본 앱의 Markdown/고지 WebView와 프로필 잠금이 충돌하지 않게 합니다. 닫기 시 프런트 화면이 먼저 연결을 끊어도 네이티브 unocommandresult 저장 응답을 직접 받아 확인합니다. 초기 보기 모드에서 편집을 시작하지 않은 문서는 저장 없이 닫고 기존 파일을 유지합니다. 수정 후에는 저장 성공/명시적인 변경 없음 응답을 기다린 뒤 문서를 닫습니다. 완료된 엔진 프로세스는 종료해 다음 문서에 이전 전역 상태를 재사용하지 않습니다.

## 0.3.1 부분 획 지우기

InkErasure는 페이지 너비 단위로 지우개 경로를 연속 캡슐로 계산하고 획과 만나는 구간을 병합해 나머지 경로를 분할합니다. 세로 좌표에 페이지 비율을 적용해 지우개가 화면에서 원형으로 유지됩니다. 새 경계점은 필압·기울기를 보간하며 원래 도구/색/폭/투명도·앵커를 유지합니다. 도형은 일정 폭의 열린 펜 경로로 변환합니다. 800만 구간 비교와 결과 용량 제한이 있고 실패한 작업은 원자적으로 거부합니다. UI에서는 경로를 표시한 뒤 펜을 떼면 Default 디스패처에서 계산하며 한 제스처를 한 Undo 단계로 기록합니다. 설정은 다음 실행에도 유지합니다. PDF의 두 조각은 별개 표준 Ink 주석과 appearance로 저장합니다.

PdfContinuousReader는 LazyColumn으로 필요한 페이지만 표시하고 두 개의 렌더 슬롯으로 동시 작업을 제한합니다. 페이지 비트맵은 최대 1600×2200이며 항목이 제거되면 해제합니다. 불변 베이스와 페이지별 필기를 합성합니다. 이동 명령과 스크롤에 따른 현재 페이지 갱신을 분리해 피드백으로 스크롤이 튀지 않게 합니다. 확대 시 가로 탐색을 제공하고, 페이지별 버튼으로 같은 앵커의 단일 페이지 필기에 진입합니다. 태블릿의 PDF 탐색 목록도 LazyColumn으로 변경했습니다.

## 편집 흐름 추가 점검 — 2026-10-16

- SheetEditor의 별도 미적용 문자열을 없애고 Edits.values를 입력 상태로 사용합니다. 입력 직후 저장/셀 선택/시트 전환/필기 진입에도 값이 남고, 구조 편집과 Undo/Redo 결과가 입력창에 곧바로 표시됩니다. 원본 SAF 쓰기는 기존처럼 명시적인 저장으로만 수행합니다.
- 셀 계산의 의존성에 Edits.styles를 포함해 숫자·날짜·백분율 서식 변경을 바로 다시 표시합니다. 주소 이동은 표시 창과 스크롤을 함께 초기화하며 마지막 행·열에서는 창을 줄여 XLS/XLSX 경계를 넘지 않습니다.
- SheetEditor의 높이가 420 dp보다 작으면 수식 안내와 25행/열 이동 버튼을 접고 주소 이동을 사용합니다. 계산 결과와 셀 서식 버튼을 한 줄로 배치해 키보드가 열린 휴대폰에서도 셀 표의 표시 공간을 유지합니다. 셀 범위 입력과 복사/붙여넣기는 계속 사용할 수 있습니다.
- 비동기 명령 전에 열려 있는 작업 중 마지막 체크포인트 이후 변경된 탭을 기록합니다. 변경 없는 탭의 필기 JSON을 매 입력마다 다시 쓰거나 최근 목록의 시각을 갱신하지 않습니다. 자동 저장 예약을 취소한 뒤 명령이 실패하더라도 직전 입력이 복구본에 남습니다. ViewModel도 busy 상태에서 Undo/Redo·홈·탭 전환을 거부해 키보드 명령이 진행 중 작업의 스냅샷과 충돌하지 않게 합니다.
- 최근 목록을 IO에서 가져온 뒤 최신 UiState를 읽어 갱신합니다. `state.copy(recents = suspendRead())`처럼 중단 전에 잡힌 상태가 홈/탭 전환·새 입력을 덮어쓰지 않게 합니다. 초기 설정 읽기도 사용자가 이미 바꾼 설정을 덮어쓰지 않으며, 연속 설정 기록은 Mutex 안에서 최신 값을 읽어 순서 역전을 방지합니다.
- MainActivity가 공개 Window.Callback의 단축키 처리를 감싸 포커스된 EditText의 별도 실행 취소와 앱 이력이 갈라지지 않게 합니다. 처리하지 않는 이벤트는 현재 Activity로 위임해 AndroidX의 전달 경로를 유지합니다. 재생성 시 재사용된 Window의 이전 콜백을 붙잡아 터치가 이전 화면으로 전달되지 않게 합니다. PDF/A의 Ctrl+S도 별도 저장 안내를 사용합니다.
- 홈으로 돌아오면 입력 포커스와 소프트 키보드를 해제합니다. 텍스트 편집 후 남은 키보드가 최근 작업 목록의 표시 영역을 가리는 문제를 방지합니다.
- 정밀 엔진 시작/가져오기 실패를 현재 Session.recoveryWarning에 반영해 같은 화면에서 보관 후 재시작 메뉴를 사용할 수 있게 합니다.
- DrawingPage의 시트 계산/본문 조합은 Default 디스패처에서 수행하고 필기 목록 변경에는 다시 계산하지 않습니다. 단일 PDF 페이지와 축소판은 베이스/페이지별 produceState 수명에 비트맵 소유권을 연결합니다. IO 완료 직후 취소된 경우도 해제하고, 새 작업은 null 이미지로 시작해 이전의 해제된 비트맵을 그리지 않습니다. 단일 페이지 렌더 실패에는 재시도를 제공합니다.

회귀 검사는 EditingWorkflowTest에서 입력→탐색→저장/복구→실행 취소 흐름을 연결해 검사합니다. 측정하지 않은 하드웨어 지연이나 전체 문서 호환성을 통과로 간주하지 않습니다. 실행 결과와 환경은 TEST_RESULTS.md를 확인하세요.

## 요구사항별 보완 — 2026-10-16

[계획서](IMPLEMENTATION_PLAN.md)를 코드 변경 전에 작성하고 [인수 확인표](FEATURE_ACCEPTANCE.md)로 원문의 세부 기능을 추적합니다. 미지원·정밀 엔진의 미검증 기능·외부 인증을 완료 항목으로 합산하지 않습니다.

- `HangulTables`는 OWPML 표의 주소/스팬으로 격자를 검사한 뒤 수정합니다. 텍스트는 `hp:subList`의 문단으로 보존하고 새 셀은 기존 글자/문단 속성을 참조합니다. 배경/테두리는 header의 새 borderFill을 만들어 적용하므로 다른 셀의 공유 서식을 바꾸지 않습니다. 중첩/보호/불규칙 격자, 기존 병합과 겹치는 병합은 거부하며 행열 변경 전에 병합 해제를 요구합니다. 새 표는 실제 OWPML이며 기본 화면은 조판 대신 위치를 표시한 런 편집입니다. 구조 참고: [hwpxlib TableWriter](https://github.com/neolord0/hwpxlib/blob/main/src/main/java/kr/dogfoot/hwpxlib/writer/section_xml/object/TableWriter.java), [TcWriter](https://github.com/neolord0/hwpxlib/blob/main/src/main/java/kr/dogfoot/hwpxlib/writer/section_xml/object/table/TcWriter.java). 독립 hwpxlib로 크기·스팬·내용·서식 참조를 검사합니다.
- `FormulaCatalog`에는 실제 계산기가 구현한 16개 함수만 등록합니다. 전체 예제의 결과를 검사하며 이름/한국어 설명 검색과 입력 끝 식별자 완성을 제공합니다. 인용 문자열 안이나 셀 참조·완성된 수식은 교체하지 않습니다. `FormulaTools`는 기존 계산/서식 행에 배치해 키보드 사용 중 표 높이를 유지합니다.
- `PdfImages`는 [PDFStreamParser](https://pdfbox.apache.org/docs/2.0.13/javadocs/org/apache/pdfbox/pdfparser/PDFStreamParser.html)로 해제된 페이지 스트림을 제한된 크기/연산자 수 안에서 순회합니다. q/Q·cm 상태를 추적하고 선택한 직접 이미지의 Do 한 번에만 국소 변환을 적용합니다. 원래 CTM의 역행렬과 표시 페이지/CropBox/회전 변환을 합성하므로 공유 자원의 다른 위치는 유지합니다. 클리핑/ExtGState/태그/스텐실/중첩 이미지는 보호합니다. 삭제해도 공유 자원 바이트는 남을 수 있어 보안 삭제가 아닙니다.
- 외부 열기 인텐트는 `openExternal` 대기열에 남기고 busy가 풀린 뒤 기존 열기 경로로 처리합니다. 최대 32개이며 중복 대기 요청을 합칩니다. MainActivity는 singleTop으로 최상단의 중복 인스턴스를 줄이고 허용되는 URI 지속 권한을 확보합니다. Repository의 프로세스 공용 Mutex로 여러 인스턴스의 index 읽기/수정/쓰기를 직렬화합니다. 다중 프로세스/동시 편집 충돌을 완전히 해결했다는 의미는 아닙니다.
- ZIP 복원은 보존 자료를 별도 디렉터리에 격리하고 원래 경로로 재백업합니다. 이름 충돌 시 두 파일을 모두 보존합니다. 외부 pending 표식을 현재 베이스에 적용하지 않으며 실패한 복원은 자신이 생성한 임시 세션을 정리합니다. 내보내기 용량을 쓰기 전에 검사하고 실패 시 스테이징 파일도 정리합니다.
- `InkView`는 Paint/Path/StrokePainter를 재사용하고 필터/선분 Pair/부분 리스트 생성을 그리기 루프에서 제거했습니다. 진행 중 점 버퍼는 UI 스레드 안에서 참조하고 펜을 뗄 때 한 번만 불변 복사합니다. 완료 획이 다음 입력/clear에 변하지 않는지 검사합니다. MotionEvent 역사 샘플의 기울기는 각 샘플의 getHistoricalAxisValue로 읽습니다. 이 변경은 실제 S펜 지연 수치를 인증하지 않습니다.
- `rememberSlide`는 베이스/섹션/입력/서식 키마다 새 producer 상태를 만들고 IO 안에서 이미지 소유권을 확보합니다. 이전 장면이 해제된 뒤 새 로딩 동안 그 장면을 다시 그리지 않습니다. `SlideView`도 선택 Paint/StrokePainter를 재사용합니다. 한 장면은 같은 이미지 자원을 한 번만 디코딩하고 합계 800만 화소 안에서 유지하며, 로딩 실패 시 확보한 이미지도 정리합니다. 초과 이미지는 제외 수로 안내하고 원본 파트는 보존합니다. 장시간 프레임/메모리 벤치마크를 대신하는 보증은 아닙니다.
- 슬라이드 변환의 숫자 개체 순번은 삭제/계층 도구와 같은 직접 개체 목록을 사용합니다. `p:sp`만 세어 이미지 순번을 놓치던 경로를 고쳤으며 그림·일반 도형·연결선을 변환합니다. 그룹/표/차트 변환은 정밀 편집 안내로 거부합니다. HWPX 표의 런 라벨용 표 번호도 구역마다 한 번 계산해 각 셀에서 XML 전체를 재탐색하지 않습니다.

`scripts/verify-editing.ps1`은 JVM 재실행·전체 Android 검사·lint·debug 빌드와 XML/환경/APK SHA-256 수집을 묶습니다. 오래된 검사 XML은 fresh=false로 기록하고 통과 횟수에서 제외합니다. `-SkipDevice`는 부분 검사이며 전체 게이트 통과로 표시하지 않습니다.

## 2026-10-17 확장 설계

문서 ID를 캡처한 DocumentActions로 분할 창/SAF 선택기 콜백을 처리한다. 닫힌 대상을 활성 문서로 대체하지 않는다. Session별 History/선택/저장 대상은 독립적이다. DocumentTree는 허용된 tree URI 아래만 탐색하고 취소/노드 한도/중복 ID를 처리한다.

Stroke는 원시 샘플과 pressureCurve/smoothing/layerId/snapped, 선택적 CellInkBinding을 보존한다. InkGeometry가 표시 경로를 계산하고 StrokePainter가 경로와 입력 중 끝점을 캐시한다. Edits의 레이어/종이는 Undo/복구/ZIP/PDF에 포함된다. InkProjection은 현재 셀/저장 프레임 사이의 가역 투영, InkAnchors는 기본 행열 변경의 주소/소유권 변경을 담당한다. 정밀 엔진 재조판은 자동 추적하지 않는다.

PdfAnnotations는 표시 좌표를 CropBox/회전이 반영된 QuadPoints와 로컬 appearance로 변환한다. PdfInkAppearance는 표준 InkList에 필압을 반영한 벡터 appearance를 더한다. OCR은 번들 모델을 한 번에 한 건으로 제한하고 취소 후에도 SDK 완료까지 Bitmap을 소유한다. PdfOcrText는 원본 픽셀을 바꾸지 않는 비표시 텍스트와 한글 subset 폰트를 저장한다.

EncryptedBackup은 bounded ZIP을 AES-GCM 인증 봉투로 감싼다. 인증이 끝난 private 임시 ZIP에 기존 복원 검사를 적용하며 암호 오류 시 인덱스/출력을 갱신하지 않는다. 암호 CharArray는 요청 생명주기에 묶어 정리하며 복구 상태에 기록하지 않는다.

OfficeProbeProvider는 debug에만 존재하는 비공개 동일 UID 검사 접점이다. 실제 네이티브 엔진을 열고 클라이언트 명령을 보낸 뒤 저장한 OOXML을 검사한다. WebView 원격 디버깅은 켜지 않는다. release 검증은 검사 provider/authority가 없는지 확인한다.


## HWP 변환 트랜잭션 (0.5.0)

`HangulConversion`은 고정 upstream Java 변환기를 감싸며, OLE/압축 스트림과 레코드 중첩을 먼저 제한한다. hwplib 모델의 제어 선별·구역/하위 문단/셀·자원 인벤토리 → HWPX 쓰기 → 안전 DOM 재열기와 범위별 해시/구조 대조 → 검증 후 이동 순서다. 결과 앱/OS 메타데이터도 실제 제작 앱으로 지정한다.

Repository는 원본 작업을 체크포인트하고 새 UUID 디렉터리에서 편집 HWP를 materialize한다. 검증한 새 HWPX와 이전 원본/편집본/보고서만 새 탭으로 연결한다. 실패 시 새 디렉터리를 폐기한다. 원본 URI에는 쓰지 않는다. 구역 순서로 필기 앵커를 재연결하며 편집 명령은 새 기본 파일에 반영하고 새 Undo 이력을 시작한다. 보존 자료는 `preserved-backup/conversion`에 두고 백업 가져오기는 허용한 세 경로만 격리 복원한다. 보고서 읽기는 버전/크기/범위·원본 해시를 검사하며 변환 시점의 기록으로 표시한다.

## 0.6.0 형식별 작업 공간

WorkspaceHome은 형식별 생성과 최근 파일 필터, WorkspaceComponents는 형식별 색/아이콘/도구 탭, FormatWorkspace는 도구 탭과 본문·시트·슬라이드·PDF·필기를 연결합니다. DocumentPaper는 기존 필드 ID를 유지하며 본문과 한글 표 격자를 입력합니다. CommandInput은 엔진 인수를 스위치·목록·팔레트로 표현합니다. 기존 DocumentActions의 세션 고정을 유지합니다.

UI에서 요청한 새 문서·열기·최근 작업과 외부 열기는 visual 옵션으로 오피스 편집을 요청합니다. EditorViewModel은 파일을 성공적으로 붙인 뒤 세션 ID와 일회성 토큰을 발급합니다. Compose는 작업이 끝났을 때 토큰을 소비하고 기존 안전한 openOffice 경로를 호출합니다. 회전이나 정밀 편집 복귀로 다시 실행하지 않으며, 일반 저장소/모델 연산은 명시적 visual 요청 없이 편집기를 띄우지 않습니다. Android 10과 설정을 끈 경우 기본 편집을 사용합니다.
