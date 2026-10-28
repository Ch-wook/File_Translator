# Offline Android Hangul engine

- Upstream: https://github.com/edwardkim/rhwp
- Version/tag: 0.8.7 / v0.8.7
- Source commit: `1a76570e833917d15817415a53c09ad61ab3203f`, MIT.
- WASM/JS: `@rhwp/core@0.8.7`; npm archive URL and SHA-256 are pinned in `engine-lock.json`.
- Build: `scripts/prepare-hangul-engine.py`, Node.js 24 + Python 3.11+. Upstream dependencies use `npm ci --ignore-scripts`; CanvasKit 0.42.0 is retained and Canvas2D is the selected renderer.
- Fonts: upstream `assets/fonts`, excluding Cafe24/Happiness. Bundled fonts use SIL OFL 1.1 or GUST. Copyright/license texts ship under Android `assets/licenses` and are visible in Settings. The upstream MIT `LICENSE` is copied as `RHWP-LICENSE.txt`; `THIRD_PARTY_LICENSES.md` is also retained.

## Source and local patches

This is a reimplementation on the available `2bfe225` source baseline. It does not recover or reproduce the unavailable historical `aa3be049` commit.

All local engine integration changes are tracked here: `patch-engine.py`, `host-readonly.ts`, `engine-adapter.ts`, the HTML/CSS/JavaScript host, the preparation script, and `HangulEditorActivity.kt`. Generated upstream source, npm packages, runtime assets and caches are excluded from Git/source ZIPs and rebuilt from the pinned sources. No proprietary fonts, private signing keys or user documents are required to rebuild.

The preparation script checks out the pinned commit before applying changes, copies the policy module, and applies exact source anchors that fail if the upstream code differs. It patches the main composition point before `WasmBridge` construction, then appends the host adapter. All policy/patch/host files participate in the cache fingerprint. TypeScript checking precedes the Vite build. VitePWA, external webfonts and HWPCTRL remain disabled.

## Document model and modes

Rhwp owns parsing, page layout, hit testing, document models, text/table/image editing, undo/redo and same-format serialization. Structured documents are never replaced by extracted text in an HTML input field.

The default is VIEW. EDIT is enabled only after an explicit host edit request. The same engine document instance remains loaded during mode changes. The adapter samples actual DOM scroll positions and engine zoom, restores them after the chrome changes, and keeps view state separately from document bytes in a per-session AtomicFile.

The local read-only controls are independent of tool visibility:

- The command dispatcher permits a fixed set of copy/search/navigation/display commands in VIEW. Undo/redo, formatting, structure changes, form-edit mode and unknown commands are denied.
- The actual `WasmBridge` instance permits reviewed read/layout/clipboard operations and denies other methods in VIEW. Document loading has a scoped initialization permission. Serialized caret stamping is suppressed in VIEW; borrowed raw-document methods are guarded too.
- Input-handler IME, insertion, paste/cut, undo/redo, mutation execution and edit-input focus paths are blocked in VIEW. Document/object pointer-start events are stopped so transform paths cannot start; caret and resize/rotation layers are hidden. Native touch scrolling, search and zoom controls remain available.
- `HangulEditorActivity` separately authorizes begin/chunk/finish write calls and image selection. Reading and closing do not invoke the document export/write pipeline.

The compact host toolbar sends real dispatcher commands for character/paragraph formatting, table creation and cells/rows/columns, image insertion/properties/order, and page/column setup. Buttons use current engine command availability; the tool row can be collapsed. Editing selection/drag and Korean IME stay in the engine. Automatic checkpoints defer while a Korean composition is active instead of interrupting composition by blurring the input.

## Private working files and saving

Host `/document` serves only a validated private working file at an isolated HTTPS asset origin. Every other origin is blocked and the APK has no INTERNET permission. Image insertion uses an Android user-selected content URI. No remote document server, account or license key is required.

Independent engine file-open/new/save-as/export/print commands are disabled so engine actions cannot replace the session identity or download outside the Android storage workflow. A host checkpoint validates the same output format and engine content-loss report, streams bounded bytes into a staged file, preserves missing opaque ZIP/OLE resources, syncs and atomically replaces the private working file. Only then is an fsynced `.edited` marker created. The repository import and SAF conflict/backup/read-back workflow handles original files. Unmodified VIEW sessions do not create that approval marker.

## Limits and validation evidence

A zero-loss report is necessary but does not independently prove preservation of every HWP/HWPX record. Unknown data inside regenerated body/header records, complex controls and proprietary font shaping remain compatibility limits.

VIEW intentionally prevents document hit/drag starts to protect objects. Full touch text/object selection is an EDIT feature; VIEW retains search/scroll/zoom. This should not be described as unrestricted text selection in a read-only canvas.

The repository includes policy tests, Android bridge tests and actual Rhwp/Chromium scenarios for explicit editing, mode transitions, Korean text, undo/redo, save/reopen, read-only input/command/drag denial and zero document writes on close. Their presence is not evidence that a particular build passed: use the matching GitHub Actions run and packaged test reports for executed results. Chromium touch emulation and Robolectric do not establish physical Android System WebView/IME, pen, gesture or native storage-provider behavior. No physical Android-device validation was performed during this source reimplementation.

See the current support matrix and the matching build report before distributing as a production-compatible editor.

## Handwritten signatures and PDF conversion

The host signature panel emits a transparent PNG and uses the native InputHandler image-placement path. Document text, cell/image anchors, transforms, undo and serialization remain engine-owned. Reusable signature vectors and PNGs stay in app-private AtomicFile storage. The initial PDF converter renders every full engine page at its document dimensions into Android PdfDocument; this is a raster PDF, not viewport capture and not searchable/selectable text. Unsupported advanced conversions remain absent from the target list.
