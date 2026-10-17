# 문서 스튜디오 0.5.0 — Android 통합 문서 편집 앱

Kotlin·Compose 앱에 로컬 Collabora/LibreOffice 엔진을 통합했습니다. Word·Excel·PowerPoint는 **정밀 편집**에서 실제 페이지·시트·슬라이드를 보고 편집할 수 있습니다. 다른 앱 설치나 문서 서버 없이 실행합니다. 11개 확장자를 열고 지원하는 편집을 원본 형식으로 저장합니다.

이번 버전은 요구사항을 크게 확장했지만 모든 전문 기능의 완성·호환성을 인증한 제품은 아닙니다. 한국어/영어 OCR, 두 문서 편집, 셀 연결 필기, 암호 백업을 추가했습니다. 한글 전문 조판·HWPX→HWP 역변환·PDF/A 인증·정밀 조판 후 필기 재배치와 실기기 검증은 남아 있습니다. [구현 범위](docs/SUPPORT_MATRIX.md)와 [검증 결과](docs/TEST_RESULTS.md)를 확인하세요.

## 주요 기능

- DOCX/DOC·XLSX/XLS·PPTX/PPT 정밀 편집: 앱 내부 별도 프로세스에서 네이티브 엔진 실행. 원본 대신 작업용 복사본을 전달하고 저장·닫기 후 검증된 결과를 불변 베이스로 가져옵니다. 앱의 실행 취소·원본 충돌 검사·필기 보존과 연결됩니다.
- 기본 DOCX 도구: 글자·문단·자간·장평·위아래 첨자, 다단, 표 행열/병합/분할/배경/테두리, 이미지·링크·머리글·바닥글·쪽 번호.
- 기본 XLSX 도구: 범위 복사/붙여넣기·채우기·삭제·정렬, 행열 삽입/삭제와 참조 갱신, 시트·병합·서식, 다른 시트 참조와 날짜·백분율 계산 표시. 무거운 표 계산은 백그라운드에서 처리합니다.
- 기본 PPTX 도구: 슬라이드·개체 이동/크기/회전/순서/삭제, 이미지 삽입, 기본 개체 미리보기·발표·PDF 출력.
- PDF 페이지 조작·병합·추출·축소판·연속 스크롤·북마크, 검색·선택·양식, 본문/이미지 추가, 직접 이미지의 위치·크기·삭제, 원래 폰트가 지원하는 기존 가로 본문 문자열 교체, 표준 Ink 저장.
- HWPX 텍스트·글자/문단 서식·용지·표 생성/행열/병합/서식과 실제 새 문서, HWP 일반 문자 구간 편집. TXT/MD 입력·검색·인코딩·줄 번호·CommonMark/GFM 표 미리보기.
- 공통 필기: 필압·기울기 입력, 여러 펜·형광펜·도형, 획 전체/부분 지우기, 올가미·이동·복사·회전·크기·삭제, 확대·손가락 탐색.
- 탭·즐겨찾기·복구·Undo/Redo, SAF 원본 백업·해시 충돌 검사·저장 검증, ZIP 백업·필기 포함 PDF·인쇄. INTERNET/전체 저장소 접근 권한 없음.

기본 Excel은 구현된 16개 함수의 이름·한국어 설명 검색과 입력 끝 함수 이름 완성을 제공합니다. 외부 열기/공유 요청은 진행 중인 명령이 끝난 뒤 순서대로 처리하며, 가져온 ZIP의 정밀 편집 실패 파일과 원본 백업은 재백업에도 보존합니다.

0.4.0은 분할 편집/SAF 폴더, PDF 선택 문자 주석/가로 보기, 필압/보정/프리셋/레이어/종이, Markdown 체크/문법, HWPX 자간/장평/번호/다단, 셀 연결 필기, 번들 OCR, 암호 백업/임시 정리, XLSX 테두리/조건부 규칙/목록/표를 추가했습니다. [0.4.0 실행 계획](docs/EXECUTION_PLAN_20261017.md).

0.5.0은 **HWP→HWPX 변환**을 추가했습니다. 새 탭에 결과를 열고 변환 전 HWP·현재 편집본·구역별 필기/레이어·점검 보고서를 보존합니다. 문단/셀별 텍스트·표 구조·각주/미주·그림 자원/참조·수식을 대조하고 지원하지 않는 제어는 중단합니다. [변환 사용법과 제한](docs/HANGUL_CONVERSION.md), [후속 실행 계획](docs/EXECUTION_PLAN_20261017_PHASE2.md).

## 설치·빌드

