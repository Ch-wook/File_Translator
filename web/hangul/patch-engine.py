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

table=root/'src/command/commands/table.ts'
text=table.read_text()
text="import { studioTablePosition } from '../../studio-table-position';\n"+text
operations=[
    ("wasm.deleteTableRow(pos.sectionIndex, pos.parentParaIndex!, pos.controlIndex!, cellInfo.row);", "cellInfo.row", "cellInfo.col", False),
    ("wasm.deleteTableColumn(pos.sectionIndex, pos.parentParaIndex!, pos.controlIndex!, cellInfo.col);", "cellInfo.row", "cellInfo.col", False),
    ("wasm.insertTableRow(pos.sectionIndex, pos.parentParaIndex!, pos.controlIndex!, cellInfo.row, false);", "cellInfo.row+1", "cellInfo.col", True),
    ("wasm.insertTableRow(pos.sectionIndex, pos.parentParaIndex!, pos.controlIndex!, cellInfo.row, true);", "cellInfo.row", "cellInfo.col", True),
    ("wasm.insertTableColumn(pos.sectionIndex, pos.parentParaIndex!, pos.controlIndex!, cellInfo.col, false);", "cellInfo.row", "cellInfo.col+1", True),
    ("wasm.insertTableColumn(pos.sectionIndex, pos.parentParaIndex!, pos.controlIndex!, cellInfo.col, true);", "cellInfo.row", "cellInfo.col", True),
]
for statement,row,col,keep in operations:
    before=statement+"\n          return pos;"
    after="const result = "+statement+"\n          if (!result.ok) throw new Error('표 변경을 적용하지 못했습니다.');\n          const next = studioTablePosition(wasm,pos,"+row+","+col+","+str(keep).lower()+");\n          (ih as any).cursor.moveTo(next);\n          (ih as any).cursor.clearSelection();(ih as any).cursor.exitCellSelectionMode();(ih as any).cellSelectionRenderer?.clear();\n          return next;"
    if text.count(before)!=1: raise RuntimeError('Pinned table position source changed: '+before)
    text=text.replace(before,after,1)
table.write_text(text)

# Direct text commits (including Android commitText without a composition event) use the same grouping.
text=ime.read_text()
before="  const hadSelection = this.cursor.hasSelection();"
after=before+"\n  const studioBeforeDirect = this.history.peekUndoTop();\n  let studioDeletedDirect = null;"
if text.count(before)!=1: raise RuntimeError('Pinned direct input selection anchor changed')
text=text.replace(before,after,1)
before="    this.deleteSelection();\n    insertPos = this.prepareClickHereInputPosition?.() ?? this.cursor.getPosition();"
after="    this.deleteSelection();\n    if (this.history.peekUndoTop() !== studioBeforeDirect) studioDeletedDirect = this.history.peekUndoTop();\n    insertPos = this.prepareClickHereInputPosition?.() ?? this.cursor.getPosition();"
if text.count(before)!=1: raise RuntimeError('Pinned direct input delete changed')
text=text.replace(before,after,1)
before="  if (refreshClickHereGuide) {\n    this.refreshClickHereAfterFirstInput?.();\n  }\n}"
after="  if (studioDeletedDirect) this.history.groupStudioImeReplacement(studioDeletedDirect);\n"+before
if text.count(before)!=1: raise RuntimeError('Pinned direct input insert changed')
ime.write_text(text.replace(before,after,1))

# Surface table failures to the mobile host instead of reporting a successful click.
text=table.read_text()
before="try { fn(); } catch (e) { console.error(\x60[table] \x24{label} 실패:\x60, e); }"
after="try { fn(); } catch (e) { console.error(\x60[table] \x24{label} 실패:\x60, e); throw e; }"
if text.count(before)!=1: raise RuntimeError('Pinned table error reporting changed')
table.write_text(text.replace(before,after,1))

# Arrow keys collapse an existing text range before moving again.
keyboard=root/'src/engine/input-handler-keyboard.ts'
text=keyboard.read_text()
before="""      if (e.shiftKey) {
        this.cursor.setAnchor();
      } else {
        this.cursor.clearSelection();
      }
      if (!e.shiftKey && moveH === 1 && this.tryEnterExitedFieldStart?.()) {"""
after="""      const studioSelection = !e.shiftKey && moveH !== null ? this.getNonEmptySelection?.() : null;
      if (studioSelection) {
        this.cursor.clearSelection();
        this.cursor.moveTo(moveH! < 0 ? studioSelection.start : studioSelection.end);
        this.updateCaret();
        break;
      }
""" + before
if text.count(before)!=1: raise RuntimeError('Pinned arrow selection source changed')
keyboard.write_text(text.replace(before,after,1))
