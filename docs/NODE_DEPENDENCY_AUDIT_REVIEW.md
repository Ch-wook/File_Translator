# Rhwp npm 권고 점검 — 2026-11-04

실제 실행: [Actions41813288172](https://github.com/Ch-wook/File_Translator/actions/runs/41813288172), 코드 b68d31951ffa881c8784ad30897ddc8dfd0fd4de. Rhwp는 1a76570e833917d15817415a53c09ad61ab3203f로 고정했다. 검사 설치에는 패키지 스크립트를 실행하지 않았다.

npm의 production 분류에서는0건, 개발·빌드 도구까지 포함한 전체 분류에서는22개 패키지에 권고가 일치했다(높음8, 중간9, 낮음5, 치명적0). 10월29일의16건 결과로 최신 검사 결과를 대신하지 않는다. 원시 목록과 권고 링크는 [JSON](releases/0.8.11-preview-node-audit.json)에 보존한다.

| 범위 | 실제 확인 / 남은 조치 |
|---|---|
| Vite / launch-editor | 개발 서버의 파일 접근·미들웨어·편집기 실행 관련 권고. 앱은 빌드된 정적 자산을 사용하며 Vite 개발 서버를 실행하지 않는다. 빌드 도구의 취약점을 수정한 상태는 아니다. |
| Puppeteer / chromium-bidi / zod | 브라우저 자동화 및 스키마 검증 도구 체인. production 검사와 별도로 남겨 두며 도구 업데이트·회귀 검증이 필요하다. |
| Workbox / PWA / ejs / jake / filelist / glob / minimatch | 새로 보고된 전이 도구 체인과 정규식·파일 탐색 관련 권고를 포함한다. 서비스워커 생성 도구 등의 버전 갱신을 검증해야 한다. |
| Babel / source-map-js / browserslist / baseline-browser-mapping | 소스 맵·브라우저 대상 계산의 파일 접근/메모리·서비스 거부 권고. 문서 파일을 이 빌드 입력으로 전달하지 않는다. 수정 완료를 뜻하지 않는다. |
| brace-expansion / deepmerge / fast-uri / serialize-javascript / Rollup 플러그인 | 메모리·재귀·프로토타입·URI 처리·직렬화 관련 권고. 전이 의존성 갱신과 실제 빌드·번들 검사가 남아 있다. |

production0건은 npm의 의존성 분류 결과다. 전체 APK·네이티브/Wasm·최종 JavaScript 번들에 취약점이 없다는 보장이 아니다. 개발 의존성에서 나온 코드가 번들에 전혀 포함되지 않았다는 증명으로도 사용하지 않는다. 이번 작업에서는 기존 엔진과 빌드를 유지하며 실제 권고를 공개한 개발 서명 프리뷰로 제공한다. 이 미해결 항목과 실제 기기 검증·서명키·스토어·법적 검토 때문에 상용 출시 완료로 판정하지 않는다.

Maven·권한·릴리스 구조 점검은 별도 보고서와 검증 ZIP에 포함한다. 한 영역의0건을 다른 영역에 확대 적용하지 않는다.