`artifacts/document-studio-release.apk`는 설치용, `document-studio-release.aab`는 배포용 번들입니다. `document-studio-source.zip`, 개발 APK와 SHA-256도 함께 제공합니다. 프로젝트 로컬 출시 키를 재사용하며 Play 업로드는 하지 않았습니다.

Git 저장소에는 소스·리소스·테스트·문서를 포함합니다. APK/AAB와 검사 보고서는 아래 빌드·패키징 명령으로 `artifacts/`에 생성하며 Git에는 포함하지 않습니다. 서명 키·암호 설정·기기별 SDK 경로·다운로드한 엔진 캐시도 제외합니다.

앱은 **Android 10 이상, arm64-v8a/x86_64**를 지원합니다. 로그 보호에 필요한 Android API 때문에 **정밀 편집은 Android 11 이상**에서 사용합니다. Android 10에서는 기본 편집 도구를 사용합니다. 두 ABI의 큰 엔진을 포함하므로 APK와 설치 공간이 기존 버전보다 크게 증가했습니다. debug/release 서명은 다르므로 전환 전 작업을 ZIP으로 백업하세요.

JDK 17, SDK 36, NDK 28.2.13676358, CMake 3.22.1을 사용합니다. 첫 빌드는 고정 SHA-256으로 검증하는 공식 엔진 APK 약 523 MB와 Gradle 의존성을 다운로드합니다. 다운로드 자산은 `.engine-cache`에 저장하고 전달 소스 ZIP에서 제외합니다. [엔진 출처와 수정 소스](officeruntime/PROVENANCE.md).

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
.\gradlew.bat :core:test :app:assembleDebug :app:lintDebug
.\gradlew.bat :app:connectedDebugAndroidTest
.\scripts\verify-editing.ps1
.\scripts\build-release.ps1
.\scripts\verify-release.ps1
.\scripts\package-delivery.ps1
```

## 저장·필기·복구

정밀 편집에서 저장 후 닫으면 앱의 작업 복구본으로 돌아옵니다. 상단 **저장**으로 SAF 파일에 반영합니다. 엔진 강제 종료 시 마지막으로 엔진이 저장한 유효한 작업 파일을 복구합니다. 손상·동시 쓰기가 감지되면 이전 베이스를 유지하고 작업 파일을 보존합니다. 실패한 복구본은 문서 메뉴에서 보관한 뒤 다시 시작할 수 있고 ZIP의 `office-recovery/`에 포함됩니다.

PDF 필기는 표준 Ink 주석에 포함합니다. 나머지는 **앱 내부 별도 필기 데이터**입니다. 기본 XLSX 필기는 셀 주소/크기에 연결해 기본 행열·시트 구조 변경을 추적합니다. 정밀 편집기의 페이지·셀에 자동 부착하거나 Word/HWP 본문 재배치를 추적하지는 않습니다. 원본만 옮기면 별도 필기는 따라가지 않습니다. 앱 삭제·서명 전환·기기 이동 전에 ZIP으로 백업하세요.

기본 편집은 입력 휴지 약 700 ms와 백그라운드 전환에 복구본을 기록합니다. 원본은 명시적 저장 시 바뀝니다. 강제 종료 직전 입력·진행 중 획은 유실될 수 있습니다. 실제 S펜·손바닥·Bluetooth 장치 검증은 아직 수행하지 않았습니다.

## 문서

- [0.5.0 후속 실행 계획](docs/EXECUTION_PLAN_20261017_PHASE2.md)
- [HWP→HWPX 변환](docs/HANGUL_CONVERSION.md)
- [0.4.0 실행 계획](docs/EXECUTION_PLAN_20261017.md)
- [암호 백업](docs/BACKUP_ENCRYPTION.md)
- [남은 전문 기능 인수 조건](docs/PROFESSIONAL_VALIDATION.md)
- [전체 기능 검증·보완 계획과 진행 기록](docs/IMPLEMENTATION_PLAN.md)
- [원문 기능별 인수 확인표](docs/FEATURE_ACCEPTANCE.md)
- [형식별 지원과 남은 요구사항](docs/SUPPORT_MATRIX.md)
- [테스트 결과](docs/TEST_RESULTS.md)
- [빌드·서명·배포](docs/BUILD_AND_RELEASE.md)
- [기술 설계·엔진 비교](docs/ARCHITECTURE.md)
- [라이선스·비용·제약](docs/THIRD_PARTY.md)
- [사용 안내](docs/USER_GUIDE.md)
- [원문 요구사항](docs/REQUIREMENTS.ko.md)
