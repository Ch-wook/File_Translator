# 검증 자료 안내 — 0.8.1-preview

이 버전은 이전 미푸시 0.8.0을 복구한 결과가 아니라 `2bfe225`에서 재구현한 코드입니다. [출처 감사](ORIGINAL_VIEW_AUDIT.md)를 확인하세요.

GitHub Actions `Android preview verification`이 아래 검사를 실행합니다. 검사 목록은 통과 선언이 아닙니다. 실제 결과는 해당 커밋의 Actions 로그와 Release의 verification ZIP에 기록합니다.

1. core JUnit, Android Robolectric, debug APK 및 AndroidTest APK 빌드, Android lint.
2. JavaScript 권한 검사와 실제 Rhwp/Chromium 문서 편집·한국어 입력·undo/redo·동일 형식 저장/재열기 시나리오.
3. 생성한 한글 엔진의 TypeScript 검사 및 독립 HWP/HWPX 파서 왕복 검사.
4. Android 35 x86_64 에뮬레이터의 전용 P0 모드 UI 검사. 실패·생략은 별도로 기록하며 실제 휴대폰 검증으로 표시하지 않습니다.
5. APK 서명·ZIP/ABI/WASM 포함·INTERNET 권한 부재 검사.
6. 전체 추적 소스 ZIP, 바이너리 변경 패치, 이 브랜치의 Git bundle, 검증 ZIP과 SHA256SUMS 생성.
7. Release 태그의 커밋과 업로드 후 공개 다운로드 파일의 크기·SHA-256 대조.

검증 ZIP의 `artifacts/ci/summary.json`에는 커밋, 기준 소스, 테스트 수, 에뮬레이터 결과, 실제 기기 미검증 여부가 담깁니다. 기기 테스트의 실행 실패를 단위 테스트 통과 수에 합산하지 않습니다.

실제 휴대폰의 한국어 IME 조합·터치 선택/드래그·회전·파일 제공자·전체 Office 저장/재열기 검증은 남아 있습니다. 모든 파일 형식과 모든 고급 요소의 보존을 확인했다는 의미가 아닙니다.
