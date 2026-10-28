# 문서 스튜디오 0.8.8-preview — 자필 서명·로컬 파일 변환

2026-10-28 UTC. 기존 프로젝트와 Rhwp·Collabora/LibreOffice·PDFBox·Android PDF 코드를 유지했다. 기준 소스는 공개 원격 0.8.7 커밋 `5a655fdd5dae50b00443bfe57a8a8a0c6acee963`이며, 이전 미푸시 0.8.0 원본을 복구한 작업이 아니다.

## 이번 구현과 지원 범위

| 입력 | 결과·편집 경로 | 실제 검사와 제한 |
|---|---|---|
| PPTX | 기존 native PDF 필터 | 10장→10페이지, 텍스트·표, 순서·16:9 비율. 실제 슬라이드 추가 후 11페이지로 재변환, 원본 해시 보존. 그림·도형 위치·배경색은 결과 렌더링으로 별도 검사. 애니메이션·영상은 재생되지 않는 정적 PDF이다. |
| DOCX | 기존 native PDF 필터 | 2페이지, 본문·표·머리말·꼬리말 텍스트와 그림, PDF 파서·Android 렌더러 재열기. 원본 해시 보존. 모든 복잡한 Word 요소의 동일성을 보장하지 않는다. |
| XLSX | 기존 native PDF 필터 | 2시트 순서·이름, 수식 계산 결과 42, 셀 배경색·원본 해시. 병합 포함 샘플 사용. 모든 병합·차트·페이지 맞춤 조합을 개별 검증한 것은 아니다. |
| HWPX | 전체 페이지 이미지 PDF | 실제 터치 서명→작업 HWPX 저장→실제 엔진 재열기→PDF 저장·재열기와 파란 서명 표시. 표 셀 서명은 브라우저·독립 ZIP/XML·페이지 이미지로 검사. PDF 텍스트 선택·검색·링크는 미지원이다. |
| HWP | 같은 전체 페이지 PDF 경로, 기존 HWP→HWPX | HWP 서명 저장/재열기와 전체 페이지 렌더링은 브라우저에서 검사했다. 별도 Android HWP→PDF 사례는 이번에 실행하지 않았다. 배포용 등 보호된 문서의 변환은 차단한다. |
| MD | 기본 서식 PDF, HTML, TXT | 제목·강조·표 포함 샘플의 변환·유효 PDF·HTML table·텍스트 검사. PDF는 기본 서식 범위이며 복잡한 표·HTML 블록·원격 이미지 보존은 미지원이다. |
| TXT | PDF, HTML, MD | 여러 페이지 한국어 텍스트 PDF와 원본 해시 검사. 입력 UTF-8/MS949 선택 및 기존 인코딩 감지 경로, PDF 크기 8~32pt. TXT→HTML/MD는 코드 구현이며 별도 실행 사례는 이번 검사에 포함하지 않았다. |
| PDF | PNG/JPG 페이지 ZIP, TXT | 72/144dpi의 선택 페이지·이미지 수·이미지 디코딩, 범위 정렬·텍스트 추출. 216dpi는 구현되어 있으나 별도 해상도 사례는 미실행. 스캔 PDF OCR과 이미지 개별 저장은 미지원이다. |
| DOC/XLS/PPT | 공통 native PDF 필터 연결 | 기존 native 열기/편집 회귀는 실행했다. 각 레거시 형식의 별도 PDF 변환 사례는 이번에 실행하지 않았다. |

페이지 이미지 PDF는 화면 일부 캡처가 아니다. Rhwp가 계산한 모든 페이지를 원래 문서 크기로 렌더링하고, PDFBox의 디스크 임시 저장소에 이미지 스트림으로 기록한다. 최대 300페이지·페이지당 픽셀 제한·이미지 입력량 제한을 두며 결과는 128MiB 이내로 제한한다. Office 변환은 실제 문서 모델의 PDF 필터를 사용한다. 파일 확장자만 바꾸거나 구조 문서를 추출 텍스트로 대체하지 않는다.

