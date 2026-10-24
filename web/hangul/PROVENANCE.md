# Offline Android Hangul engine

- Upstream: https://github.com/edwardkim/rhwp
- Version/tag: 0.8.7 / v0.8.7
- Source commit: `1a76570e833917d15817415a53c09ad61ab3203f`, MIT.
- WASM/JS: `@rhwp/core@0.8.7`, npm archive URL and SHA-256 in `engine-lock.json`.
- Build: `scripts/prepare-hangul-engine.py`, Node.js 24 + Python 3.11+. Locked upstream npm dependencies with `npm ci --ignore-scripts`; CanvasKit 0.42.0 is retained, Canvas2D is the selected renderer.
- Fonts: upstream `assets/fonts`, excluding Cafe24/Happiness. Bundled fonts use SIL OFL 1.1 or GUST. Copyright and license texts are shipped under Android `assets/licenses`, visible in Settings. Upstream `THIRD_PARTY_LICENSES.md` is also retained.

Local changes are fully represented in this repository: `engine-adapter.ts` appended to Studio main, VitePWA disabled, external webfonts and HWPCTRL excluded, and the Android host/CSS/native bridge. The pinned upstream source remains downloadable; generated source/cache is excluded from Git and delivery source ZIPs. Build scripts never need runtime document access.

The engine owns parsing, layout, hit testing, document model, edits, undo/redo and same-format serialization. This integration does not feed extracted text into an HTML editor. Host `/document` serves only a validated private working file at an isolated HTTPS asset origin; every other origin is blocked and the APK has no INTERNET permission. Images use the Android user-selected content URI. No remote office server, account or license key is needed.

The host disables independent file open/new/save-as/export/print commands, so engine actions cannot replace the session identity or silently download somewhere outside Android storage. Host save runs content-loss checks, streams bounded bytes to a staged file, preserves missing opaque ZIP/OLE resources, syncs and atomically replaces the private working file. The normal repository import and SAF conflict/backup/check workflow then handles originals.

A zero-loss report is a necessary engine check, not independent proof of every HWP/HWPX record being preserved. Unknown information inside regenerated body/header records, all advanced controls and proprietary font shaping cannot be guaranteed. See the current support matrix and test report before distributing as a production-compatible editor.
