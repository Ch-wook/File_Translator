# 문서 스튜디오 0.8.7-preview 작업 보고서

작성: 2026-10-28 UTC. 기존 0.8.6 원격 코드 a7c76a8f6f9b5d9e06817ea2c0089dd17a2d00b4에서 이어서 수정했다. 이전 환경의 미푸시 0.8.0 소스·패치가 회수됐다는 의미가 아니다.

## 발견한 문제와 수정

| 문제 | 실제 변경 |
|---|---|
| PowerPoint 슬라이드 목록이 공통 도구에 없어 이동이 불편함 | 실제 엔진 part 목록·현재 선택 표시·이동을 연결했다. 보기 상태에서도 탐색만 허용한다. |
| 슬라이드 삭제를 잘못 누를 위험 | 두 단계 삭제 확인과 마지막 슬라이드 보호를 추가했다. 확인 중 선택이 바뀌면 삭제하지 않는다. |
| 모바일 원래 도구를 접으면서 Calc 셀 입력 완료·취소 접근도 사라짐 | 실제 pending 입력 중에만 작은 완료·취소 줄을 표시하고 native dispatcher의 acceptformula/cancelformula에 연결했다. |
| 숨겨진 시트가 일반 목록에 표시될 수 있음 | native 숨김 정보를 이용해 목록에서 제외하고 마지막 표시 시트는 삭제하지 않는다. |
| 셀 입력 도구가 나타날 때 canvas 높이 갱신 필요 | 표시 변화 시 엔진 레이아웃을 갱신하고 보기 전환 중에는 숨긴다. |

슬라이드 도구는 필요할 때 패널로 연다. 기존 한 줄 상단·하단 구조, 글꼴·문단·표·이미지·찾기·저장 기능과 기존 엔진을 유지했다. Word/한글을 텍스트로 대체하지 않았고, XLSX는 실제 Calc, PPTX는 실제 Impress 명령을 사용한다.

## 실제 수정 파일

- `officeruntime/src/main/assets/dist/studio-policy.js`: 슬라이드 목록·선택·삭제 보호, 숨김 시트 보호, 실제 셀 입력 완료/취소 및 viewport 갱신.
- `scripts/tests/office-workspace.test.cjs`: 읽기 상태 이동의 비변경성, 삭제 확인, 숨김 시트, 실제 dispatcher 연결 회귀.
- `app/src/androidTest/java/kr/documentstudio/NativeOfficeFontTest.kt`: 실제 Calc 입력 취소 시 이전 값 보존, 완료 시 값 반영, 기존 서식·수식·저장/재열기.
- `app/src/androidTest/java/kr/documentstudio/NativeOfficeModeSmokeTest.kt`: 두 슬라이드 사이 실제 이동 및 기존 저장/재열기.
- `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`: 별도 프리뷰 ID·버전·앱 이름.
- `.github/workflows/workspace-api-audit.yml`: 고정 해시 기존 APK에서 native API 조사.
- `.github/workflows/mobile-release.yml`, `scripts/package-mobile-release.py`, `scripts/test-mobile-installation.py`: 버전별 검증·서명·설치·원본 APK/데이터 보호·배포 산출물.
- `.github/workflows/publish-workspace-preview.yml`, `README.md`, 이 문서 및 공개 다운로드 확인서: 검증 빌드 재사용·배포·결과 기록.

## 실행 검증

