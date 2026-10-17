# 원문 요구사항별 인수 확인표

기준: [0.5.0 실행 계획](EXECUTION_PLAN_20261017_PHASE2.md), [원문](REQUIREMENTS.ko.md) §2–§20. 2026-10-18.

아래 표는 지원 여부와 확인 근거를 분리한다. **검증**은 기재한 입력·환경에 한정한다. **정밀/미검증**은 내장 엔진의 기능 경로가 있지만 해당 전문 기능을 각각 검사하지 못했다는 뜻이다. 전체 행이 통과한 것으로 집계하지 않는다. UI 경로·제약은 [사용 안내](USER_GUIDE.md)와 [지원표](SUPPORT_MATRIX.md), 실제 실행 횟수는 [검사 결과](TEST_RESULTS.md)를 따른다.

검사 약어: Core/Feature/Compatibility/Objects/Sheet/Legacy/Ink는 `core/src/test/kotlin/kr/documentstudio/core/`의 해당 `*Test` 클래스다. Integration/Workflow/EditorUI/OfficeBridge는 `app/src/androidTest/java/kr/documentstudio/`의 기존 검사다. 이번에 추가한 Catalog/HangulTables/BackupSafety/PdfImageEditing/InkInput/AcceptanceUI는 각각 `FormulaCatalogTest`, `HangulTablesTest`, `BackupSafetyTest`, `PdfImageEditingTest`, `InkInputTest`, `FeatureAcceptanceUiTest`다.

## 플랫폼·파일·공통 화면

| ID | 요구 기능 | 상태·사용 경로 | 근거와 확인하지 못한 조건 |
|---|---|---|---|
| F01.1 | Android 10 이상, 폰·태블릿·Galaxy Tab | API 35 폰/태블릿 창 검증, 앱 min 29, 정밀 엔진 min 30 | EditorUI/Workflow/AcceptanceUI. API 29/36/37·실제 Galaxy 미검증 |
| F01.2 | 화면 회전·적응형 배치·여러 탭 | 검증 | EditorUI/Workflow의 재생성·탭·입력 보존. 실제 OS 멀티윈도우는 미검증 |
| F01.3 | 분할 화면·동시 문서 편집·우측 속성 패널 | 두 문서 동시 편집 검증, 상시 패널 제한 | DocumentActions/SplitEditorUi: 독립 입력·Undo·저장·재생성·닫기. 크기에 따라 좌우/위아래/창 전환. 상시 우측 속성 패널 없음 |
| F01.4 | S펜·스타일러스·손가락·마우스·Bluetooth 키보드 | 입력 코드와 합성 이벤트 검증, 장치 미검증 | InkInput/EditorUI/Workflow. 실제 지연·버튼·호버·팜리젝션은 별도 |
| F01.5 | APK·AAB·서명 | 0.5.0/code 6 APK/AAB 생성·기존 키 서명·설치 검증 | apksigner/jarsigner·권한·검사 provider 제외·16 KB ZIP/ELF·36개 라이브러리 일치. 출시 APK의 SAF 저장/미저장 입력 강제 종료 복구. Play 제출 없음 |
| F02.1 | TXT/MD/PDF/DOCX/DOC/XLSX/XLS/PPTX/PPT/HWPX/HWP 열기 | 검증(제한 있는 형식 포함) | Integration에서 형식별 열기·편집·저장·재열기; Core/Legacy/Compatibility |
| F02.2 | 내부 MIME·헤더·ZIP/OLE 구조 판별, 잘못된 확장자 | 검증 | Core/Legacy/Integration. 이름만으로 오피스 형식을 신뢰하지 않음 |
| F02.3 | 암호화·서명·매크로·손상 파일 오류 | 거부 경로 검증 | Core/Legacy/Integration. Office/PDF 자체 암호 입력·서명 보존 편집·매크로 실행은 미지원. 별도 .dsbackup 암호 복원은 지원 |
| F02.4 | SAF 열기/생성, Downloads/Documents/SD/제공자 | FileProvider/실제 Downloads 검증, 나머지 제공자 미검증 | Integration/Workflow. SD·Drive는 시스템 제공자/권한에 의존 |
| F02.5 | ACTION_VIEW/ACTION_SEND·URI 권한·연속 열기 | 검증 | AcceptanceUI: 작업 중 두 인텐트 대기, 기존 입력 보존, 재생성. 제공자가 허용할 때만 지속 권한 |
| F03.1 | 새 문서 형식별 생성·미지원 선택 안내 | 검증 | Integration/EditorUI; DOC 새 생성은 이유와 함께 비활성 |
| F03.2 | 홈·최근·즐겨찾기·이름 검색·설정 | 검증(허용 폴더 범위) | DocumentTree/EditorUI/Workflow. SAF 하위 폴더 이름 검색·취소·순환 보호, 실제 Documents 파일 편집/저장. 전체 기기 무권한 검색 아님 |
| F03.3 | 파일명·수정 상태·저장·메뉴·Undo/Redo·뒤로·탭 | 검증 | Workflow/EditorUI/AcceptanceUI; 작업 중 명령 보호, 입력 대상별 이력 |
| F03.4 | 폰 키보드·스크롤 도구·접을 수 있는 메뉴·터치 크기·서식 패널 | API 35 창에서 검증/제한 | Workflow/AcceptanceUI. 서식은 스크롤 AlertDialog이며 Bottom Sheet 아님. 접근성 전문 감사·모든 글꼴 배율 조합은 미검증 |