## 서명·손글씨

전용 패널에서 손가락/Pointer Events 펜 입력, 검정·파랑·빨강·사용자 색, 굵기·지원 기기 압력, 획 지우개·실행 취소/다시 실행·초기화 확인을 제공한다. 완료하면 투명 PNG를 기존 native 그림 배치 경로에 전달한다. 문단·표 셀·그림의 실제 문서 좌표를 사용하며 기존 그림 도구로 이동·크기·회전을 조절한다.

실제 검사: 손가락 터치, 빈 서명의 패널 내 오류 안내, 투명 PNG 포함, 표의 텍스트·2×2 행/열 유지, 저장 후 서명 색상·면적·중심 위치·선 모양 유지, PDF의 서명 표시. 기존 그림 이동·크기·undo 회귀도 유지했다. 서명만을 대상으로 모든 이동·회전·셀 크기 변경 조합을 실기기에서 검증한 것은 아니다.

기기 내부 서명 보관함에는 이름·PNG·획 정보를 AtomicFile로 저장하고 미리보기·다시 사용·불러와 수정·삭제를 연결했다. 보관함의 전체 Android UI 회귀는 별도 미실행이며, 불러와 수정한 서명을 새 항목으로 저장하는 범위다. 부분 획 분할 지우기·문서 위 직접 벡터 필기·고급 셀 반응형 고정은 미지원이다. 기존 별도 필기를 제거하지 않았다. 자필 그림을 인증서 기반 전자서명으로 표시하지 않는다.

## 파일 처리·모바일 UI

- 기존 7가지 문서 카드와 편집기를 유지하고 메인에 파일 변환 카드를 추가했다. 편집기 더보기에서 최신 편집 데이터를 사용한다.
- 변환 전용으로 연 native 문서는 읽기 상태를 유지한다. 원본 편집기의 글꼴·선택·표·이미지·한 줄 도구 구조를 유지한다.
- 알려진 페이지 수에는 실제 처리 페이지를, 나머지에는 단계/진행 중 표시를 사용한다. 가짜 백분율은 없다.
- 한글 PDF 변환 중 실제 엔진의 변경 명령을 잠그고 완료/취소 후 편집 상태·확대·스크롤을 복원한다. 취소 버튼과 진행 영역이 숨김 규칙에 가려지지 않게 수정했다.
- 결과를 임시 파일에서 검증한 후 사용자 지정 위치에 기록하고 다시 읽어 SHA-256을 비교한다. 기존 출력 파일 백업과 실패 복구를 사용한다. 원본과 같은 URI는 거절한다.
- 결과 PDF는 기존 공통 리더의 읽기 전용 화면에서 즉시 연다. 원본 편집 세션의 busy 상태를 기다리지 않으며, 이전 복구본 대신 실제 저장 파일의 바이트를 읽는다. 닫으면 원래 화면으로 돌아간다.
- PDF 이미지는 선택/전체 페이지를 번호 순서 ZIP으로 만든다. 결과 저장·열기·공유를 제공한다.
- 자동 저장은 작업 복구본이며 원본 파일 저장 완료와 구분한다. 외부 원본에 적용하려면 기존 저장/다른 이름 저장을 사용한다.

## 실행 검증