실제 APK의 `bundle.js`/`cool.html` API 검사: [40607575083](https://github.com/Ch-wook/File_Translator/actions/runs/40607575083).
JavaScript 28건과 Rhwp 회귀: [40611568982](https://github.com/Ch-wook/File_Translator/actions/runs/40611568982).
Android 집중 5건: [40611569270](https://github.com/Ch-wook/File_Translator/actions/runs/40611569270).

최종 빌드: [40612743461](https://github.com/Ch-wook/File_Translator/actions/runs/40612743461), 앱 코드 커밋 `25ef2749c30203fa4b1e2d33cb7f23213a1a1edb`.
- Android 35 x86_64: **39건**, 단위: **148건**(core 127 / officeruntime 2 / app 19), JavaScript: **28건**. 실패·오류·건너뜀 모두 0건.
- 실제 Rhwp 브라우저 시나리오 7개(기존 5개 + 모바일 HWP/HWPX 2개), TypeScript·lint·독립 저장 파서 검사 통과.
- 실제 Android InputConnection·Calc 입력 취소/완료·슬라이드 이동·기존 형식별 회귀를 실행했다. 39건은 이번에 다시 실행한 총수이며 5건 집중 검사를 더해 부풀리지 않는다.
- APK 서명·ABI·16KiB ELF 정렬·testOnly 미설정·자산 일치 검사 통과.
- x86_64 일반 설치(-t 없이), 실행, 기존 0.8.6 APK/개인 데이터 보존·동시 설치, 같은 서명 재설치의 데이터 보존 통과.
- 이름/MIME 조회 회귀: 같은 3건에서 수정 전 3건 실패, 수정 후 3건 통과.

## 형식별 범위와 저장 확인

| 형식 | 유지·검증한 범위 | 이번 추가/수정 |
|---|---|---|
| DOCX | 실제 Writer, 선택 글꼴·크기·굵게·복합 서식, provider 저장/파서 및 엔진 재열기 | 기존 경로 유지·전체 회귀 |
| HWP/HWPX | Rhwp, 한국어 조합/선택, 자간·장평·복합 서식, 표/이미지, 작업 복사본·자동 저장·독립 파서 | 기존 경로 유지·전체 회귀 |
| XLSX | 실제 Calc 셀·서식·15개 수식 결과·시트·provider 저장/파서/엔진 재열기·원본 해시 | 입력 완료/취소, 숨김 시트·마지막 표시 시트 보호 |
| PPTX | 실제 Impress 텍스트/슬라이드, 저장/재열기 | 슬라이드 이동·현재 선택·삭제 보호 |
| PDF | 기존 주석/텍스트/페이지 도구와 저장 회귀 범위 | 기존 경로 유지 |
| Markdown | 문법 편집·토글·미리보기·저장/재열기 | 기존 경로 유지 |
| TXT | 한국어 텍스트·찾기/바꾸기·저장/재열기, 서식은 표시 설정으로 구분 | 기존 경로 유지 |

자동 저장은 작업 복구본이다. 외부 원본 반영의 성공과 구분한다. 검증은 포함된 샘플·시나리오 범위이며 모든 기존 파일의 무손실 보존을 보장하지 않는다. [0.8.6 형식별 상세 결과](EDITOR_COMPLETION_RESULTS.md), [공통 UI](COMMON_MOBILE_UI_RESULTS.md), [지원 제한](SUPPORT_MATRIX.md)을 함께 참고한다.

## 설치·다운로드·서명

사용자가 선택한 별도 프리뷰: **문서 스튜디오 작업 도구**, `kr.documentstudio.workspacepreview`, versionCode 17 / 0.8.7-preview. 기존 서명키가 없어 기존 0.8.6 앱을 업데이트하지 않는다. 기존 앱·비공개 작업 복구본을 삭제하지 않고 공존한다. 문서는 기존 앱에서 파일로 저장한 뒤 새 앱에서 열 수 있다.

[휴대폰 ARM64 APK](https://github.com/Ch-wook/File_Translator/releases/download/v0.8.7-preview/document-studio-0.8.7-preview-arm64-v8a.apk) · [소스 ZIP](https://github.com/Ch-wook/File_Translator/releases/download/v0.8.7-preview/document-studio-0.8.7-preview-source.zip) · [Release·검증 ZIP·Git bundle·패치·SHA256SUMS](https://github.com/Ch-wook/File_Translator/releases/tag/v0.8.7-preview).

- arm64-v8a: 429,636,235 bytes; SHA-256 `4542081d6ec7e0c5fde9a4d6bb377742be6c21214acc4a70e59002137819f93f`.
- x86_64: 441,522,725 bytes; SHA-256 `d13031515ecd1d34b56ffa97cb339ed2f9a379aa837d1bffd1be8a1fc95858f8`.
- 이번 두 APK의 인증서 SHA-256: `b64685af32ba7d85fd7b73241c930d19a91ee4f1d8223b8e4a072c5644b2f6de` (개발 서명).

APK 빌드 커밋과 태그의 소스 커밋은 배포 문서·CI 추가로 다를 수 있다. 게시 워크플로가 앱/엔진 코드 차이 없음, 추적 소스 바이트 일치, APK 해시 보존, 태그와 모든 공개 자산의 다운로드·해시 일치를 검사한다. 이전 0.8.6 배포 자산을 덮어쓰지 않는다.

## 남은 제한과 다음 작업

- 실제 ARM64 휴대폰, 삼성 키보드·Gboard의 종합 검증은 하지 못했다. Android 35 x86_64 에뮬레이터 검증이다.
- 슬라이드 썸네일·순서 변경, 고급 개체/차트·전체 스타일·애니메이션 편집을 이번에 완성하지 않았다.
- PDF 자유 본문 재배치, 모든 복잡한 HWP/Office 요소의 무손실 보존, 요청한 상용 편집기 전체 기능은 부분 지원 또는 미구현이다. 지원하지 않는 기능을 새 정상 버튼으로 추가하지 않았다.
- 숨김 시트·마지막 표시 시트 보호는 정책 회귀로 검사했다. 숨김 시트를 포함한 모든 Excel 변형의 실기기 종합 검증은 남아 있다.
- 다음 순서: ARM64 휴대폰 입력/터치·복합 문서 보존 확인, 슬라이드 탐색 고도화, 형식별 고급 도구를 실제 저장 보존 검증과 함께 확장.
