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
