# Embedded Office runtime

This module embeds Collabora Office 26.04.6.0 snapshot build 951 (2026-10-15).
The native libraries are unmodified official binaries, with their bundled assets
and notices. The font cache path is changed to this application's package.

Official download index: https://www.collaboraoffice.com/downloads/Collabora-Office-Android-Snapshot/

Matching complete corresponding source:
https://github.com/CollaboraOnline/online.mirror/tree/cf7b84af545e7112ee7ea69d7ae1ccbd2b38e4e9

`src/main/java` and `src/main/res` originate from `android/lib/src/main` at that
commit. Upstream files retain MPL-2.0 headers. See `licenses/MPL-2.0.txt`.
New host/bridge code is included in this source distribution.

Local changes to LOActivity and supporting Java files:

- Discard Java diagnostic messages and WebView console output; disable WebView debugging.
- Disable native logging with a small JNI bridge before loading/processing document messages.
- Use private app-owned file copies without requesting shared storage permissions.
- Close the nested activity without removing the host task.
- Wait for successful native save and document close before reporting a completed edit.
- Remove the host application's unused store rating prompt.
- Hide unconfigured AI entry points with a local host policy script.
- End the private engine process after a finished document; do not reuse kit state.

The JNI bridge uses Android 11's process-local log callback and priority API;
the host offers basic tools on Android 10. Upstream ART/Dex optimization profiles
are excluded because they refer to the upstream APK's classes. The two pinned
APKs have identical non-profile assets, verified by ZIP CRC and uncompressed size.

The pinned APK URLs and SHA-256 digests are in `scripts/prepare-office-engine.ps1`.
The official APK signing certificate SHA-256 is
`573258c84e149b5f4d9299e7434b2b69a8410372921d4ae586ba91ec767892cc`.
The source archive excludes downloaded APKs and generated native assets; the
preparation script retrieves the exact pinned inputs. Internet is needed for
initial build downloads; the installed editor has no INTERNET permission.

Bundled `assets/license.html` and `assets/notice.txt` retain the upstream engine,
fonts and other third party notices. Collabora and LibreOffice trademarks belong
to their respective owners; this independent application is not endorsed by them.

## 0.6.0 파일 표시 이름

호스트가 원래 문서의 표시 이름을 전달하고, LOActivity의 내보내기 파일 이름에 사용한다. 파일 URI에서는 마지막 경로 요소를 기본 이름으로 사용한다. 개인 작업 파일 경로·검증·격리·원본 저장 보호는 기존 경로를 유지한다. 수정 Java 소스는 이 모듈에 포함된다.

## 2026-10-26 원본 보기 우선 재구현

원격 기준 `2bfe225ab040e3cb9251a8cafce9578ea3d9b645`에서 다시 작성한 호스트 변경입니다.
이전 로컬 0.8.0 커밋/산출물을 복구한 것으로 표시하지 않습니다.

- 기본 `openIntent=VIEW`; 알려지지 않은 값도 VIEW입니다. `permission=edit`는 편집 가능 여부이며,
  초기 `startreadonly=true`와 네이티브 `setviewreadonly value=true`가 실제 보기 모드를 설정합니다.
- 명시적 편집 요청에만 Android 브리지가 권한을 부여합니다. 키보드/IME·붙여넣기·개체 변경과
  쓰기 UNO 명령은 보기 상태에서 차단합니다. 읽기 전용 UNO 허용은 정확한 Copy/SelectAll과
  FIND/FIND_ALL 검색에 한합니다. Replace/ReplaceAll 및 비슷한 이름의 변경 명령을 허용하지 않습니다.
- 같은 네이티브 문서 인스턴스에서 `map._switchToEditMode()` /
  `map.setPermission('readonly')`로 전환합니다. part·zoom·twips 위치를 캡처하고
  `layoutingService.onDrain` 뒤 레이아웃의 `applyZoom`/`scrollTo`로 복원합니다.
  보기 위치는 문서 바이트와 별도의 AtomicFile 메타데이터입니다.
- 편집 허용과 실제 `.uno:ModifiedStatus`를 구분합니다. 읽기만 하거나 편집 모드만 왕복한 문서는
  저장하지 않습니다. 승인된 실제 수정의 네이티브 Save 성공을 확인한 뒤에만 동기화된
  `.edited` 표시를 기록하여 호스트가 작업 복사본을 가져오게 합니다.
- 모바일 보기 상태에는 모드 전환/돌아가기 중심의 작은 바를 표시합니다. 편집 시 Writer 글꼴·문단·쪽,
  Calc 셀 서식·함수 마법사, Impress 슬라이드와 기존 개체 도구를 실제 UNO 명령에 연결합니다.
  이 명령 이름은 위 고정 업스트림의 Writer/Calc notebookbar 소스와 대조했습니다.
- WebView/네이티브 로드 실패는 호스트에 오류로 전달합니다. 원본 URI는 계속 엔진에 전달하지 않습니다.

추가 정책 검사 소스는 `scripts/tests/office-workspace.test.cjs`와
`src/test/java/org/libreoffice/androidlib/OfficeEditPolicyTest.java`입니다.
실행 결과는 배포 검증 기록을 확인해야 합니다. 정책 단위 검사는 실제 Android의 네이티브 Office,
한국어 IME·터치/개체 조작·저장/재열기 종합 검증을 대신하지 않습니다.

## Local document conversion

The mobile host exposes the existing native PDF saveAs filter through a compact document menu. It writes a private staging PDF, validates pages with Android PdfRenderer, then copies to a user-selected URI and verifies readback. The existing document remains owned by its original working copy. No print or print-preview UI is added. The Java host changes remain included here under the upstream MPL-2.0 file headers.
