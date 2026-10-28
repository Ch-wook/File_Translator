# 문서 스튜디오 0.8.8-preview

기존 편집기에 **자필 서명 삽입·보관**과 **기기 내 파일 변환**을 추가한 개발 서명 프리뷰입니다. 메인 화면의 파일 변환 카드와 편집기의 더보기 메뉴에서 시작합니다.

[휴대폰용 ARM64 APK](https://github.com/Ch-wook/File_Translator/releases/download/v0.8.8-preview/document-studio-0.8.8-preview-arm64-v8a.apk) · [소스 ZIP](https://github.com/Ch-wook/File_Translator/releases/download/v0.8.8-preview/document-studio-0.8.8-preview-source.zip) · [작업 보고서](docs/SIGNATURE_CONVERSION_RESULTS.md) · [Release·검증 자료·SHA256SUMS](https://github.com/Ch-wook/File_Translator/releases/tag/v0.8.8-preview)

PPTX·DOCX·XLSX는 기존 Office 엔진의 PDF 필터, HWP/HWPX는 전체 페이지 렌더링을 사용합니다. TXT·Markdown PDF, PDF 페이지 PNG/JPG ZIP·텍스트 추출, Markdown HTML/TXT, TXT HTML/MD 및 기존 HWP→HWPX를 제공합니다. 실제 선택 가능한 경로만 표시하며 원본을 덮어쓰지 않습니다.

한글 문서에서 편집하기 → 더보기 → 서명/필기로 들어가 손가락이나 펜으로 작성합니다. 투명 PNG를 실제 문서 그림으로 삽입하고 기존 그림 도구로 위치·크기를 조절합니다. 서명은 앱 내부에 저장·재사용할 수 있습니다. 인증서 기반 전자서명은 아닙니다.

한글 PDF는 페이지 이미지 방식이며 텍스트 선택·검색·링크가 보존되지 않습니다. Markdown PDF는 기본 서식 범위이며 복잡한 표·원격 이미지 배치는 미지원입니다. OCR, 편집 가능한 PDF→Word, DOCX↔HWPX, 일괄 변환과 문서 위 직접 벡터 필기는 아직 지원하지 않습니다.

검증: Android 35 x86_64 **44건**, 단위 **148건**, JavaScript **28건**, 브라우저 실제 엔진 9개 시나리오와 독립 파일 검사 통과. 설치·기존 앱/개인 데이터 보존·서명·16KiB 정렬도 확인했습니다. 익명 API 403으로 중단된 마지막 패키징은 보존된 동일 APK로 복구했으며 재빌드하지 않았습니다. 실제 ARM64 휴대폰·S펜 하드웨어 검증은 미완료입니다.

기존 서명키가 없어 사용자 선택에 따라 **문서 스튜디오 서명·변환**(`kr.documentstudio.convertpreview`)을 별도 설치합니다. 기존 앱과 비공개 작업 복구본을 삭제하지 마세요. 기존 앱에서 파일로 저장한 문서를 새 앱에서 열 수 있습니다. Android 10+ 64비트이며 Office 변환은 Android 11+입니다. 문서·서명은 외부 서버에 업로드하지 않습니다.

<details>
<summary>0.8.7 및 이전 구현·배포 기록</summary>

# 문서 스튜디오 0.8.7-preview

기존 7종 편집기를 유지하면서 **PowerPoint 슬라이드 목록·이동·삭제 보호**, **Excel 셀 입력 완료·취소와 숨김 시트 보호**를 보완했습니다.

[휴대폰용 ARM64 APK](https://github.com/Ch-wook/File_Translator/releases/download/v0.8.7-preview/document-studio-0.8.7-preview-arm64-v8a.apk) · [소스 ZIP](https://github.com/Ch-wook/File_Translator/releases/download/v0.8.7-preview/document-studio-0.8.7-preview-source.zip) · [작업 보고서](docs/WORKSPACE_TOOLS_RESULTS.md) · [Release·검증 자료·SHA256SUMS](https://github.com/Ch-wook/File_Translator/releases/tag/v0.8.7-preview)

휴대폰에서는 ARM64 APK(약 429.6 MB)를 설치합니다. 기존 서명키가 없어 사용자 선택대로 **문서 스튜디오 작업 도구**(`kr.documentstudio.workspacepreview`)를 별도 설치합니다. 기존 0.8.6 앱·작업 복구본을 삭제하지 마세요. 기존 앱에서 파일로 저장한 문서를 새 앱에서 열 수 있습니다.

Android 35 x86_64 **39건**, 단위 **148건**, JavaScript **28건**, 실제 엔진 브라우저 7개 시나리오와 저장/재열기 파서 검사를 통과했습니다. 설치·기존 앱/데이터 보존·재설치 및 APK 서명·정렬·자산도 확인했습니다. 실제 ARM64 휴대폰·삼성 키보드·Gboard 검증과 고급 편집 기능 일부는 남아 있습니다. 자동 저장은 작업 복구본이며 원본 저장 성공과 구분합니다.

[실제 변경 파일·형식별 범위·남은 제한](docs/WORKSPACE_TOOLS_RESULTS.md) · [이전 글꼴·7종 편집 보완](docs/EDITOR_COMPLETION_RESULTS.md)

[공개 다운로드·해시·이전 배포 보존 확인서](docs/releases/0.8.7-preview-download-verification.json) — 10개 자산 확인 완료.

<details>
<summary>0.8.6 및 이전 구현·배포 기록</summary>

# 문서 스튜디오 0.8.6-preview

기존 Word·Excel·PowerPoint·한글·PDF·Markdown·TXT 편집기에 실제 글꼴과 형식별 도구, 서식·선택·저장 처리를 보완한 개발 서명 프리뷰입니다.

[휴대폰용 ARM64 APK](https://github.com/Ch-wook/File_Translator/releases/download/v0.8.6-preview/document-studio-0.8.6-preview-arm64-v8a.apk) · [소스 ZIP](https://github.com/Ch-wook/File_Translator/releases/download/v0.8.6-preview/document-studio-0.8.6-preview-source.zip) · [작업 보고서 다운로드](https://github.com/Ch-wook/File_Translator/releases/download/v0.8.6-preview/document-studio-0.8.6-preview-work-report.md) · [Release·검증 자료·SHA256SUMS](https://github.com/Ch-wook/File_Translator/releases/tag/v0.8.6-preview)

나눔고딕·나눔명조·고운돋움·고운바탕·Lato·Cousine 6개 패밀리의 실제 파일과 라이선스를 추가했습니다. 공통 글꼴 검색·즐겨찾기·미리보기, 파일별 하단 도구, Word 선택 서식, Excel 수식·셀·시트, Markdown 문법 토글·미리보기 복귀와 TXT 찾기·바꾸기를 보완합니다. [변경 파일·검증 결과·부분 지원·미지원](docs/EDITOR_COMPLETION_RESULTS.md)을 확인하세요.

휴대폰에서는 ARM64 APK를 다운로드해 설치합니다. 기존 서명키가 없어 사용자 선택에 따라 **문서 스튜디오 편집 개선**(kr.documentstudio.editorspreview)을 별도 설치합니다. 기존 앱과 비공개 작업 복구본을 삭제하지 마세요. 기존 앱에서 문서를 다른 이름으로 저장한 뒤 새 앱의 파일 열기에서 선택할 수 있습니다.

검증: Android 35 x86_64 39건, 단위 148건, JavaScript 25건과 실제 엔진/독립 파서 검사를 통과했습니다. APK 서명·ABI·16KiB 정렬·자산과 기존 앱 보존 설치도 확인했습니다.

고급 차트·전체 개체/스타일 편집·PDF 자유 재배치·모든 기존 파일의 무손실 호환성을 완성한 버전은 아닙니다. 실제 ARM64 휴대폰·삼성/Gboard 검증은 미완료입니다. 자동 저장은 작업 복구본이며 외부 원본의 저장 성공과 구분합니다.

[공개 다운로드·해시·기존 배포 보존 확인서](docs/releases/0.8.6-preview-download-verification.json) — 10개 자산 확인 완료.

<details>
<summary>0.8.5 이전 구현·배포 기록</summary>

# 문서 스튜디오 0.8.5-preview

Android에서 문서를 원래 형식의 엔진으로 열어 보고, **편집하기**를 선택하면 문서 화면에서 직접 수정하는 앱입니다. 기존 파일 열기·최근 작업·외부 앱 열기는 기본적으로 보기로 시작합니다.

이 버전은 확인 가능한 원격 `2bfe225`에서 **재구현**했습니다. 이전 환경의 미푸시 0.8.0 소스·패치·APK는 회수하지 못했습니다. [출처와 감사](docs/ORIGINAL_VIEW_AUDIT.md), [검증 자료 안내](docs/ORIGINAL_VIEW_RESULTS.md)를 확인하세요.

## 문서 열기 오류 수정

배포용 HWP를 열지 못하던 문제와 파일 제공자의 이름·MIME 조회 실패로 문서 가져오기가 중단되는 문제를 수정합니다. 배포용 HWP는 원래 제한을 유지한 읽기 전용으로 표시하며, 일반 HWP/HWPX의 명시적 편집과 원본 보호는 유지합니다. [원인·수정·검증](docs/DOCUMENT_OPEN_FIX.md).

## 모바일 공통 편집 UI

상단과 하단을 각각 한 줄로 줄이고, 상세 설정은 필요할 때 여는 패널로 옮겼습니다. HWP/HWPX·Office는 같은 도구·글꼴 컴포넌트를, PDF·TXT·Markdown은 같은 네이티브 컴포넌트를 사용합니다. HWP/HWPX 눈금자는 기본 숨김이며, 글꼴 검색·현재/최근 글꼴·미리보기와 선택 범위 보존을 개선했습니다. Office의 서식 인수 유실과 이전 검색 API 연결, 저장 알림의 하단 도구 가림도 수정했습니다. [실제 수정 파일·형식별 지원·검증·제한](docs/COMMON_MOBILE_UI_RESULTS.md).

## 작업 문서

[작업 보고서·인수인계](docs/WORK_REPORT_0.8.5-preview.md)에 수정 파일, 실패 원인, 실제 검증·미검증, 배포 커밋·해시와 후속 작업을 정리했습니다.

[보고서 Markdown 다운로드](https://github.com/Ch-wook/File_Translator/releases/download/v0.8.5-preview/document-studio-0.8.5-preview-work-report-20261027-r1.md) · [관련 문서·화면 ZIP](https://github.com/Ch-wook/File_Translator/releases/download/v0.8.5-preview/document-studio-0.8.5-preview-work-docs-20261027-r1.zip) · [문서 배포 확인서](docs/releases/0.8.5-preview-work-report-20261027-r1-download-verification.json). 추가 문서 3개의 공개 다운로드·해시와 기존 앱 배포 자산 9개·태그 보존을 확인했습니다.

## 다운로드

[휴대폰용 ARM64 APK](https://github.com/Ch-wook/File_Translator/releases/download/v0.8.5-preview/document-studio-0.8.5-preview-arm64-v8a.apk) · [소스 ZIP](https://github.com/Ch-wook/File_Translator/releases/download/v0.8.5-preview/document-studio-0.8.5-preview-source.zip) · [Release·해시·검증 자료](https://github.com/Ch-wook/File_Translator/releases/tag/v0.8.5-preview)

0.8.5-preview APK·소스·검증 자료 9개를 게시했고, [공개 다운로드·크기·SHA-256 확인](docs/releases/0.8.5-preview-download-verification.json)을 완료했습니다. ARM64 APK는 약 404.7 MB입니다. 휴대폰은 ARM64용, x86_64 APK는 해당 아키텍처용입니다. 기존 서명키가 없어 **문서 스튜디오 모바일 UI**(`kr.documentstudio.compactpreview`) 별도 프리뷰로 제공합니다. 기존 앱·데이터를 지울 필요가 없습니다. 작업 중인 문서는 기존 앱에서 파일로 저장한 뒤 새 프리뷰의 파일 열기에서 선택하세요. [기존 설치 실패 분석](docs/INSTALLATION_FIX.md).

## 휴대폰에서 사용하기

1. **파일 열기** 또는 최근 작업에서 문서를 선택합니다. 먼저 원본 문서를 봅니다.
2. **편집하기**를 누르고 본문·표 셀·실제 셀·슬라이드 개체 등 해당 형식의 대상을 선택합니다.
3. 필요하면 **보기**로 돌아갑니다. 읽기 상태에서는 입력·붙여넣기·개체 변경과 undo/redo를 차단합니다.
4. 편집 화면의 **저장 후 닫기** 또는 작업 화면의 **저장**으로 원본 반영을 요청합니다. 새 파일은 시스템 저장 위치를 선택합니다.
5. 저장한 파일을 다시 열어 변경 내용과 중요한 표·그림·수식·페이지를 확인하세요. 다른 이름 저장과 문서·필기 ZIP 백업도 사용할 수 있습니다.

한글 편집 화면 아래의 글자·문단·표(개체 선택 시 그림)·삽입·더보기에서 도구를 엽니다. 목록·찾기·스타일·쪽·클립보드 등의 기존 도구는 더보기에 있습니다. 선택한 글자에 글꼴·크기·색·자간·장평을 함께 적용하고, 선택 핸들로 범위를 조절합니다. 표 행·열 추가/삭제와 셀 병합/나누기, 갤러리 그림 삽입을 실제 엔진에 연결했습니다. 입력이 멈추면 약 1.2초 후 작업 복사본에 자동 저장하며, 원본 반영은 저장 후 닫기를 선택합니다.

엔진은 앱 전용 작업 복사본을 사용합니다. 읽기만 하고 닫으면 문서를 다시 저장하지 않으며, 승인된 편집 결과만 가져옵니다. 원본 저장에는 기존 충돌 검사·백업·실패 복구를 유지합니다. **별도 필기**는 사용자가 직접 선택하는 기능입니다.

## 형식과 엔진

| 형식 | 화면/엔진 |
|---|---|
| HWP/HWPX | Rhwp 0.8.7의 페이지 캔버스·본문·표·그림 도구 |
| DOC/DOCX | 로컬 Collabora/LibreOffice Writer |
| XLS/XLSX | Calc의 실제 셀·수식 입력줄·시트 |
| PPT/PPTX | Impress의 슬라이드·텍스트 상자·그림/도형 |
| PDF | 원본 PDF 페이지, 주석·필기와 지원 범위의 본문/이미지 편집 |
| TXT/MD | 본래 텍스트 형식의 보기/편집과 Markdown 미리보기 |

Android 10 이상, arm64-v8a/x86_64를 지원합니다. Office 엔진은 Android 11 이상이 필요합니다. 문서 엔진은 APK에 포함되며 앱에 INTERNET 권한이 없습니다.

**검증용 개발 서명 프리뷰입니다.** 실제 Android 휴대폰 종합 인수와 모든 형식의 고급 문서 보존 검증은 남아 있습니다. 전용 글꼴·복잡한 한글 컨트롤·Office 고급 요소·PDF 임의 콘텐츠를 상용 프로그램과 동일하게 처리한다고 보장하지 않습니다. [형식별 지원과 제한](docs/SUPPORT_MATRIX.md)을 확인하세요.

APK는 [GitHub Releases](https://github.com/Ch-wook/File_Translator/releases)에 실제 첨부된 파일로 제공합니다. 개발 서명이 기존 설치와 다르면 바로 업데이트되지 않으므로 기존 작업을 보존한 상태에서 확인하세요.

2026-10-27 [최종 검증 실행](https://github.com/Ch-wook/File_Translator/actions/runs/40494582093): Android 35 x86_64 **27건**, 단위 **143건**, JavaScript **20건** 모두 실패·오류·건너뜀 0건. 실제 엔진 브라우저 7개 시나리오, HWP/HWPX 독립 저장 파서, 설치·기존 앱/데이터 보존과 저장 후 재열기를 통과했습니다. [수정 파일·형식별 범위·결과](docs/COMMON_MOBILE_UI_RESULTS.md). 실제 ARM64 휴대폰·삼성 키보드·Gboard 검증은 미완료입니다.

## 빌드와 검증

JDK 17, Node.js 24, Python 3.11 이상, SDK 36, Build Tools 35.0.0, NDK 28.2.13676358, CMake 3.22.1이 필요합니다. 최초 빌드에 고정 해시의 엔진 다운로드와 충분한 공간이 필요합니다.

```sh
python3 scripts/prepare-open-fixtures.py
./gradlew :app:help :core:test :core:prepareVisualFixtures --configure-on-demand --max-workers=2 --console=plain
python3 scripts/prepare-hangul-engine.py
./gradlew :officeruntime:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --max-workers=2 --console=plain
npm ci --prefix scripts/tests
node --test scripts/tests/*.test.cjs
(cd scripts/tests && npx playwright install chromium && npm run test:hangul)
node scripts/tests/hangul-mobile.cjs
(cd .engine-cache/hangul/source/rhwp-studio && npx tsc --noEmit)
./gradlew :core:verifyVisualRoundTrip :core:verifyMobileRoundTrip --max-workers=2 --console=plain
```

모바일 편집은 GitHub Actions의 `Build and verify mobile editing APKs`에서 실제 엔진 브라우저 7개 시나리오, 독립 HWP/HWPX 저장 파서, Android 35 x86_64에서 기존 P0 및 실제 InputConnection 편집·외부 열기·공통 TXT/MD/PDF 화면·Writer 부분 서식 저장을 포함한 총 27건의 통과를 요구합니다. 실제 휴대폰·삼성 키보드·Gboard 검증은 남아 있습니다. `Android preview verification`은 이전 경로입니다. ABI별 APK·전체 추적 소스 ZIP·패치·Git bundle·검증 ZIP·SHA256SUMS를 생성합니다. 실제 실행 결과는 해당 커밋의 Actions 로그와 검증 ZIP을 기준으로 확인합니다.

[사용 안내](docs/USER_GUIDE.md) · [한글 엔진 출처](web/hangul/PROVENANCE.md) · [Office 엔진 출처](officeruntime/PROVENANCE.md) · [오픈소스 고지](docs/THIRD_PARTY.md)

0.7.0 이하 문서와 테스트 수는 과거 기록입니다. 이번 버전의 실기기 검증 결과로 합산하지 않습니다.

</details>

</details>

</details>
