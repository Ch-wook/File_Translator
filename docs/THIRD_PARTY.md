# 라이브러리·라이선스·제약 — 0.3.1

검토일: 2026-10-16. 고지·라이선스 원문은 app/src/main/assets/licenses에 포함하고 앱 설정에서 표시합니다.

| 포함 라이브러리 | 버전 | 라이선스·역할 |
|---|---|---|
| Kotlin / Coroutines | 2.2.20 / 1.10.2 | Apache 2.0, 언어·비동기 |
| AndroidX/Compose/Material | BOM 2025.09.01 / Activity 1.11.0 / Lifecycle 해결 2.9.4 | Apache 2.0, Android UI |
| PDFBox Android / FontBox | 2.0.27.0 포트 | Apache 2.0, PDF 읽기/쓰기 |
| Apache POI / scratchpad | 5.4.1 | Apache 2.0, DOC/XLS/PPT 런타임 |
| hwplib | 1.1.10 | Apache 2.0, HWP 런타임, 자체 재배치 POI 포함 |
| HWPX 빈 문서 | hwpxlib upstream commit f9fd2255ac0fc57414e0b657d115e7de51d31c65 | Apache 2.0, 템플릿 자산 |
| Commons IO / Codec | 2.20.0 / 1.18.0 | Apache 2.0 |
| Commons Collections4 / Math3 | 4.4 / 3.6.1 | Apache 2.0 |
| Log4j API / SparseBitSet | 2.24.3 / 1.3 | Apache 2.0 |
| CommonMark Java/GFM Tables | 0.25.0 | BSD 2-Clause |
| LiberationSans-Regular | 2.1.5, 포트 내장 | SIL OFL 1.1, PDFBox fallback 폰트 |
| POI OOXML / hwpxlib | 5.4.1 / 1.0.8, JVM 테스트 전용 | Apache 2.0, 독립 저장 검증 |
| JUnit/AndroidX Test | 테스트 전용 | EPL 1.0/Apache 2.0 |
| Collabora Office / LibreOffice | 26.04.6.0 build 951 | MPL 2.0 및 구성요소별 고지, 실제 로컬 오피스 엔진 |
| R8 keepanno annotations | 9.3.16 | Apache 2.0, JNI 보존 메타데이터 |