## Word·한글

| ID | 요구 기능 | 상태·사용 경로 | 근거와 확인하지 못한 조건 |
|---|---|---|---|
| F04.1 | 글꼴·크기·색·굵게·기울임·밑줄·취소선·강조·위/아래첨자·자간 | DOCX 기본 도구 검증, DOC는 정밀 편집 | Feature/Objects/Integration에서 실제 저장 서식 검사 |
| F04.2 | 정렬·줄/앞뒤 간격·들여쓰기·내어쓰기 | DOCX 기본 도구 검증 | Feature/Objects. 복잡한 스타일 상속은 Writer에서 확인 |
| F04.3 | 서식 복사·스타일·제목·글머리표·번호·다단계 목록 | 정밀 경로, 제목/번호 저장 검증 | OfficeProfessionalFeatures: Writer 제목 1·번호 OOXML 저장. 나머지 개별 기능/외부 Word 왕복 미검증 |
| F05.1 | A4·방향·여백·페이지 나누기·다단 | DOCX 기본 도구 검증 | Feature/Objects/Integration |
| F05.2 | 구역 나누기·다른 용지·페이지 배경 | 정밀/미검증 | Writer 경로, 기본 도구는 A4·마지막 구역 한정 |
| F05.3 | 머리글/바닥글·쪽 번호·링크·이미지 | 기본 추가 기능 검증 | Objects/Integration. 이미지 후속 크기 변경·다중 구역은 정밀/미검증 |
| F05.4 | 표 생성·행열 변경·병합/분할·셀 배경/테두리/정렬 | 기본 도구 검증(직사각형 등 제한) | Objects/Feature/Integration, 독립 POI 재열기 |
| F05.5 | 도형·텍스트 상자·수식·각주/미주·목차·주석·변경 추적 | 정밀 경로, 각주/변경 추적 검증 | OfficeProfessionalFeatures: 실제 Writer 각주 텍스트·ins·trackRevisions 저장. 나머지 개별 기능/외부 호환성 미검증 |
| F05.6 | 원본 레이아웃·서식 보존 | 파트 보존/저장 구조 검증, 시각적 일치 미보장 | Compatibility/Objects/OfficeBridge. 외부 코퍼스·글꼴 대체 검사 필요 |
| F06.1 | HWP/HWPX 한글·영문 입력 | 일반 문자 구간 검증 | Legacy/Feature/Integration. HWP의 제어·범위 태그·일부 유니코드는 보호 |
| F06.2 | HWPX 글꼴·크기·색·굵게·기울임·밑줄·취소선·문단 정렬·줄 간격 | 기본 도구 검증 | Feature/Integration, 별도 hwpxlib 재열기. 한컴 조판 인증 아님 |
| F06.3 | 한글 자간·장평·글자 위치·스타일·문단 번호·글머리표 | HWPX 구조 편집 검증, HWP 미지원 | HangulFormatting: charPr/paraPr·기존 styleID·7수준 번호/글머리표를 hwpxlib 재열기. 한컴 조판 인증 아님 |
| F07.1 | HWPX 표 생성·행/열 추가/삭제·병합/분할·크기·배경·테두리·셀 세로 정렬 | 검증(기본 구조) | HangulTables/AcceptanceUI. 최대 50×20, 보호/중첩/불규칙 표 거부, 병합 내용 유지 |
| F07.2 | HWP 표 구조·표 정렬·개별 셀 삽입/삭제 | HWPX 표 가로 정렬 검증, 나머지 제한 | HangulFormatting/HangulTables: LEFT/CENTER/RIGHT. HWP는 일반 문자열만. HWPX 셀 구조는 직사각형 행열 단위 |
| F07.3 | HWPX 편집 용지·여백·쪽 나누기·문단 추가 | 검증(A4) | Feature/Integration |
| F07.4 | 한글 다단·구역·머리말/꼬리말·쪽 번호·각주/미주·개체·글상자·수식·차례 | HWPX 균등 다단 검증, 나머지 미지원 | HangulFormatting: 1–4단/간격 OWPML 저장. 복잡한 다단·전문 조판/개체 엔진 없음 |
| F07.5 | HWP↔HWPX 변환·변환 손실 검사 | HWP→HWPX 구조 변환·대조 구현, 역변환/시각 일치 미지원 | HangulConversion JVM/Android/UI: 문단/셀 텍스트, 표/셀/개체 수, 그림 해시·참조와 수식 대조. 새 탭·원본/작업본·필기/레이어·보고서·ZIP/암호 백업 보존. [제한](HANGUL_CONVERSION.md) |

