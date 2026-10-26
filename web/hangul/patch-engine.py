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

history=root/'src/engine/history.ts'
text=history.read_text()
before="import type { WasmBridge } from '@/core/wasm-bridge';"
if text.count(before)!=1: raise RuntimeError('Pinned history import changed')
text=text.replace(before,before+"\nimport { StudioImeReplacementCommand } from '../studio-ime-history';",1)
before="  canUndo(): boolean { return this.undoStack.length > 0; }"
after="""  groupStudioImeReplacement(deleted: EditCommand | null): void {
    if (!deleted || this.undoStack.length < 2) return;
    const last = this.undoStack[this.undoStack.length - 1];
    const previous = this.undoStack[this.undoStack.length - 2];
    if (previous !== deleted || previous.type !== 'deleteSelection' || last.type !== 'insertText') return;
    this.undoStack.splice(this.undoStack.length - 2, 2, new StudioImeReplacementCommand([previous, last]));
  }
""" + before
if text.count(before)!=1: raise RuntimeError('Pinned history grouping anchor changed')
history.write_text(text.replace(before,after,1))
ime=root/'src/engine/input-handler-text.ts'
text=ime.read_text()
before="export function onCompositionStart(this: any): void {"
if text.count(before)!=1: raise RuntimeError('Pinned composition start changed')
text=text.replace(before,before+"\n  this._studioCompositionDelete = null;",1)
before="    this.deleteSelection();\n  }\n  let basePos = this.cursor.isInHeaderFooter()"
after="""    const previous = this.history.peekUndoTop();
    this.deleteSelection();
    const current = this.history.peekUndoTop();
    if (current !== previous && current?.type === 'deleteSelection') this._studioCompositionDelete = current;
  }
  let basePos = this.cursor.isInHeaderFooter()"""
if text.count(before)!=1: raise RuntimeError('Pinned composition selection changed')
text=text.replace(before,after,1)
before="  // 조합 종료 후 대기 중인 탐색 키 처리 (IME 조합 중 방향키 등)"
after="""  if (finalLength > 0) this.history.groupStudioImeReplacement(this._studioCompositionDelete);
  this._studioCompositionDelete = null;
""" + before
if text.count(before)!=1: raise RuntimeError('Pinned composition end changed')
ime.write_text(text.replace(before,after,1))
