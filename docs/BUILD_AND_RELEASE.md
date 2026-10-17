# 빌드·서명·배포 — 0.4.0

## 환경

JDK 17, Android SDK Platform 36, Build Tools 35.0.0, NDK 28.2.13676358, CMake 3.22.1, Gradle wrapper 9.1.0을 사용합니다. Android Studio에서 루트를 열고 Gradle JDK를 17로 선택합니다. SDK 경로는 사용자 환경의 local.properties에 지정합니다.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
.\gradlew.bat :core:test :app:assembleDebug :app:lintDebug
.\gradlew.bat :app:connectedDebugAndroidTest
.\scripts\build-release.ps1
.\scripts\verify-release.ps1
.\scripts\package-delivery.ps1
```

Android 검사는 API 35 에뮬레이터를 켜고 실행합니다. connectedDebugAndroidTest는 검사 종료 시 앱을 제거할 수 있으므로 이후 다시 설치합니다.

코어만 검사할 때는 `.\gradlew.bat :core:test --configure-on-demand`로 Android 네이티브 모듈 구성을 생략할 수 있습니다. 편집 흐름 추가 검증은 Android 15/API 35의 StudioReviewApi35 에뮬레이터를 사용하며, 결과와 이전 검증 이력은 [검증 결과](TEST_RESULTS.md)에 구분합니다. API 36 에뮬레이터는 앱 실행 전 시스템 UI 응답 없음이 발생해 이번 UI 통과 환경에 포함하지 않습니다. 첫 전체 빌드는 대형 엔진·의존성 처리에 메모리를 많이 사용하므로 메모리가 부족하면 에뮬레이터를 빌드 후 실행하고 `--max-workers=2`로 동시 작업을 줄이세요.

전체 기능의 반복 검증은 [계획서](IMPLEMENTATION_PLAN.md)와 [인수 확인표](FEATURE_ACCEPTANCE.md)를 기준으로 다음 명령을 사용합니다. JDK 17과 ANDROID_HOME 또는 SDK 경로를 먼저 설정하고, API 35 기기/에뮬레이터를 한 대 연결합니다.

```powershell
.\scripts\verify-editing.ps1
# 여러 기기가 연결된 경우
.\scripts\verify-editing.ps1 -DeviceId emulator-5554
# 기기 없이 JVM/lint/debug 빌드만 확인 (전체 통과로 표시하지 않음)
.\scripts\verify-editing.ps1 -SkipDevice
```

JVM 검사를 새로 실행하고 Android 전체 검사·lint·debug APK와 검사 APK 빌드를 수행합니다. 결과 XML·lint·Gradle 로그·API/ABI/화면 조건·APK SHA-256·요약 JSON은 `artifacts/acceptance-날짜-시각/`에 생성합니다. 오래된 XML은 통과 횟수에 넣지 않습니다. 실패·오류·건너뛰기나 lint 오류가 있으면 실패를 반환합니다. 기기 생략 시 상태는 `partial-device-not-tested`입니다. 검사 뒤 APK를 유지하도록 설정하므로 수동 화면/SAF 검사를 이어갈 수 있습니다. release 서명/배포·실기기·외부 호환성 인증은 별도입니다.

| 결과 | 전달 위치 |
|---|---|
| 개발 서명 APK | artifacts/document-studio-debug.apk |
| 로컬 출시 키 서명 APK | artifacts/document-studio-release.apk |
| 동일 키 서명 AAB | artifacts/document-studio-release.aab |
| 전체 소스/리소스/검사/문서 ZIP | artifacts/document-studio-source.zip |
| 파일 SHA-256 | artifacts/SHA256SUMS.txt |
| 검사 보고서 | artifacts/test-results/ |

AAB는 직접 설치 파일이 아니라 스토어용 번들입니다. APK는 Android 10 이상에서 설치합니다. 패키지 ID는 kr.documentstudio, versionCode 5, versionName 0.4.0입니다. 이 결과를 Play에 업로드하거나 계정 설정을 변경하지 않았습니다.

## 서명 키 관리

build-release.ps1은 STUDIO_KEYSTORE가 설정되어 있으면 사용자가 지정한 키를 사용합니다. 없으면 이 프로젝트용 RSA 3072/PKCS12 키를 처음 한 번 생성하고 재사용합니다. 키 생성 및 빌드는 이미 실행했습니다.

로컬 비공개 파일:

- .signing/document-studio-upload.p12
- .signing/release.json — 별칭·암호·키 경로 포함

.signing은 현재 Windows 사용자와 SYSTEM에 권한을 부여했고 Git/소스 ZIP에서 제외했습니다. **두 파일을 안전한 비공개 위치에 함께 백업하세요. 암호 파일을 공유하거나 APK/소스와 배포하지 마세요.** 다른 PC에서 경로가 바뀌면 release.json의 keystore 경로를 새 비공개 위치로 수정합니다. 키가 있는데 설정이 없으면 스크립트는 키를 교체하지 않고 중단합니다.

기존 키 사용:

```powershell
$env:STUDIO_KEYSTORE = 'C:\안전한위치\upload-key.jks'
$env:STUDIO_KEY_ALIAS = 'upload'
# STUDIO_STORE_PASSWORD와 STUDIO_KEY_PASSWORD는 비공개 환경에서 설정
.\scripts\build-release.ps1
```

서명한 APK는 apksigner verify, AAB는 jarsigner -verify로 검사합니다. debug와 release의 인증서가 다르므로 서로 덮어 설치되지 않습니다. 앱 제거/데이터 삭제 전 ZIP으로 모든 필요한 작업을 백업해야 합니다. 기존에 배포한 앱이 있다면 그 앱의 기존 키/Play App Signing 설정을 사용해야 합니다.

## Play 및 기기 검증 범위

2026-10-17 공식 정책을 재확인했습니다. 2026-08-31부터 일반 Android 신규 앱/업데이트에는 API 36 이상이 요구되며 이 앱은 targetSdk 36입니다. Play의 최신 조건은 [공식 target SDK 안내](https://developer.android.google.cn/google/play/requirements/target-sdk?hl=en)를 기준으로 실제 제출 시 다시 확인해야 합니다. API 35 에뮬레이터 실행이 Android 16/17 실기기 검증을 대신하지 않습니다. [Android 17](https://developer.android.com/about/versions/17).

APK 서명·ZIP 정렬과 포함 네이티브 라이브러리 정렬을 검사하지만 실제 16 KB 페이지 크기 실행은 미실시입니다. [공식 16 KB 안내](https://developer.android.com/guide/practices/page-sizes). API 29/36/37, Galaxy Tab/S펜, 외부 키보드·마우스, SAF 제공자별 장애, 실제 사용자 문서 코퍼스와 보안·접근성 검증을 마친 뒤 공개 출시를 판단해야 합니다.

앱은 INTERNET/전체 저장소 권한과 문서 업로드를 포함하지 않습니다. 번들 OCR SDK의 통계 전송도 앱 INTERNET 권한 제거로 차단합니다. OS 자동 백업/기기 전송은 꺼져 있으며 사용자가 선택한 SAF 저장·내보내기만 수행합니다. 소스 내 라이브러리 고지는 앱에 포함했습니다.

## 복구 smoke

debug APK 설치 후 다음을 실행합니다. 샘플 작업을 추가하여 실제 프로세스 강제 종료 후 본문/필기 복구를 검사합니다. 기존 작업을 삭제하지 않습니다.

```powershell
.\scripts\smoke-recovery.ps1
```

Windows PowerShell 5.1에서 한글이 있는 .ps1 파일은 UTF-8 BOM으로 저장합니다.



## 포함 엔진과 빌드 캐시

officeruntime의 preBuild가 prepare-office-engine.ps1을 실행합니다. 최초 실행 시 공식 고정 APK 두 개(약 523 MB)를 받고 SHA-256을 검증합니다. .engine-cache에는 APK·압축 해제 자산·ABI 라이브러리를 저장합니다. 인터넷은 최초 빌드에 필요하고 설치 앱의 편집은 로컬입니다. 소스 ZIP에는 캐시와 비공개 서명 키를 제외하고 엔진 Java/res·호스트·NDK 브리지·고지·검증 다운로드 스크립트를 포함합니다. 출처는 officeruntime/PROVENANCE.md를 확인하세요.

빌드는 Windows PowerShell을 사용합니다. CMake/NDK가 없으면 SDK Manager로 위 버전을 설치하세요. 두 ABI 라이브러리를 포함해 APK와 설치 공간이 커졌습니다. 32비트 ARM/x86은 이번 번들에서 제외했습니다. 정밀 편집은 프로세스 로그 보호 API 때문에 Android 11 이상입니다.

AAB 생성·서명은 스토어 업로드 승인이나 용량 정책 검증을 뜻하지 않습니다. 대형 엔진을 포함하므로 실제 스토어 제출에는 기기별 다운로드 크기 검사와 필요 시 모듈/자산 전달 구성 변경이 추가로 필요합니다. 이 프로젝트에서 계정 접근·업로드·출시는 수행하지 않습니다.

## 별도 빌드 드라이브

내장 엔진 중간 파일이 크므로 여유 공간이 있는 절대 경로를 지정할 수 있습니다. 같은 경로를 검증·출시·패키징에 전달합니다. 기존 build 폴더를 삭제하거나 이동하지 않습니다.

```powershell
$buildRoot = 'D:\CodexBuilds\DocumentStudio'
.\scripts\verify-editing.ps1 -DeviceId emulator-5556 -BuildRoot $buildRoot
.\scripts\build-release.ps1 -BuildRoot $buildRoot
.\scripts\verify-release.ps1
.\scripts\package-delivery.ps1 -BuildRoot $buildRoot
```

Gradle 직접 호출에는 `-Pstudio.buildRoot=<절대 경로>`를 사용합니다. 기본 Java 힙은 3 GiB이며 `-JavaHeapMiB`로 조정합니다. 기존 앱 업데이트에는 이전 키를 비공개 환경 변수로 지정하세요. 새 복제본에서 다른 키를 만들면 이전 앱을 업데이트할 수 없습니다. 이번 빌드도 기존 비공개 키를 재사용합니다.