[Apache POI](https://poi.apache.org/components/index.html), [hwplib](https://github.com/neolord0/hwplib), [HWPX 템플릿 원본](https://github.com/neolord0/hwpxlib/blob/f9fd2255ac0fc57414e0b657d115e7de51d31c65/testFile/tool/blank.hwpx), [PDFBox Android](https://github.com/TomRoush/PdfBox-Android), [CommonMark](https://github.com/commonmark/commonmark-java), [Apache 라이선스](https://www.apache.org/licenses/LICENSE-2.0).

업스트림 JAR의 Apache NOTICE를 추출해 Upstream-Apache-NOTICES.txt와 Apache-POI-NOTICE.txt에 보존했습니다. Liberation 폰트 name 테이블의 copyright/version/license를 확인했고 Google 2010, Red Hat 2012, SIL OFL 1.1 원문을 포함했습니다. 수정 폰트를 배포하지 않습니다. [Liberation](https://github.com/liberationfonts/liberation-fonts).

Android 검사 전용 legacy.doc는 Apache POI REL_5_4_1의 test-data/document/simple.doc(Apache 2.0)이며 release 앱에 들어가지 않습니다. legacy.ppt는 프로젝트에서 생성한 검사 자료입니다.

**본 제품은 한컴의 HWP 문서 파일(.hwp) 공개 문서를 참고하여 개발하였습니다.** HWP는 hwplib를 사용한 제한 텍스트 편집이며 한컴 조판 엔진은 아닙니다. [공식 명세](https://www2.hancom.com/support/downloadCenter/hwpOwpml).

## 비용과 대안

현재 채택한 라이브러리의 구매 비용은 없고 상용 SDK/키를 설치하지 않았습니다. 고급 오피스 엔진에 상용 라이선스만이 유일한 방법은 아닙니다. Collabora/LibreOffice는 이번 버전의 실제 Android 정밀 편집 엔진입니다. 공식 바이너리와 대응 Java/res를 포함하며 MPL 2.0 및 구성요소별 고지·수정 소스·출처를 보존했습니다. [모바일 제품](https://www.collaboraonline.com/collabora-office-android-ios/), [고지](https://www.collaboraonline.com/collabora-office-for-android-ios-credits/).

ONLYOFFICE 모바일 WebView 통합은 상용 모바일 배포 조건과 서버/스토리지/콜백 구성이 필요합니다. [공식 통합 문서](https://api.onlyoffice.com/docs/docs-api/get-started/how-it-works/mobile-integration). 현재 앱에는 통합하지 않았습니다.

Nutrient/Apryse/Aspose는 배포·모듈에 따른 라이선스 검토와 견적이 필요합니다. [Nutrient 가격](https://www.nutrient.io/sdk/pricing/), [Apryse 가격](https://apryse.com/pricing/guide), [Aspose 가격](https://purchase.aspose.com/pricing/). 공개 근거가 없는 금액은 제시하지 않습니다. 서버를 선택하면 운영비·인증·저장소 비용도 별도입니다.

## 보안·호환성의 한계

PDFBox Android는 오래된 PDFBox 2.0.27 기반입니다. Bouncy Castle을 제외하고 암호화/서명 문서를 거부합니다. Commons IO를 갱신했어도 상위 PDFBox 수정/취약점이 모두 해결된 것은 아닙니다. 공개 출시 전 포트 보안 감사·악성 PDF 코퍼스·격리 실행 검토가 필요합니다.

ZIP/XML/텍스트/OLE 용량 제한·DTD/엔티티 차단·매크로/전자서명 거부·네트워크 미사용을 적용했지만 완전한 악성 문서 샌드박스는 아닙니다. 앱 전용 저장소와 OS 보호를 사용하며 별도 앱 수준 암호화는 없습니다. 내보낸 ZIP도 암호화되지 않습니다.

의존성은 재현 가능한 조합으로 고정했습니다. lint의 버전/최적화 권고가 남으며 업데이트 시 회귀 검사가 필요합니다. 전체 전이 SBOM·정식 법무 승인·상용 Office/한컴 호환성 인증은 이 결과에 포함되지 않습니다.


## 포함한 Collabora/LibreOffice 엔진

26.04.6.0 snapshot build 951(2026-10-15)의 arm64-v8a/x86_64 공식 APK에서 네이티브 라이브러리·자산을 추출했습니다. SHA-256은 prepare-office-engine.ps1, 인증서·전체 대응 소스 링크·수정 내역은 officeruntime/PROVENANCE.md에 기록합니다. Java/res는 일치하는 commit cf7b84af545e7112ee7ea69d7ae1ccbd2b38e4e9입니다. MPL 원문은 officeruntime/licenses와 앱 고지에 포함합니다.

공식 license.html·notice.txt와 폰트/구성요소 고지를 유지하고 앱 설정에서 엔진 고지를 표시합니다. Java 로그·콘솔 차단, 호스트 종료/저장 대기·전용 파일 접근, AI 메뉴 숨김 등 변경 소스를 함께 배포합니다. 자체 JNI 로그 보호 브리지는 소스에 포함합니다. 바이너리 다운로드를 제외한 소스 ZIP만으로 초기 빌드할 때는 고정 공식 입력의 네트워크 다운로드가 필요합니다.

공식 엔진 바이너리를 재컴파일하지 않았습니다. 글꼴 설정의 캐시 경로를 이 앱의 패키지로 바꿨습니다. Collabora/LibreOffice 상표권은 각 소유자에게 있으며 이 독립 앱의 보증·제휴를 뜻하지 않습니다. 공개 배포의 정식 법무·전체 전이 라이선스 감사는 수행하지 않았습니다.

## 2026-10-17 OCR 추가

Google ML Kit Korean/Latin Text Recognition 16.0.1과 모델을 앱에 번들로 포함한다. 모델은 오픈소스 라이선스가 아니며 [ML Kit 약관](https://developers.google.com/ml-kit/terms)과 Google APIs 약관을 따른다. 공식 [Android 통합 문서](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)의 번들 방식을 사용한다. 이미지·인식 결과는 기기에서 처리하고, SDK 통계 전송도 불가능하도록 최종 manifest에서 INTERNET 권한을 제거한다. 앱 고지에 SDK 특성과 약관 링크를 표시한다. 런타임 모델 다운로드/클라우드 OCR은 사용하지 않는다.

검색 텍스트의 한글 글꼴은 변경하지 않은 Noto Sans KR(Google Fonts b38c5c93af322c45f633e17ac440ec1e6c94d489), SIL OFL 1.1이다. 10,414,588바이트이며 SHA-256 `194018e6b2b293a7964f037b25c0249ce1418bc9ab3c971060a03aa57861e252`, 원문 라이선스는 assets/licenses/NotoSansKR-OFL.txt에 있다. PDF에는 필요한 글리프를 subset 임베딩한다. SDK/글꼴 고지는 앱 라이선스 화면에도 포함한다.
