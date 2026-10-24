# 문서 스튜디오 0.7.0-preview — Android 원본 문서 편집

Android 앱 안에서 파일 형식별 엔진으로 문서를 엽니다. HWP/HWPX는 Rhwp의 실제 페이지 캔버스, Word·Excel·PowerPoint는 로컬 Collabora/LibreOffice의 Writer·Calc·Impress를 사용합니다. PDF는 원본 페이지의 내용 스트림에 연결된 글자와 이미지 출현을 선택해 수정합니다. 바이너리 문서를 추출 텍스트 입력창으로 편집하던 화면과 간이 스프레드시트 편집 화면은 제거했습니다.

**검증용 프리뷰입니다.** 한컴/MS Office/Acrobat과 동일한 조판이나 모든 고급 요소의 무손실 저장을 보장하지 않습니다. [이번 변경·검증·제한](docs/ANDROID_DOCUMENT_ENGINES_20261024.md), [형식별 지원](docs/SUPPORT_MATRIX.md)을 먼저 확인하세요. 이전 버전의 테스트 결과를 이번 버전의 실기기 검증으로 표시하지 않습니다.

## Android에서 사용하기

1. 시스템 파일 선택기나 다른 앱의 공유/열기로 파일을 선택합니다. 새 문서와 최근 작업도 같은 형식별 엔진으로 연결됩니다.
2. 한글/Word는 문서 본문이나 표 셀을 누르고 입력합니다. Excel은 실제 셀과 수식 입력줄을, PowerPoint는 슬라이드 목록과 개체 선택 핸들을 사용합니다.
3. **저장 후 닫기**는 엔진의 작업 파일 저장과 앱의 원본 저장을 이어서 수행합니다. 새 문서는 시스템 저장 위치 선택기가 나타납니다. 작업 화면으로만 돌아오면 복구본을 유지하고 앱의 저장 버튼으로 원본에 반영합니다.
4. PDF는 **편집** 탭에서 글자·이미지를 누릅니다. 지원되는 기존 본문은 그 위치에서 키보드로 입력하고, 이미지는 드래그·오른쪽 아래 핸들로 이동/크기를 조정합니다. 글꼴이 지원하지 않는 문자는 오류로 알립니다.
5. 기존 손필기는 **그리기**에서 사용합니다. PDF 외 필기는 원본 본문과 별도 데이터이므로 기기 이동 전 ZIP 백업이 필요합니다.

휴대폰은 접이식 도구와 문서 확대/탐색, 태블릿은 넓은 리본을 사용합니다. Android 파일 선택·이미지 선택·저장과 IME 키보드를 연결했습니다. 하드웨어 마우스/키보드·S펜·접근성·다중 창의 실제 기기 인수는 아직 필요합니다.

## 빌드

Android 10(API 29) 이상, arm64-v8a/x86_64. Word·Excel·PowerPoint 엔진은 Android 11(API 30) 이상에서 실행합니다. Android 10에서는 지원 불가를 알리며 추출 텍스트 편집기로 대신 열지 않습니다. HWP/HWPX·PDF·TXT·MD는 API 29부터 사용합니다. 최신 Android System WebView를 권장합니다.

JDK 17, SDK 36, NDK 28.2.13676358, CMake 3.22.1, Python 3.11 이상, Node.js 24/npm이 필요합니다. Gradle `preBuild`가 버전/해시가 고정된 오피스 바이너리와 Rhwp 소스·WASM·폰트를 `.engine-cache`에 준비합니다. 첫 빌드에는 네트워크와 충분한 공간이 필요합니다. 실행 중 문서 서버나 다른 앱 설치는 필요하지 않으며 APK에는 INTERNET/전체 저장소 접근 권한이 없습니다.

```sh
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
./gradlew :core:prepareVisualFixtures
cd scripts/tests && npm ci && npx playwright install chromium && npm run test:hangul && npm test
cd ../.. && ./gradlew :core:verifyVisualRoundTrip
./gradlew :app:connectedDebugAndroidTest
```

APK는 `app/build/outputs/apk/debug/app-debug.apk`에 생성합니다. 두 ABI의 네이티브 오피스와 오프라인 한글 엔진을 포함하므로 크고 설치 공간도 필요합니다. 이 프리뷰는 개발 서명 APK이며 기존 출시 서명과 다를 수 있습니다. 전환 전에 작업을 ZIP으로 백업하세요. 출시 키는 저장소에 포함하지 않습니다.

## 저장과 복구

엔진은 앱 전용 작업 복사본만 수정합니다. 형식/파싱·수정 충돌을 검사하고 불변 베이스로 가져온 뒤, 원본 저장 시 현재 파일 해시 비교·백업·쓰기 후 해시 검증·실패 시 복원을 수행합니다. 한글은 보존 불가 보고가 있으면 저장을 중단하고, 엔진이 모르는 누락된 패키지 파트/OLE 스트림도 보충합니다. 이것은 문서 전체의 완전한 무손실 보증은 아닙니다. 고급 문서는 원본을 별도로 보관하고 해당 상용 프로그램에서도 저장 결과를 확인해야 합니다.

한글 복구본은 입력 휴지 약 700ms 후 내보내며 앱 강제 종료 직전의 입력은 남지 않을 수 있습니다. 저장 실패 시 최근 변경을 버리고 마지막 유효 복구본으로 돌아가는 확인 동작을 제공합니다. 원본 문서의 렌더링 위에 별도 공통 필기를 자동 부착하는 기능은 제공하지 않습니다.

- [사용 안내](docs/USER_GUIDE.md)
- [설계·형식별 기능·테스트 결과·미지원](docs/ANDROID_DOCUMENT_ENGINES_20261024.md)
- [한글 엔진 출처와 빌드](web/hangul/PROVENANCE.md)
- [Collabora 엔진 출처와 수정 소스](officeruntime/PROVENANCE.md)
- [오픈소스 고지](docs/THIRD_PARTY.md)
- [GitHub 릴리스](https://github.com/Ch-wook/File_Translator/releases)

`docs`의 0.6.0 이하 기본 편집기 안내와 과거 테스트 기록은 변경 이력입니다. 현재 동작은 위 프리뷰 문서와 지원표가 우선합니다.
