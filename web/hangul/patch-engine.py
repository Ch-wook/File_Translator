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

# Search, table creation and some native navigation return valid flat cell positions.
# The pinned formatting commands formerly serialized these as [], silently changing no text.
# Use the engine's existing canonical cell-axis fallback for both apply and history restoration.
command=root/'src/engine/command.ts'
text=command.read_text()
for before,after in [
    ("return JSON.stringify(pos.cellPath ?? []);", "return JSON.stringify(cellAxisPath(pos));"),
    ("const path = (pos.cellPath ?? []).map((e) => ({ ...e }));", "const path = cellAxisPath(pos).map((e) => ({ ...e }));"),
]:
    if text.count(before)!=1: raise RuntimeError('Pinned cell formatting source changed: '+before)
    text=text.replace(before,after,1)
command.write_text(text)
