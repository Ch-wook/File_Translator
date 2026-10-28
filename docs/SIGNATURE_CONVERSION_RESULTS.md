# 자필 서명·파일 변환 개발 및 검증 기록

2026-10-28. 원격 0.8.7 검증 커밋 5a655fd에서 기존 프로젝트를 이어서 수정했다. 이전 미푸시 0.8.0 원본을 회수한 작업이 아니다.

## 구현 경로

- 파일 변환 화면: 실제 입력 형식에 따른 대상 선택, 로컬 변환·진행 단계, 원본 보존, 결과 검증·다른 이름 저장·공유.
- PPTX/DOCX/XLSX 및 기존 Office: 원래 Collabora/LibreOffice native saveAs PDF 필터. 텍스트 추출 PDF나 현재 화면 캡처가 아니다.
- HWP/HWPX: Rhwp의 모든 문서 페이지를 원래 크기와 레이아웃으로 그려 Android PDF에 포함한다. 페이지 이미지 방식이므로 PDF의 텍스트 선택/검색/링크는 유지되지 않는다. 변환 전에 안내한다.
- TXT/Markdown PDF: Android StaticLayout·PdfDocument, 실제 문법 해석. 기본 서식만 지원하며 Markdown 원격 이미지·복잡한 표/HTML 레이아웃은 미지원이다.
- PDF→PNG/JPG: 선택/전체 페이지·해상도·페이지 번호 순서 ZIP. PDF→TXT는 실제 텍스트 계층만 추출하며 텍스트 없는 스캔 PDF에는 OCR 미지원 안내.
- Markdown→HTML/TXT, TXT→HTML/MD, 기존 검증된 HWP→HWPX 재사용.
- HWP/HWPX 자필 서명: 전용 터치 패널, 펜 색/굵기·압력·획 지우기·undo/redo·초기화 확인, 투명 PNG, native 그림 위치 지정·이동·크기/회전 도구, 기기 내부 서명 보관/재사용/수정/삭제.
- 편집기의 최신 문서 데이터를 변환한다. 메인에서 여는 변환은 선택한 파일/작업 복구본을 명시적으로 사용하며 원본 저장 성공과 혼동하지 않는다.
- 서버 사용 및 새 SDK 없음. 앱 INTERNET 권한 없음. Rhwp MIT, 기존 Collabora/LibreOffice 고지·라이선스, Android·CommonMark 및 PDFBox 기존 의존성을 사용한다. 유료 한컴 SDK/API는 도입하지 않았다.

## 검증 상태

새로운 변환·서명 검사는 진행 중이다. 실행 결과를 확인하기 전에는 완료로 계산하지 않는다.
첫 빌드의 Provider manifest 충돌은 변환 전용 Provider 클래스로 분리했다. 서명 선택 보존의 TypeScript private cursor 접근은 명시적 어댑터 캐스트로 수정한다.

합성 샘플: 표·그림·도형·배경이 있는 10장 PPTX, 머리말/꼬리말·표·그림이 있는 2페이지 DOCX, 수식·병합·배경·여러 시트 XLSX.
검사: native PDF 생성·저장·다시 읽기, 페이지 수·슬라이드 순서·내용·비율·원본 해시; 기본 텍스트/Markdown/PDF 변환; 실제 터치 서명·HWPX 내부 PNG·PDF 생성; 브라우저의 표 셀 서명 저장/재열기·독립 ZIP/XML 및 페이지 이미지 대조.

## 명확한 제한

실제 S펜 하드웨어·삼성/Gboard 및 ARM64 휴대폰 종합 검증은 미완료이다. 스타일러스 Pointer Events의 압력을 사용하지만 모든 기기의 손바닥 차단을 보장하지 않는다.
문서 위 직접 벡터 필기 모드·부분 획 분할 지우기·고급 표 셀 반응형 서명 위치 고정은 이번 서명 PNG와 별개로 남아 있다. 서명은 native 그림 문서 좌표로 저장한다.
Office 선택 슬라이드/시트/셀 범위 및 PDF 품질/페이지 맞춤의 새 상세 옵션, 일괄 변환, OCR, DOCX↔HWPX 및 편집 가능한 PDF→DOCX/PPTX/HWPX는 미지원이다. 선택 목록에 가짜 지원으로 표시하지 않는다.
인쇄·인쇄 미리보기 UI는 추가하지 않았다. 자필 이미지는 인증서 기반 전자서명이 아니다.