## Excel·PowerPoint

| ID | 요구 기능 | 상태·사용 경로 | 근거와 확인하지 못한 조건 |
|---|---|---|---|
| F08.1 | 셀 입력·범위·클립보드 복사/붙여넣기·상대/절대 참조 복사·자동 채우기 | 기본 XLSX 검증, XLS 입력 검증 | Sheet/Integration/Workflow/EditorUI. 최대 10,000셀 명령 |
| F08.2 | 행열 추가/삭제·높이/너비·병합/해제 | 기본 XLSX 검증(안전 제한) | Sheet/Feature/Integration. 복잡한 관계 포함 시 Calc 사용 |
| F08.3 | 글꼴·글자/배경색·정렬·숫자/통화/날짜 서식 | 기본 XLSX 검증 | Feature/Workflow/Integration; 저장 서식과 화면 계산 함께 확인 |
| F08.4 | 테두리·조건부 서식 | XLSX 기본 저장 검증, 전체 시각화는 Calc | SheetDataFeatures/SheetDataUi: 범위 테두리, 숫자 비교/중복 강조, 해당 범위 규칙 지우기. 독립 POI 검사 |
| F09.1 | 수식 입력줄·자동 계산·오류·다른 시트 참조·1904 날짜 | 기본 계산 범위 검증 | Feature/FormulaLimits/SpreadsheetModel/Sheet/Workflow |
| F09.2 | SUM·AVERAGE·COUNT·COUNTIF·SUMIF·IF·IFS·VLOOKUP·XLOOKUP·INDEX·MATCH·ROUND·MAX·MIN·TEXT·CONCAT | 16개 함수 검증, 전체 Excel 의미론과 구분 | Catalog의 예제 전부 계산 + Feature/FormulaLimits의 오류/조건/한도 |
| F09.3 | 함수 검색·함수 자동 완성 | 검증 | Catalog/AcceptanceUI. 이름 또는 한국어 설명 검색, 입력 끝 함수 접두어 완성 |
| F10.1 | 정렬·필터 범위·시트 추가/삭제/복제/이름·틀 고정 | 기본 XLSX 검증 | Sheet/Feature/Integration; 복잡한 개체가 있는 시트 복제 거부 |
| F10.2 | 필터 조건·데이터 검증·중복·드롭다운·표 생성 | 목록/중복 강조/표 저장 검증, 필터 조건은 정밀 | SheetDataFeatures/SheetDataUi: 목록 검증·표 관계/열/스타일, 겹침/헤더/용량 거부. 기본 격자는 규칙을 직접 집행/조판하지 않음 |
| F10.3 | 막대/선/원/분산형 차트·편집·범례/축·피벗 | 정밀/미검증 | 기존 파트 보존 검사와 개별 차트 편집 검사를 구분 |
| F10.4 | 숨김 시트·메타데이터·기존 수식/차트 보존, 매크로 | 보존 제한 검증, 매크로 거부 | Compatibility/Sheet. 외부/3D/배열/동적 참조 전체 갱신 미지원 |
| F11.1 | 슬라이드 추가/삭제/복제/순서 | 기본 PPTX 검증 | Feature/Objects/Integration. 노트/복잡한 관계가 있으면 안전하게 거부 |
| F11.2 | 레이아웃·테마·배경·마스터 | 정밀/미검증 | Impress 경로. 기본 미리보기의 지원으로 세지 않음 |
| F12.1 | 텍스트 상자·기본 도형·이미지·이동·크기·회전·계층·삭제 | 기본 PPTX 검증 | Objects/Feature/Integration. 애니메이션 참조 개체의 삭제 보호 |
| F12.2 | 표·차트·그룹화·정렬 | 정밀/미검증 | Impress 경로, 기본 미리보기는 그룹/차트 등을 재현하지 않음 |
| F12.3 | 기본 발표·전체 화면·필기 포함 PDF | 제한 검증 | Integration/EditorUI, 직접 텍스트·그림·일부 도형만 표시 |
| F12.4 | 전환·애니메이션·발표자 노트·원본 배치 보존 | 정밀/미검증, 수정하지 않은 파트 보존 검사 | Compatibility/Objects. 실제 Office 발표/효과 왕복 미검증 |

