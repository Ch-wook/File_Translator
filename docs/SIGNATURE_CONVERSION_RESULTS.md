# 자필 서명·파일 변환 개발 및 검증 기록

2026-10-28. 원격 0.8.7 검증 커밋 5a655fd에서 기존 프로젝트를 이어서 수정했다. 이전 미푸시 0.8.0 원본을 회수한 작업이 아니다.

## 구현 경로

- 파일 변환 화면: 실제 입력 형식에 따른 대상 선택, 로컬 변환·진행 단계, 원본 보존, 결과 검증·다른 이름 저장·공유.
- PPTX/DOCX/XLSX 및 기존 Office: 원래 Collabora/LibreOffice native saveAs PDF 필터. 텍스트 추출 PDF나 현재 화면 캡처가 아니다.
- HWP/HWPX: Rhwp의 모든 문서 페이지를 원래 크기와 레이아웃으로 그려 PDFBox Android의 임시 저장소에 페이지 이미지 스트림으로 포함한다. 페이지 이미지 방식이므로 PDF의 텍스트 선택/검색/링크는 유지되지 않는다. 변환 전에 안내한다.
- TXT/Markdown PDF: Android StaticLayout·PdfDocument, 실제 문법 해석. 기본 서식만 지원하며 Markdown 원격 이미지·복잡한 표/HTML 레이아웃은 미지원이다.
- PDF→PNG/JPG: 선택/전체 페이지·해상도·페이지 번호 순서 ZIP. PDF→TXT는 실제 텍스트 계층만 추출하며 텍스트 없는 스캔 PDF에는 OCR 미지원 안내.
- Markdown→HTML/TXT, TXT→HTML/MD, 기존 검증된 HWP→HWPX 재사용.
- HWP/HWPX 자필 서명: 전용 터치 패널, 펜 색/굵기·압력·획 지우기·undo/redo·초기화 확인, 투명 PNG, native 그림 위치 지정·이동·크기/회전 도구, 기기 내부 서명 보관/재사용/수정/삭제.
- 편집기의 최신 문서 데이터를 변환한다. 메인에서 여는 변환은 선택한 파일/작업 복구본을 명시적으로 사용하며 원본 저장 성공과 혼동하지 않는다.
- 서버 사용 및 새 SDK 없음. 앱 INTERNET 권한 없음. Rhwp MIT, Collabora/LibreOffice MPL-2.0 및 구성요소별 기존 고지, AndroidX Apache-2.0, CommonMark BSD-2-Clause, PDFBox Android Apache-2.0 의존성을 사용한다. 유료 한컴 SDK/API는 도입하지 않았다.

## 검증 상태

