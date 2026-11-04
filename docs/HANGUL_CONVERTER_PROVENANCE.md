# HWP→HWPX 변환기 출처와 변경

- 원본: https://github.com/neolord0/hwp2hwpx
- 고정 커밋: `edc05278506b663d5bdd98050a51f54b7ff5e0bc`
- 라이선스: Apache-2.0. 앱의 `licenses/hwp2hwpx-LICENSE.txt`에 원문 포함.
- 위치: `core/src/main/java/kr/dogfoot/hwp2hwpx/`. upstream Maven 프로젝트의 Java 소스를 포함한다. Android AWT 의존성 없이 hwplib 1.1.11, hwpxlib 1.0.9와 함께 빌드한다.
- Document Studio 변경: `ForChars`의 하이픈 직전 버퍼 유실 수정, 마지막 런에서 65,535자 이후 범위를 벗어나는 문제 수정. `ForPicture`의 오른쪽 내부 여백이 위쪽 값으로 복사되는 문제 수정. 변경 파일 첫 줄에 수정 고지를 넣었다. 변환 결과의 제작 앱 메타데이터는 래퍼에서 Document Studio로 지정한다.
- 별도 Kotlin 래퍼 `HangulConversion`이 입력 제한, 지원 제어 선별, 텍스트/구조/바이너리 대조와 검증 후 결과 파일 생성을 담당한다. upstream이 지원하는 모든 기능을 앱이 보증한다는 뜻은 아니다.

`ForContentHPFFile`의 선택 메타데이터가 null인 경우 날짜/문자열을 생략하도록 수정했다. 생성 앱 이름은 Document Studio, appVersion은 `0.5.0 Android`, 숫자 OS 필드는 `0`으로 기록한다. OS가 숫자임은 [한컴 공개 모델](https://github.com/hancom-io/hwpx-owpml-model/blob/main/OWPML/Class/Etc/version.h)의 `GetOS/SetOS(UINT)`와 writer를 확인했다.

이 모듈은 페이지 조판 엔진이 아니다. 시각적 동일성, 한컴 인증, HWPX→HWP 역변환은 제공하지 않는다. 변환 전 HWP와 검증 보고서는 새 작업의 백업 자료에 보존한다.

추가 수정: `HWPUtil.mediaType`은 `Locale.ROOT` 기준 대소문자 구분 없이 이미지 확장자를 처리한다. Kotlin 래퍼는 모든 ZIP 파트 내용을 유지하며 첫 `mimetype` 항목을 무압축으로 정규화한 결과를 검사·반환한다.