## PDF·TXT·Markdown

| ID | 요구 기능 | 상태·사용 경로 | 근거와 확인하지 못한 조건 |
|---|---|---|---|
| F13.1 | 렌더·축소판·단일/연속/가로 보기·확대 | 검증 | Integration/EditorUI/PdfMarkupUi: 가로 페이지 넘김, 페이지별 비트맵 수명 |
| F13.2 | 검색·텍스트 선택·북마크·스캔 OCR | 검증(인식 한도 내) | OcrIntegration/OcrUi: 번들 한국어/영어, PDF 범위 20장, 검색 텍스트·원본 픽셀/회전 보존 |
| F14.1 | 자유필기·형광펜·도형·화살표·수기 서명 | 검증(공통 필기) | Ink/InkErasure/Integration/EditorUI. 수기 서명은 전자서명 인증 아님 |
| F14.2 | 밑줄·취소선·메모·텍스트 상자 | 선택 문자 주석/관리 검증 | PdfAnnotations/PdfMarkupUi: Highlight/Underline/StrikeOut QuadPoints/AP, 외부 주석·답글 보호·수정/삭제·Undo. 메모/본문 추가 별도 |
| F15.1 | 페이지 추가/삭제/순서/회전·병합·분할/추출 | 검증(양식 등 제한) | Integration/EditorUI. 양식 위젯 페이지 삭제/양식 병합·추출 보호 |
| F15.2 | 본문 추가·기존 본문 직접 수정 | 제한 검증 | Integration, 가로 Tj/TJ·원본 폰트로 인코딩 가능한 문자열. 재줄바꿈/세로/중첩/태그 편집 없음 |
| F15.3 | 이미지 추가·기존 이미지 이동/크기 변경·삭제 | 검증(직접 이미지) | PdfImageEditing/AcceptanceUI. 직접 XObject 출현 단위, 공유 이미지의 다른 위치 보호. 중첩/클리핑/태그/스텐실 제외 |
| F15.4 | 양식 입력 | 검증(지원 필드) | Integration. 읽기 전용·지원하지 않는 필드는 비활성 |
| F15.5 | PDF/A 편집 후 적합성·규격 유지 불가 시 일반 PDF 별도 저장 | 감지/일반 복사 검증, 규격 검증 미지원 | Integration. 원본 덮어쓰기 금지. veraPDF/인증 없음 |
| F16.1 | TXT 입력/삭제·줄 번호·자동 줄바꿈·글꼴 크기·인코딩·단어 검색/치환 | 검증/일부 UI 검증 | Core/Integration/EditorUI/Workflow. 인코딩 표현 불가 문자는 거부 |
| F16.2 | 단축키 | Ctrl+Z/Y/Shift+Z/S 검증 | Workflow. 실제 Bluetooth 키보드·제조사 IME 조합 미검증 |
| F17.1 | MD 원본·미리보기·제목·목록·체크리스트·표·코드·링크·인용·굵게/기울임 | 검증 | MarkdownEditing/Tasks/Ui: 원문 위치 기반 토글·문법 도구·실제 WebView 클릭·Undo/Redo. JavaScript/네트워크 차단 |
| F17.2 | MD PDF·렌더 데이터 일치 | 검증 | Integration, 미리보기와 내보내기가 같은 렌더러 사용. 원본 배치/웹 CSS와의 일치 미보장 |

## 필기·데이터 보호·품질

