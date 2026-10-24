# 검증 결과 — 0.6.0

2026-10-24 (Asia/Seoul). 최종 UI 코드 기준 `ab55bd5058167ac0ada14c1e5d3829aa136be92f`. 이후 결과 문서와 화면 캡처만 갱신한다. [0.5.0 결과](TEST_RESULTS_0.5.0.md)는 이전 기록이며 이번 횟수에 합산하지 않는다.

## 전체 검사

최종 휴대폰 자동 게이트 **205개 통과 = JVM 97개 + Android 108개**, 실패/오류/건너뛰기 0이다. 정적 검사는 오류 0개·경고 33개다. 경고는 기존 DefaultLocale 1개, 의존성 9개, UseKtx 22개와 ModifierParameter 1개다. 근거: `artifacts/acceptance-20261024-131952/summary.json`. 검사 때 작업 트리는 깨끗했으며, 실행 시작 후 갱신된 XML만 집계했다.

- `WorkspaceRedesignUiTest` 9개: 홈에서 즉시 생성, 한글 본문/큰 글자/줄 간격/선택 서식/회전과 실제 저장, 한글 표 4셀 입력과 선택 서식, 셀 안 입력/시트 탭, PDF 읽기·메모·그리기, 슬라이드 복제와 저장, 도구 이름 검색/용지 방향 스위치, TXT/필기 전환·회전, 사용자 지정 글꼴·색상·숫자 형식의 실제 파일 보존.
- `OfficeDefaultRoutingTest` 4개: 오피스 세 형식의 정확한 세션 요청과 일회 소비, 설정을 끈 경우와 비오피스 형식, 기존 문서 열기/복구, 실패한 열기에서 이전 문서의 편집기를 띄우지 않음.
- 기존 Android 95개도 재실행했다. 문서 입력·단축키·저장·분할·복구·OCR·암호 백업·HWP 변환·PDF 주석/이미지·필기·스프레드시트와 실제 네이티브 Writer/Calc/Impress 저장 검사를 포함한다.

## 화면과 태블릿

태블릿 2560×1600/240dpi에서 새 UI의 **9개 검사를 추가 통과**했다. 휴대폰 1080×2400/420dpi와 모두 API 35 x86_64 에뮬레이터다. 근거: `artifacts/ui-redesign-060-tablet/summary.json`, 해당 `instrumentation.log`, [실제 화면](UI_SCREENSHOTS.md). 이번 태블릿 실행은 UI 9개에 한정하며 전체 108개를 태블릿에서 다시 실행했다고 집계하지 않는다. 실제 Galaxy/S펜 검증은 아니다.

## 서명 배포

APK/AAB는 0.6.0/code 7이다. 기존 출시 키를 재사용했다. `apksigner`·`jarsigner`, 권한/디버그 provider 제외, 16 KB ZIP 정렬, 36개 ELF LOAD 정렬 및 APK/AAB 네이티브 라이브러리 일치를 통과했다. 근거: `artifacts/test-results/release-verification.json`.

인증서 SHA-256: `18034d8ff98a32c3273f8466c4e506d82e0a5361502ef042a9347e81379164f1`.

APK SHA-256: `033f5b3ad5fe25312606a1f313cee4e5da19d990f57c6d9fcee2ff3bb16d1fc8`.

설치용 APK는 [GitHub Releases](https://github.com/Ch-wook/File_Translator/releases/tag/v0.6.0)의 `document-studio-release.apk`다. AAB는 직접 설치 파일이 아니다. 비공개 서명 키·암호 설정·기기 경로는 소스 ZIP과 Git에 포함하지 않는다.

## 업데이트 설치와 소스 전달

- 0.5.0 출시 APK에서 TXT 문서와 필기를 만들고 실제 SAF Downloads 저장/ZIP 백업 후, 0.6.0을 `install -r`로 업데이트했다. 같은 세션의 본문과 1개 필기 획, 원본 베이스가 그대로 보존됨을 업데이트 전후 ZIP으로 대조했다. 강제 종료 후 복구도 확인했다. 근거: `artifacts/release-ui-060/upgrade-verification.json`과 UI/XML/전후 백업.
- 출시 APK에서 오피스 홈 카드를 눌렀을 때 실제 편집기로 바로 진입하는 화면을 확인했다. [화면 기록](UI_SCREENSHOTS.md).
- 전달 소스 ZIP을 새 디렉터리에 풀어 JVM 97개와 debug APK/test APK 빌드를 새로 수행했다. 비공개 파일 제외와 최종 ZIP의 비문서 빌드 입력 331개 일치도 검사했다. 근거: `artifacts/test-results/source-verification.json`, `artifacts/source-rebuild.log`.

## 남은 범위

이번 개편은 편집 진입과 UI/UX를 개선했다. 전문 한글 조판·HWPX→HWP 역변환·복잡한 한글 제어·PDF/A 인증·정밀 조판 후 필기 재배치·전용 디지털 필기 문자 인식·Galaxy/S펜 및 외부 앱 호환·실제 16 KB 기기 검증은 완료되지 않았다. 전체 원문 요구사항이나 상용 오피스와의 완전한 동등성을 인증하지 않는다. [지원 범위](SUPPORT_MATRIX.md)와 [남은 전문 기능](PROFESSIONAL_VALIDATION.md)을 따른다.
