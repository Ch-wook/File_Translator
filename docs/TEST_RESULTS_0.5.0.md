# 검증 결과 — 0.5.0

2026-10-18 (Asia/Seoul). 코드 기준 `3675b50545ef0ced001b73fc3f0bf23d5746c8e9`. 이후 결과 문서만 갱신한다. 0.4.0 결과는 [별도 기록](TEST_RESULTS_0.4.0.md)이며 이번 실행 수에 합산하지 않는다.

최종 휴대폰 자동 게이트는 **192개 통과 = JVM 97개 + Android 95개**, 실패/오류/건너뛰기 0이다. 린트는 오류 0개, 경고 31개다. 경고 분류: {'DefaultLocale': 1, 'GradleDependency': 5, 'NewerVersionAvailable': 4, 'UseKtx': 21}. 자동 게이트 근거는 `artifacts/acceptance-20261018-002458/summary.json`이며 실행 시작 이후 갱신된 XML만 집계했다. 코드 저장소는 검사 시점에 깨끗했다.

## 새 기능 검증

- HangulConversionTest 9개: 한글/유효 이모지·독립 hwpxlib 재열기, 하이픈 앞 텍스트/탭/줄바꿈/특수 공백, 65,535자 이상 문단, 표 2×2의 셀 범위별 내용·머리말·꼬리말·각주·미주, 셀끼리 텍스트 교환 탐지, 그림 바이트/참조·수식 및 오른쪽 내부 여백, 49 MiB 압축 자원 차단, 선택 메타데이터 누락, 잘못된 입력/미지원 제어/동일 경로와 기존 대상 보존.
- HangulConversionIntegrationTest 4개: Android 실제 변환·편집 내용 반영·원본 보존·구역별 필기/레이어/잠금/숨김/종이, 저장/재열기/강제 재구성, ZIP/암호 백업 및 반복 재백업, 비활성 문서의 새 탭 변환과 닫힌 콜백, 미지원 제어 실패 시 인덱스/원본 보존, 표/머리말/각주 및 변조한 보존 자료의 보고서 거부.
- HangulConversionUiTest 1개: 실제 변환 확인창 취소/실행, 새 탭, 점검 결과 표시, 변환 결과만 편집하고 Undo, 기존 HWP 입력 보존.
- 기존 178개 회귀도 모두 재실행했다. OCR·암호 백업·분할 편집·PDF 문자 주석/이미지·필기/레이어·Markdown·시트·HWPX 및 네이티브 Writer/Calc/Impress 모델 검사를 포함한다.

## 화면·배포·소스

- API 35 x86_64 읽기 전용 격리 에뮬레이터의 휴대폰 창에서 위 192개를 실행했다. 태블릿 창 2560×1600 px/240 dpi에서는 **새 변환 관련 Android 5개를 추가로 통과**했다. 근거: `artifacts/tablet-regression-050/summary.json`. 기존 0.4.0 태블릿 90개 결과와 별개이며 이번 태블릿 검사는 변환 경로에 한정한다. 실제 Galaxy/S펜 검사는 아니다.
- APK/AAB는 0.5.0/code 6이며 기존 0.4.0과 같은 인증서로 서명했다. apksigner/jarsigner·권한/디버그 provider 제외·16 KB ZIP 정렬·36개 ELF LOAD 정렬과 APK/AAB 라이브러리 일치를 검사했다. `artifacts/test-results/release-verification.json`.
- 출시 APK를 실제 설치해 HWP→HWPX 새 탭 변환, 보고서 표시, SAF Downloads 저장, 미저장 편집 후 강제 종료/재실행 복구를 확인했다. `artifacts/release-smoke-050/summary.json`과 해당 UI/XML/저장 파일 근거를 보존했다.
- 배포 소스 ZIP을 새 D: 경로에 풀고 고정 엔진 캐시/SDK 경로만 제공하여 JVM 97개 및 debug/test APK를 새로 빌드했다. 이후 결과 문서만 갱신했으며, 마지막 ZIP의 모든 비문서 빌드 입력은 새 경로의 검사 입력과 바이트 단위로 일치한다. 비공개 키/설정/캐시/생성 파일은 제외했다. `artifacts/test-results/source-verification.json`.
- 배포 파일 네 개의 SHA-256은 `artifacts/SHA256SUMS.txt`에서 확인한다. 서명 키를 새로 만들거나 Play에 업로드하지 않았다.

## 한계와 미완료 항목

변환 검사는 구조/텍스트/자원 비교이며 한컴 조판·모든 서식 속성·페이지 시각 일치 인증이 아니다. HWPX→HWP 역변환, 전문 한글 제어 편집/조판, PDF/A 전체 인증, 본문 재조판에 따른 필기 재배치, 궤적 전용 손글씨 인식과 실제 Galaxy/S펜/16 KB 기기·외부 제공자 전체 검사는 남아 있다. [변환 제한](HANGUL_CONVERSION.md), [요구사항 인수표](FEATURE_ACCEPTANCE.md)의 미지원/미검증을 전체 완료로 집계하지 않는다.

## 실행된 검사 목록

| 그룹 | 검사 스위트 | 수 | 실패/오류/건너뛰기 |
|---|---|---:|---|
| jvm | kr.documentstudio.core.CoreTest | 15 | 0/0/0 |
| jvm | kr.documentstudio.core.EncryptedBackupTest | 3 | 0/0/0 |
| jvm | kr.documentstudio.core.FeatureTest | 7 | 0/0/0 |
| jvm | kr.documentstudio.core.FormulaCatalogTest | 3 | 0/0/0 |
| jvm | kr.documentstudio.core.FormulaLimitsTest | 3 | 0/0/0 |
| jvm | kr.documentstudio.core.HangulConversionTest | 9 | 0/0/0 |
| jvm | kr.documentstudio.core.HangulFormattingTest | 2 | 0/0/0 |
| jvm | kr.documentstudio.core.HangulTablesTest | 5 | 0/0/0 |
| jvm | kr.documentstudio.core.InkAdvancedTest | 3 | 0/0/0 |
| jvm | kr.documentstudio.core.InkAnchorsTest | 3 | 0/0/0 |
| jvm | kr.documentstudio.core.InkErasureTest | 8 | 0/0/0 |
| jvm | kr.documentstudio.core.InkTest | 2 | 0/0/0 |
| jvm | kr.documentstudio.core.LegacyTest | 4 | 0/0/0 |
| jvm | kr.documentstudio.core.MarkdownEditingTest | 1 | 0/0/0 |
| jvm | kr.documentstudio.core.OfficeCompatibilityTest | 4 | 0/0/0 |
| jvm | kr.documentstudio.core.OfficeObjectsTest | 6 | 0/0/0 |
| jvm | kr.documentstudio.core.SheetDataFeaturesTest | 4 | 0/0/0 |
| jvm | kr.documentstudio.core.SheetOperationsTest | 9 | 0/0/0 |
| jvm | kr.documentstudio.core.SpreadsheetModelTest | 5 | 0/0/0 |
| jvm | kr.documentstudio.core.TemporaryFilesTest | 1 | 0/0/0 |
| android | kr.documentstudio.BackupSafetyTest | 95 | 0/0/0 |