| ID | 요구 기능 | 상태·사용 경로 | 근거와 확인하지 못한 조건 |
|---|---|---|---|
| F18.1 | 좌표·필압·기울기·도구·펜 버튼/지우개·호버 | 코드/합성 이벤트 검증 | InkInput/EditorUI/Integration. 실제 S펜 이벤트·하드웨어 분류 미검증 |
| F18.2 | 볼펜·만년필·연필·브러시·형광펜·자유선·직선/화살표/사각형/원 | 검증(기본 모양/스타일) | Ink/Integration/InkInput. 필기체 질감의 실제 기기 평가는 별도 |
| F18.3 | 색·굵기·투명도·필압 반응·설정 저장 | 검증 | InkAdvanced/Persistence/Ui: 필압 곡선·설정 재열기·PDF 메타데이터/AP |
| F18.4 | 획 보정·즐겨찾는 펜·도형 자동 보정 | 검증 | InkAdvanced: 원시 샘플 유지, 펜 12개/색 16개, 선택적 도형 보정. 실제 질감/지연 측정 별도 |
| F19.1 | Undo/Redo·올가미·변형·복사·삭제·숨김·다중 레이어 | 검증 | InkAdvanced/Persistence/Ui: 최대 8개, 표시/잠금/순서/이름·잠긴 획 지우기/변형 거부 |
| F19.2 | 스타일러스 우선·펜 전용·손가락 스크롤·입력 우선순위 | 코드/합성 이벤트 검증, 실제 손바닥 미검증 | EditorUI/InkInput. 하드웨어 손바닥 분류를 구현했다고 표시하지 않음 |
| F19.3 | 두 손가락 확대·이동·페이지 보기·모드·도구 모음 | 검증(지원 경로) | EditorUI/PdfMarkupUi/InkAdvancedUi: PDF 연속/가로 보기, 도구 위/아래·접기. 연속 보기 직접 획 입력 없음 |
| F19.4 | 낮은 지연·빠른 샘플·해상도·선명도·페이지 렌더 | 샘플/좌표 보존 검증, 지연 수치 미검증 | InkInput: 역사 기울기·불변 획. 프레임 임시 객체·샘플별 전체 복사 제거. 예측 입력 없음 |
| F19.5 | OCR·손글씨 검색/변환·템플릿·색 즐겨찾기 | 일반 이미지 OCR/템플릿/색 검증, 궤적 전용 인식 미지원 | OcrIntegration/Ui: 이미지/PDF/필기 이미지→검토→TXT/검색 PDF. InkAdvanced: 무지/줄/격자/점. 전용 손글씨 모델 아님 |
| F20.1 | PDF 표준 Ink·필압/도구·재열기 | 검증 | Integration/InkAdvancedPersistence/PdfInkAppearance: 4회전/CropBox, 필압별 벡터 굵기·원시 샘플 왕복 |
| F20.2 | Word/HWP/HWPX/TXT/MD 별도 필기·파일별 독립·경고 | 검증(고정 페이지) | Integration/Workflow. 본문 재배치 추적/원본 내장 아님 |
| F20.3 | 슬라이드/시트·셀 좌표 추적 | 기본 XLSX 셀/슬라이드 구조 검증, 정밀 재배치 제한 | InkAnchors/CellInkIntegration/Ui: 행열·크기·확대·복제·지우개·Undo·ZIP/PDF. Calc 자체 구조 변경 자동 추적 없음 |
| F20.4 | 내보내기·ZIP·암호 백업·삭제 정책 | 검증 | EncryptedBackup/Integration/Ui·TemporaryFiles: 인증 전 가져오기 금지·암호 오류/변조 원본 보존·기존 ZIP. 탭 닫기 복구본 보존 |
| F21.1 | 저장·다른 이름·위치 변경·변환·PDF/필기 출력·인쇄 | 지원 경로 검증, 임의 포맷 간 변환 미지원 | Integration/Workflow. Office 엔진의 PDF 출력과 기본 PDF 출력 구분 |
| F21.2 | 임시 검증·원본 백업·해시 충돌·실패 시 롤백/복구 | 검증 | Integration. 비원자적 제공자 실패/복원 실패를 따로 재현 |
| F21.3 | 자동 작업 저장·백그라운드·강제 종료 복구·수정 상태 | 검증(체크포인트 이후), 직전 미완성 입력 제한 | Workflow/Integration/OfficeBridge, 자동 원본 덮어쓰기 아님 |
| F21.4 | ZIP 복원 후 원본/실패 작업 파일 재백업·안전한 경로·공유 인덱스 | 검증 | BackupSafety. 외부 정밀 편집 pending은 격리 보관하고 자동 실행하지 않음 |
| F22.1 | 페이지 단위·가상 스크롤·슬라이드 캐시·백그라운드 파싱/계산·생명주기 | 구현/동작 검증 | 기존 검사와 AcceptanceUI의 슬라이드 이미지/Undo/재생성, SlideMemoryTest. 슬라이드 이미지 합계 800만 화소 한도·같은 자원 재사용. 기본 표는 최대 25×6 창 |
| F22.2 | 대용량·메모리·저사양·렌더 지연 | 제한/성능 미인증 | 파일/ZIP/수식/필기 작업량 한도. 실제 저사양·대용량 코퍼스·장시간 측정 필요 |
| F23.1 | 오프라인·권한·링크·매크로·로그·임시 파일 | 구조 검토/거부 검사·임시 정리 검증 | INTERNET/전체 저장소 권한 없음, OCR 모델 번들, 24시간 지난 지정 임시 파일만 정리. 진행 중 인쇄/문서/복구 보존. SAF 제공자 동작 별도 |
| F24.1 | 모듈·엔진 비교·라이선스·비용·파일 구조·개발/배포 절차 | 문서·빌드 검사 | ARCHITECTURE/THIRD_PARTY/BUILD_AND_RELEASE/PROVENANCE |
| F24.2 | 외부 Word/Excel/PowerPoint/한컴 재열기·시각적/구조적 무손실 | 외부 검증 필요 | 독립 POI/hwpxlib 재열기를 외부 제품 검사로 세지 않음 |