브라우저 실행 [40653387077](https://github.com/Ch-wook/File_Translator/actions/runs/40653387077) 성공: 기존 JavaScript 28건·Rhwp 회귀, HWP/HWPX 두 서명 시나리오, 독립 PNG/ZIP/XML 및 저장 전후 페이지 이미지 검사 통과. Android 실행 40654428111: 5건 중 기본 TXT·Markdown·PDF 변환 1건 통과, WebView 컨트롤을 찾는 새 테스트 4건 실패. 접근성 캐시 갱신과 실제 화면 좌표를 사용하는 기존 테스트 방식으로 수정해 재실행 중이다. 전체 40654175816에서는 기존 39건이 통과했으며 새 테스트 클래스의 반환형 초기화 오류 1건 때문에 전체 성공으로 계산하지 않았다.
첫 빌드의 Provider manifest 충돌은 변환 전용 Provider 클래스로 분리했다. JUnit 진입 함수의 nullable 반환형은 void 함수로 고쳤다. 서명 선택 보존의 TypeScript private cursor 접근은 명시적 어댑터 캐스트로 수정한다.

합성 샘플: 표·그림·도형·배경이 있는 10장 PPTX, 머리말/꼬리말·표·그림이 있는 2페이지 DOCX, 수식·병합·배경·여러 시트 XLSX.
검사: native PDF 생성·저장·다시 읽기, 페이지 수·슬라이드 순서·내용·비율·원본 해시; 기본 텍스트/Markdown/PDF 변환; 실제 터치 서명·HWPX 내부 PNG·PDF 생성; 브라우저의 표 셀 서명 저장/재열기·독립 ZIP/XML 및 페이지 이미지 대조.

## 명확한 제한

실제 S펜 하드웨어·삼성/Gboard 및 ARM64 휴대폰 종합 검증은 미완료이다. 스타일러스 Pointer Events의 압력을 사용하지만 모든 기기의 손바닥 차단을 보장하지 않는다.
문서 위 직접 벡터 필기 모드·부분 획 분할 지우기·고급 표 셀 반응형 서명 위치 고정은 이번 서명 PNG와 별개로 남아 있다. 서명은 native 그림 문서 좌표로 저장한다.
Office 선택 슬라이드/시트/셀 범위 및 PDF 품질/페이지 맞춤의 새 상세 옵션, 일괄 변환, OCR, DOCX↔HWPX 및 편집 가능한 PDF→DOCX/PPTX/HWPX는 미지원이다. 선택 목록에 가짜 지원으로 표시하지 않는다.
인쇄·인쇄 미리보기 UI는 추가하지 않았다. 자필 이미지는 인증서 기반 전자서명이 아니다.

## 출처·관련 자료

[Office 고정 바이너리·소스와 MPL 고지](../officeruntime/PROVENANCE.md), [Rhwp 고정 소스·MIT·폰트](../web/hangul/PROVENANCE.md), [기존 라이선스 목록](THIRD_PARTY.md), [HWP→HWPX Apache-2.0 출처](HANGUL_CONVERTER_PROVENANCE.md). 한컴 유료 SDK나 서버 API의 이용 권한을 가정하지 않았다. 한컴 공식 규격/변환 지원표는 향후 상호 변환의 검토 자료이며 현재 엔진이 모든 형식을 HWPX로 변환한다고 해석하지 않는다.

## 변경 소스

- `ConversionActivity.kt`, `ui/WorkspaceHome.kt`, `ui/StudioApp.kt`, `EditorViewModel.kt`: 전용 화면과 기존 편집 화면 연결, 최신 복구본 준비.
- `data/PortableConversions.kt`, `data/ConversionFiles.kt`, `data/PdfEngine.kt`: 변환 행렬, 페이지 범위·해상도, 임시 파일/원본 보호·검증·PDF 이미지 처리.
- `ConversionFileProvider.kt`, `res/xml/conversion_paths.xml`, `AndroidManifest.xml`: 결과 캐시에 한정한 저장·공유 권한.
- `web/hangul/signature.js`, `host.js`, `host.css`, `engine-adapter.ts`, `HangulEditorActivity.kt`: 서명 패널·보관함·native 그림, 모든 페이지 출력, 변환 중 변경 차단과 편집 위치 복원.
- `officeruntime/.../LOActivity.java`, `StudioOfficeActivity.java`, `dist/studio-policy.js`: 실제 native PDF 필터·원본 보호·결과 검증, 최신 입력 처리, 변환 전용 읽기 상태.
- `scripts/prepare-conversion-fixtures.py`, `scripts/tests/signature-conversion.cjs`, `scripts/verify-signature-conversion.py`, `ConversionIntegrationTest.kt`, debug probe: 합성 샘플과 브라우저/native·독립 파일 검사.
- 빌드·패키지·설치·게시 워크플로와 출처 문서: ABI별 결과·소스·패치·Git 이력·검증 자료·해시 보존.

## 형식별 변환 상태

| 입력 | 구현한 대상 | 제한 |
|---|---|---|
| PPTX/DOCX/XLSX 및 기존 DOC/XLS/PPT | native PDF | 문서 엔진의 현재 페이지 설정 사용; 새 상세 범위/페이지 맞춤 옵션은 미지원 |
| HWP/HWPX | 페이지 이미지 PDF | 텍스트 검색·선택·링크 미지원; 보호된 배포용 문서는 차단 |
| MD | 기본 서식 PDF, HTML, TXT | PDF의 원격 이미지·복잡한 표/HTML 배치 미지원 |
| TXT | PDF, HTML, MD | PDF 기본 글자 크기 지정; 입력 인코딩 UTF-8/MS949 선택과 기존 감지 경로 |
| PDF | PNG/JPG 페이지 ZIP, TXT | 72/144/216dpi 및 페이지 범위; 이미지 개별 저장·OCR·편집 가능한 문서 복원은 미지원 |
| HWP | 기존 HWP→HWPX | 기존 지원 제어와 보존 검사 범위, 미지원 요소는 변환 거절 |

이 표는 구현 경로와 제한이며 모든 입력 파일의 품질을 검증했다는 뜻이 아니다. 최종 Android·독립 파서 결과와 함께 지원 범위를 판단한다.

## 핵심 Android 실행 통과

[40659202763](https://github.com/Ch-wook/File_Translator/actions/runs/40659202763): Android 35 x86_64 2건, 실패·오류·건너뜀 0건. 10장 PPTX→10페이지 PDF의 텍스트·표·순서·16:9 비율·원본 해시, 실제 터치 서명→HWPX 저장→실제 엔진 재열기→PDF 저장·재열기·파란 서명 표시를 확인했다. 앞선 자동화의 뒤로가기가 문서까지 닫던 문제를 수정했다. 보호된 문서·변환 전용 세션은 편집으로 전환하지 않는다.

최종 APK 검증에서는 기존 39건과 새 5건 전부를 다시 실행한다. PPTX를 native 명령으로 11장으로 수정한 뒤 다시 변환해 최신 변경이 PDF 페이지 수에 반영되는 검사도 포함한다. 아직 이 최종 실행을 통과로 계산하지 않는다.
