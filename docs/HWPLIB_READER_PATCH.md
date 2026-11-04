# hwplib 개체 헤더 읽기 수정

Maven `kr.dogfoot:hwplib:1.1.11`은 2026-09-09에 배포됐으며, [공식 #315 수정](https://github.com/neolord0/hwplib/commit/877c78af2f327f84b8a07e1691fe3c79d53b43d0)은 2026-09-11이다. 버전 번호만 올려서는 이 수정이 포함되지 않는다.

Rhwp가 새 표를 저장할 때 사용하는 42바이트 CTRL_HEADER는 필수 필드 뒤에 빈 설명 길이 2바이트가 남는다. 종전 파서는 선택적인 4바이트 속성을 무조건 읽어 다음 레코드까지 2바이트 소비하고 `This is not paragraph.`를 발생시켰다. 공식 수정은 레코드에 남은 바이트 수를 확인한 뒤 선택 속성을 읽는다. 파서 오류를 무시하거나 원본 문서를 다시 쓰는 처리는 하지 않는다.

## 포함 방식

- Apache-2.0, Park Sungkyon(neolord0).
- 원본: `neolord0/hwplib`, commit `877c78af2f327f84b8a07e1691fe3c79d53b43d0`.
- `core/src/main/java/kr/dogfoot/hwplib/reader/bodytext/paragraph/control/gso/part/ForCtrlHeaderGso.java`는 출처 주석 외에는 이 커밋의 구현 그대로다.
- `prepareHwplibRuntime`은 Maven 1.1.11 JAR에서 이 클래스 한 개와 재생성되는 JAR manifest만 제외한다. 나머지 클래스·리소스는 원본 그대로 포함한다. 수정 클래스는 기존 core Java 빌드로 컴파일해 중복 클래스를 만들지 않는다.
- Android 앱과 Android 테스트는 동일한 필터 JAR/수정 클래스를 사용한다. 별도 키·서버·새 편집 엔진은 없다.

## 검증

42바이트 짧은 헤더, 46바이트 전체 헤더의 쪽나눔 방지 속성, 설명 문자열을 검사하고 다음 레코드의 정확한 위치와 태그가 유지되는지 확인한다. 실제 엔진에서 만든 HWP/HWPX의 부분 서식·표 내용/구조·그림 원본 바이트 검사와 Android 저장 후 재열기는 별도로 요구한다. 테스트 결과는 해당 빌드의 Actions 로그와 검증 ZIP을 기준으로 한다.