- 앱 코드 커밋: `be2ea66fede30792927e672bd35abcc45d509f15`.
- [최종 앱/엔진 검사 40665116237](https://github.com/Ch-wook/File_Translator/actions/runs/40665116237): Android 35 x86_64 **44건**, 단위 **148건**(core127 / Office2 / app19), JavaScript **28건** — 실패·오류·건너뜀 0건. 실제 엔진 브라우저 기존 7개와 서명 2개, 총 **9개 시나리오**, TypeScript·lint·독립 저장 파서 통과.
- 새 검사에는 PPTX 최신 수정의 11페이지 PDF 반영, DOCX/XLSX PDF, 서명 HWPX 저장·엔진 재열기·PDF 표시, TXT/MD/PDF 변환과 독립 읽기 전용 PDF 결과 화면을 포함한다.
- 빈 서명 오류의 패널 표시, PDF 진행/취소 표시, 변환 중 변경 차단과 취소 후 편집 복귀도 브라우저 실제 엔진에서 검사했다.
- 마지막 패키징의 익명 GitHub API만 HTTP403 사용량 제한으로 실패했다. [인증된 패키징 복구 40668030801](https://github.com/Ch-wook/File_Translator/actions/runs/40668030801)에서 성공 단계·원래 44개 결과를 확인하고 보존된 APK와 검사 자료를 그대로 사용했다. **재빌드·재서명하지 않았다.** 실패한 전체 실행을 성공이라고 바꾸어 보고하지 않는다.
- 양 ABI 서명·testOnly 미설정·16KiB 정렬·APK 엔진 자산 일치, x86_64의 일반 설치·실행·같은 APK 재설치와 기존 0.8.7 APK/개인 데이터 보존을 확인했다. 실제 ARM64 휴대폰·S펜 하드웨어 검증은 미완료다.

핵심 Android 검사 [40659202763](https://github.com/Ch-wook/File_Translator/actions/runs/40659202763)는 2건 통과했다. 이전 전체 [40660629846](https://github.com/Ch-wook/File_Translator/actions/runs/40660629846)와 결과 미리보기 반영 [40662966087](https://github.com/Ch-wook/File_Translator/actions/runs/40662966087)에서도 44건을 실행했다. 이 수를 최종 검사 수에 더해 부풀리지 않는다. 별도 이미지 검사 [40663936775](https://github.com/Ch-wook/File_Translator/actions/runs/40663936775)는 PPT 그림/도형 위치·배경, DOCX 그림, XLSX 셀 배경을 통과했다. 게시 단계에서도 최종 빌드의 결과로 동일 검사를 다시 요구한다.

초기 실패는 구분해서 고쳤다. 새 FileProvider 선언이 테스트 Provider와 충돌한 문제는 클래스로 분리했다. JUnit 진입 함수 반환형, WebView 접근성 갱신, 메뉴를 닫으려던 테스트의 뒤로가기가 문서를 닫던 문제는 자동화 오류였다. 앱에서는 결과 미리보기의 원본 세션 대기 문제와 진행/오류 표시 영역을 수정했다. 실패했던 실행을 통과로 계산하지 않았다.

## 설치·배포

[ARM64 휴대폰 APK](https://github.com/Ch-wook/File_Translator/releases/download/v0.8.8-preview/document-studio-0.8.8-preview-arm64-v8a.apk) · [전체 소스 ZIP](https://github.com/Ch-wook/File_Translator/releases/download/v0.8.8-preview/document-studio-0.8.8-preview-source.zip) · [Release·패치·Git bundle·검증 ZIP·SHA256SUMS](https://github.com/Ch-wook/File_Translator/releases/tag/v0.8.8-preview).

사용자가 선택한 별도 프리뷰 **문서 스튜디오 서명·변환**, `kr.documentstudio.convertpreview`, versionCode 18 / 0.8.8-preview. 기존 서명키가 없어 기존 앱을 업데이트하지 않는다. 기존 앱과 복구본을 삭제하지 말고, 기존 앱에서 파일로 저장한 문서를 새 앱에서 연다. Android 10+ 64비트, Office는 Android 11+이다. 개발 서명이며 실제 ARM64 휴대폰·삼성/Gboard·S펜 하드웨어 종합 검증은 미완료이다.

- arm64-v8a: 429,805,261 bytes; SHA-256 `27e57435f7f9b7dec13a3104fc979a10c7fbb2a3278353006a48142c87dacdb9`.
- x86_64: 441,691,751 bytes; SHA-256 `9ab80633d8c76fc66b489857b0a451a17f37703cc554a4cd92e564ee3f413eb9`.
- 공통 개발 서명 인증서 SHA-256: `516118bdfe32327fe4527d735e1450533209271d7b87110d64f5a93c017d3727`.

APK 빌드 커밋과 소스/태그 커밋은 문서·CI 추가로 다를 수 있다. 게시 워크플로가 앱 코드 차이 없음, 소스 ZIP의 모든 추적 파일 일치, APK 바이트 보존, 모든 공개 자산 다운로드·해시와 태그를 검증한다.

## 엔진·라이선스·보안

서버 처리와 유료 SDK 도입은 없다. 앱에 INTERNET 권한이 없으며 서명·문서를 동의 없이 외부에 전송하지 않는다. 서명은 백업 제외 앱 내부 저장소, 공유 권한은 결과 캐시에 한정한다. 실제 S펜 압력·손바닥 차단·특정 기기의 빠른 필기 지연을 검증 완료로 표시하지 않는다.

[Rhwp MIT·고정 소스·폰트](../web/hangul/PROVENANCE.md), [Collabora/LibreOffice MPL-2.0·구성요소 고지·고정 바이너리](../officeruntime/PROVENANCE.md), [기존 라이선스](THIRD_PARTY.md), [HWP→HWPX Apache-2.0](HANGUL_CONVERTER_PROVENANCE.md)를 유지했다. CommonMark는 BSD-2-Clause, PDFBox Android·AndroidX는 Apache-2.0이다. 라이선스 없는 MS 글꼴이나 한컴 유료 SDK 사용 권한을 가정하지 않았다. 인쇄·인쇄 미리보기 UI는 추가하지 않았다.

## 미지원·다음 단계

DOCX↔HWPX, 편집 가능한 PDF→DOCX/PPTX/HWPX, OCR, 일괄/지속 백그라운드 변환, Office의 새 슬라이드·시트·셀 범위/페이지 맞춤·품질 옵션, TXT→DOCX, 직접 벡터 필기, 서명 보관함 고도화는 남아 있다. 암호/보호 문서의 새 변환 비밀번호 흐름은 제공하지 않는다. 복잡한 글꼴·도형·머리말·각주·차트·모든 기존 문서의 무손실 동일성은 미검증이다.

다음 우선순위는 ARM64/S펜 실제 기기 인수, 표 셀 크기 변경 시 서명 위치·크기 보존, 서명 보관함 UI와 직접 필기, 형식별 고급 옵션·OCR·일괄 변환이다. 지원이 검증되지 않은 상호 변환을 정상 버튼으로 표시하지 않는다.

## 실제 수정 파일

- [.github/workflows/conversion-signature.yml](../.github/workflows/conversion-signature.yml)
- [.github/workflows/hangul-mobile.yml](../.github/workflows/hangul-mobile.yml)
- [.github/workflows/mobile-release.yml](../.github/workflows/mobile-release.yml)
- [.github/workflows/pdf-layout-audit.yml](../.github/workflows/pdf-layout-audit.yml)
- [.github/workflows/publish-conversion-preview.yml](../.github/workflows/publish-conversion-preview.yml)
- [README.md](../README.md)
- [app/build.gradle.kts](../app/build.gradle.kts)
- [app/src/androidTest/java/kr/documentstudio/ConversionIntegrationTest.kt](../app/src/androidTest/java/kr/documentstudio/ConversionIntegrationTest.kt)
- [app/src/debug/java/kr/documentstudio/HangulProbeProvider.java](../app/src/debug/java/kr/documentstudio/HangulProbeProvider.java)
- [app/src/debug/java/kr/documentstudio/OfficeProbeProvider.kt](../app/src/debug/java/kr/documentstudio/OfficeProbeProvider.kt)
- [app/src/main/AndroidManifest.xml](../app/src/main/AndroidManifest.xml)
- [app/src/main/java/kr/documentstudio/ConversionActivity.kt](../app/src/main/java/kr/documentstudio/ConversionActivity.kt)
- [app/src/main/java/kr/documentstudio/ConversionFileProvider.kt](../app/src/main/java/kr/documentstudio/ConversionFileProvider.kt)
- [app/src/main/java/kr/documentstudio/ConversionPreviewActivity.kt](../app/src/main/java/kr/documentstudio/ConversionPreviewActivity.kt)
- [app/src/main/java/kr/documentstudio/EditorViewModel.kt](../app/src/main/java/kr/documentstudio/EditorViewModel.kt)
- [app/src/main/java/kr/documentstudio/HangulEditorActivity.kt](../app/src/main/java/kr/documentstudio/HangulEditorActivity.kt)
- [app/src/main/java/kr/documentstudio/data/ConversionFiles.kt](../app/src/main/java/kr/documentstudio/data/ConversionFiles.kt)
- [app/src/main/java/kr/documentstudio/data/PdfEngine.kt](../app/src/main/java/kr/documentstudio/data/PdfEngine.kt)
- [app/src/main/java/kr/documentstudio/data/PortableConversions.kt](../app/src/main/java/kr/documentstudio/data/PortableConversions.kt)
- [app/src/main/java/kr/documentstudio/ui/PdfContinuousReader.kt](../app/src/main/java/kr/documentstudio/ui/PdfContinuousReader.kt)
- [app/src/main/java/kr/documentstudio/ui/StudioApp.kt](../app/src/main/java/kr/documentstudio/ui/StudioApp.kt)
- [app/src/main/java/kr/documentstudio/ui/WorkspaceHome.kt](../app/src/main/java/kr/documentstudio/ui/WorkspaceHome.kt)
- [app/src/main/res/xml/conversion_paths.xml](../app/src/main/res/xml/conversion_paths.xml)
- [docs/SIGNATURE_CONVERSION_RESULTS.md](../docs/SIGNATURE_CONVERSION_RESULTS.md)
- [officeruntime/PROVENANCE.md](../officeruntime/PROVENANCE.md)
- [officeruntime/src/main/assets/dist/studio-policy.js](../officeruntime/src/main/assets/dist/studio-policy.js)
- [officeruntime/src/main/java/org/libreoffice/androidlib/LOActivity.java](../officeruntime/src/main/java/org/libreoffice/androidlib/LOActivity.java)
- [officeruntime/src/main/java/org/libreoffice/androidlib/StudioOfficeActivity.java](../officeruntime/src/main/java/org/libreoffice/androidlib/StudioOfficeActivity.java)
- [scripts/package-mobile-release.py](../scripts/package-mobile-release.py)
- [scripts/prepare-conversion-fixtures.py](../scripts/prepare-conversion-fixtures.py)
- [scripts/prepare-hangul-engine.py](../scripts/prepare-hangul-engine.py)
- [scripts/run-android-mode-tests.sh](../scripts/run-android-mode-tests.sh)
- [scripts/test-mobile-installation.py](../scripts/test-mobile-installation.py)
- [scripts/tests/signature-conversion.cjs](../scripts/tests/signature-conversion.cjs)
- [scripts/verify-signature-conversion.py](../scripts/verify-signature-conversion.py)
- [web/hangul/PROVENANCE.md](../web/hangul/PROVENANCE.md)
- [web/hangul/engine-adapter.ts](../web/hangul/engine-adapter.ts)
- [web/hangul/host.css](../web/hangul/host.css)
- [web/hangul/host.js](../web/hangul/host.js)
- [web/hangul/signature.js](../web/hangul/signature.js)
- [.github/workflows/repackage-088.yml](../.github/workflows/repackage-088.yml)
