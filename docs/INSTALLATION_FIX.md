# 0.8.2-preview 설치 실패 분석과 수정

사용자가 보고한 문구는 “앱이 설치되지 않는다”이며 기종·Android 버전·상세 설치 오류는 아직 받지 못했습니다. 따라서 해당 휴대폰의 원인을 단정하지 않습니다. 아래 배포 결함은 실제로 재현했습니다.

## 확인한 원인

공개 0.6.0과 0.8.1-preview는 같은 applicationId `kr.documentstudio`를 사용했으나 서명 인증서가 달랐습니다.

- 0.6.0 인증서 SHA-256: `18034d8ff98a32c3273f8466c4e506d82e0a5361502ef042a9347e81379164f1`
- 0.8.1 인증서 SHA-256: `3854a86c9a372bd79b1295e110396aac98fcef82beda001dca4db05a0b43a08d`

[실제 진단 실행](https://github.com/Ch-wook/File_Translator/actions/runs/40315271572)에서 Android 35 x86_64의 0.8.1 새 설치·실행·동일 APK 재설치는 성공했습니다. 0.6.0 설치 후 0.8.1 업데이트는 `INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match`로 실패했습니다. 테스트 전용 허용(-t) 옵션 없이 확인했습니다.

0.8.1 APK는 testOnly가 아니었으며 APK CRC·서명과 포함된 모든 네이티브 라이브러리의 16KB ELF 정렬을 검사했습니다. 모든 설치 실패를 파일 크기나 16KB 문제로 설명하지 않습니다.

## 수정

- 새 프리뷰는 `kr.documentstudio.preview`, 앱 이름은 **문서 스튜디오 프리뷰**입니다. 기존 `kr.documentstudio`를 삭제하거나 덮어쓰지 않고 병행 설치합니다.
- debug 파일 제공자 3개의 authority도 applicationId에 연결해 충돌을 막습니다. 원래 클래스 namespace는 유지합니다.
- ARM64 휴대폰용과 x86_64용 APK를 따로 만듭니다. 각각 단독 설치 가능한 APK이며 base/split 조합 설치가 필요하지 않습니다.
- 알려진 기존 서명키에 접근할 수 없으므로 이를 복구하거나 기존 패키지와 호환되는 업데이트 서명을 만들었다고 주장하지 않습니다.
- 새 프리뷰 ID에 대한 이후 배포에서 인증서가 달라지면 게시를 막습니다. 지속적인 업데이트에는 동일한 비공개 서명키가 필요합니다. 빌드는 보안 저장소에 구성한 STUDIO_KEYSTORE를 사용할 수 있으며 키를 소스·로그·Release 자산으로 공개하지 않습니다.

## 필수 인수 검사

[0.8.2 빌드·검증 실행](https://github.com/Ch-wook/File_Translator/actions/runs/40316820160)과 Release 검증 ZIP을 결과의 근거로 사용합니다. 다음 필수 조건을 검사합니다.

1. 이전 앱이 있는 Android에서 -t 없이 새 프리뷰 설치 및 실행.
2. 이전 APK 해시와 설치 상태 보존, 두 앱의 공존.
3. 프리뷰 내부 canary 파일을 만든 뒤 같은 인증서의 APK로 재설치하고 내용 보존.
4. 새 applicationId에서 전체 P0 Android 19건, 실패·오류·생략 0.
5. ARM64/x86_64 각각 단일 ABI, testOnly 부재, 서명, APK/ELF 16KB 정렬, CRC 및 시험한 엔진 자산 일치.
6. Release 자산을 실제 다운로드하여 크기·SHA-256 대조.

ARM64 실기기 설치·실행은 검증하지 못했습니다. x86_64 에뮬레이터 설치 결과와 ARM64 정적 패키지 검사를 구분합니다. Android 10 이상의 64비트 기기가 필요하며 Office는 Android 11 이상입니다. 32비트 전용 기기를 지원한다고 표시하지 않습니다.

기존 작업은 이전 앱의 문서·필기 ZIP 백업을 새 프리뷰에서 복원할 수 있습니다. 서명 충돌 해결을 위해 이전 앱을 먼저 삭제할 필요가 없습니다.

## 이번 실행 결과

- 앱 빌드 커밋: `dd3585d739d4d839c259a7ac3b77daf2ebf20987`. 배포 태그는 앱 코드 차이가 없는 문서·CI 추가 커밋을 가리킵니다.
- Android 35 x86_64: -t 없이 새 설치·실행, 0.6.0과 병행 설치, 기존 APK 해시 보존, 동일 인증서 APK 재설치 후 프리뷰 내부 canary 보존 통과.
- 전체 P0 Android 19건: 실패·오류·생략 0. core JUnit 117, Office Java 2, 앱 Robolectric 13, JavaScript 21, 실제 Rhwp/Chromium 5개 시나리오·독립 파서·TypeScript·lint 통과.
- DOCX 쪽 나누기·XLSX 열 삽입·PPTX 슬라이드 추가 후 실제 저장·파서·엔진 재열기도 새 앱 ID에서 통과했습니다.
- ARM64: 398,799,022 bytes. SHA-256: `81814b961f0774a99d03bd9723f349312bb1ba334cb4179041664691eee828cd`.
- x86_64: 410,685,512 bytes. SHA-256: `8b79db1f817f6b3b10c1bb66dbd5cf09857b32e1e319026f845bea1a61383fd0`.
- 두 APK는 동일 인증서이며 각자 해당 ABI만 포함합니다. APK/ELF 16KB 정렬, CRC, testOnly 부재, 시험한 엔진 자산 및 소스 ZIP의 Git blob 일치를 확인했습니다.
- APK 크기는 각각 소수 단위 MB이며, ARM64는 0.8.1의 638,031,225 bytes보다 약 37.5% 작습니다.

실제 ARM64 휴대폰 설치·실행 및 사용자 단말의 기종별 설치 정책은 아직 확인하지 못했습니다. 에뮬레이터 결과를 실기기 결과로 표시하지 않습니다.
