#!/usr/bin/env python3
"""Apply the host read-only gate to the exact pinned upstream source; fail on drift."""
from pathlib import Path
import sys
root=Path(sys.argv[1])
main=root/'src/main.ts'
source=main.read_text()
def replace_once(before, after):
    global source
    if source.count(before)!=1: raise RuntimeError('Pinned Rhwp source changed: '+before[:100])
    source=source.replace(before, after, 1)
replace_once("const wasm = new WasmBridge();", "import { installStudioBridge, installStudioInput, studioMayWrite, studioMayCommand, studioDocumentLoad, studioSetEditing, studioIsEditing } from './host-readonly';\ninstallStudioInput(InputHandler, CommandDispatcher);\nconst wasm = new WasmBridge();\ninstallStudioBridge(wasm);")
replace_once("isEditable: !isFormMode || canEditFormField,", "isEditable: studioMayWrite() && (!isFormMode || canEditFormField),")
replace_once("canUndo: inputHandler?.canUndo() ?? false,", "canUndo: studioMayWrite() && (inputHandler?.canUndo() ?? false),")
replace_once("canRedo: inputHandler?.canRedo() ?? false,", "canRedo: studioMayWrite() && (inputHandler?.canRedo() ?? false),")
main.write_text(source)
