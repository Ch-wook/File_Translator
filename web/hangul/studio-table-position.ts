import type { DocumentPosition } from './core/types';
/** Pick a valid nearby cell after row/column IDs have changed; keep the original text cursor on inserts. */
export function studioTablePosition(wasm:any,pos:DocumentPosition,row:number,col:number,keepText=false):DocumentPosition {
 const sec=pos.sectionIndex,parent=pos.parentParaIndex!,control=pos.controlIndex!;
 const dims=wasm.getTableDimensions(sec,parent,control);
 row=Math.max(0,Math.min(row,dims.rowCount-1));col=Math.max(0,Math.min(col,dims.colCount-1));
 for(let cell=0;cell<dims.cellCount;cell++){
   const info=wasm.getCellInfo(sec,parent,control,cell);
   if(row<info.row||row>=info.row+info.rowSpan||col<info.col||col>=info.col+info.colSpan)continue;
   const para=keepText?Math.min(pos.cellParaIndex||0,wasm.getCellParagraphCount(sec,parent,control,cell)-1):0;
   const charOffset=keepText?Math.min(pos.charOffset,wasm.getCellParagraphLength(sec,parent,control,cell,para)):0;
   return {sectionIndex:sec,paragraphIndex:para,parentParaIndex:parent,controlIndex:control,cellIndex:cell,cellParaIndex:para,charOffset,
     cellPath:[{controlIndex:control,cellIndex:cell,cellParaIndex:para}]};
 }
 throw new Error('변경한 표에서 유효한 셀을 찾지 못했습니다.');
}