## 파일별 공통 순환 검사

| 형식 | 열기 | 실제 편집 | 원본 형식 저장·재열기 | 필기 보존 | 독립 검사/남은 조건 |
|---|---|---|---|---|---|
| TXT | Integration | 문자열 | 바이트·줄바꿈·BOM | 앱 복구본 | Core 인코딩, 실제 외부 앱 미검증 |
| MD | Integration | 원본 문자열 | 재구성·PDF 텍스트 | 앱 복구본/ZIP | 미리보기와 같은 렌더러, 외부 앱 미검증 |
| PDF | Integration | 본문/페이지/양식/이미지 | 새 URI·표준 주석 | PDF·앱 메타데이터 | PDDocument·Android 렌더, 표준 인증 미실시 |
| PDF/A | Integration | 일반 PDF 경로 | 덮어쓰기 거부·일반 복사 | 표준 Ink | PDF/A 적합성 판정 없음 |
| DOCX | Integration | 런·서식·표·이미지, Writer | 실제 ZIP 파트 | 별도 복구본 | POI/OfficeBridge, 외부 Word 미검증 |
| DOC | Integration | 런·Writer | OLE | 별도 복구본 | HWPF, 새 생성 미지원, 외부 Word 미검증 |
| XLSX | Integration/Workflow/AcceptanceUI | 셀·수식·서식·구조, Calc | 실제 셀·수식·서식 | 시트별 복구본 | POI, 외부 Excel 미검증 |
| XLS | Integration/Workflow | 셀·Calc | OLE | 시트별 복구본 | HSSF, 외부 Excel 미검증 |
| PPTX | Integration | 런·개체·슬라이드, Impress | 실제 개체·관계 | 섹션별 복구본 | POI, 외부 PowerPoint 미검증 |
| PPT | Integration | 런·Impress | OLE | 섹션별 복구본 | HSLF, 외부 PowerPoint 미검증 |
| HWPX | Feature/Integration/AcceptanceUI | 런·서식·표 | ZIP/OWPML | 별도 복구본 | hwpxlib, 외부 한컴·전문 조판 미검증 |
| HWP | Legacy/Integration | 일반 문자 구간 | OLE/레코드 | 별도 복구본 | hwplib, 외부 한컴·전문 조판 미검증 |

강제 종료 복구는 체크포인트 및 정밀 엔진 실패 시나리오로 확인하며 실제 펜 접촉 중 전원 차단까지 보장하지 않는다. 폰/태블릿 확인은 API 35 창 조건에 한정한다. 위 미지원·미검증 항목 때문에 “원문 전문 기능 모두 완벽”이라는 판정은 내리지 않는다.
