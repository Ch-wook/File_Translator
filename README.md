# 문서 스튜디오 0.8.3-preview

Android에서 문서를 원래 형식의 엔진으로 열어 보고, **편집하기**를 선택하면 문서 화면에서 직접 수정하는 앱입니다. 기존 파일 열기·최근 작업·외부 앱 열기는 기본적으로 보기로 시작합니다.

이 버전은 확인 가능한 원격 `2bfe225`에서 **재구현**했습니다. 이전 환경의 미푸시 0.8.0 소스·패치·APK는 회수하지 못했습니다. [출처와 감사](docs/ORIGINAL_VIEW_AUDIT.md), [검증 자료 안내](docs/ORIGINAL_VIEW_RESULTS.md)를 확인하세요.

## 다운로드

[Android APK](https://github.com/Ch-wook/File_Translator/releases/download/v0.8.3-preview/document-studio-0.8.3-preview-arm64-v8a.apk) · [소스 ZIP](https://github.com/Ch-wook/File_Translator/releases/download/v0.8.3-preview/document-studio-0.8.3-preview-source.zip) · [해시·패치·Git 이력·검증 자료](https://github.com/Ch-wook/File_Translator/releases/tag/v0.8.3-preview)

휴대폰에는 ARM64 APK를 설치하세요. x86_64 APK는 해당 아키텍처용입니다. 새 앱은 **문서 스튜디오 모바일**로 표시되며 기존 0.8.2와 함께 설치됩니다. [설치 실패 분석과 수정](docs/INSTALLATION_FIX.md)을 확인하세요. 개발 서명 프리뷰입니다. [모바일 한글 편집 기능·부분 지원·미구현 및 실제 검증](docs/HANGUL_MOBILE_EDITING.md)을 확인하세요.

## 휴대폰에서 사용하기

1. **파일 열기** 또는 최근 작업에서 문서를 선택합니다. 먼저 원본 문서를 봅니다.
2. **편집하기**를 누르고 본문·표 셀·실제 셀·슬라이드 개체 등 해당 형식의 대상을 선택합니다.
3. 필요하면 **보기**로 돌아갑니다. 읽기 상태에서는 입력·붙여넣기·개체 변경과 undo/redo를 차단합니다.
4. 편집 화면의 **저장 후 닫기** 또는 작업 화면의 **저장**으로 원본 반영을 요청합니다. 새 파일은 시스템 저장 위치를 선택합니다.
5. 저장한 파일을 다시 열어 변경 내용과 중요한 표·그림·수식·페이지를 확인하세요. 다른 이름 저장과 문서·필기 ZIP 백업도 사용할 수 있습니다.

한글 편집 화면에서 글자·문단·목록·표·그림·찾기 도구를 사용할 수 있습니다. 선택한 글자에 글꼴·크기·색·자간·장평을 함께 적용하고, 선택 핸들로 범위를 조절합니다. 표 행·열 추가/삭제와 셀 병합/나누기, 갤러리 그림 삽입을 실제 엔진에 연결했습니다. 입력이 멈추면 약 1.2초 후 작업 복사본에 자동 저장하며, 원본 반영은 저장 후 닫기를 선택합니다.

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

## 빌드와 검증

JDK 17, Node.js 24, Python 3.11 이상, SDK 36, Build Tools 35.0.0, NDK 28.2.13676358, CMake 3.22.1이 필요합니다. 최초 빌드에 고정 해시의 엔진 다운로드와 충분한 공간이 필요합니다.

```sh
./gradlew :core:test :core:prepareVisualFixtures :officeruntime:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --max-workers=2 --console=plain
npm ci --prefix scripts/tests
node --test scripts/tests/*.test.cjs
(cd scripts/tests && npx playwright install chromium && npm run test:hangul)
node scripts/tests/hangul-mobile.cjs
(cd .engine-cache/hangul/source/rhwp-studio && npx tsc --noEmit)
./gradlew :core:verifyVisualRoundTrip :core:verifyMobileRoundTrip --max-workers=2 --console=plain
```

모바일 편집은 GitHub Actions의 `Build and verify mobile editing APKs`에서 실제 엔진 브라우저 7개 시나리오, 독립 HWP/HWPX 저장 파서, Android 35 x86_64에서 기존 P0 및 실제 InputConnection 편집 테스트 총 21건을 검증합니다. 실제 휴대폰·삼성 키보드·Gboard 검증은 남아 있습니다. `Android preview verification`은 이전 경로입니다. ABI별 APK·전체 추적 소스 ZIP·패치·Git bundle·검증 ZIP·SHA256SUMS를 생성합니다. 실제 실행 결과는 해당 커밋의 Actions 로그와 검증 ZIP을 기준으로 확인합니다.

[사용 안내](docs/USER_GUIDE.md) · [한글 엔진 출처](web/hangul/PROVENANCE.md) · [Office 엔진 출처](officeruntime/PROVENANCE.md) · [오픈소스 고지](docs/THIRD_PARTY.md)

0.7.0 이하 문서와 테스트 수는 과거 기록입니다. 이번 버전의 실기기 검증 결과로 합산하지 않습니다.
