// Mobile host integration for the pinned Rhwp engine. Never serializes through plain-text documents.
import { FONT_RULE_WEBFONT_ENTRIES } from './core/font-rule-runtime';
import { loadWebFonts, getWebFontSupplySnapshot } from './core/font-loader';
import { InsertTextCommand, DeleteSelectionCommand, SplitParagraphCommand, SplitParagraphInCellCommand } from './engine/command';
import { prepareRhwpInternalClipboardHtml } from './engine/input-handler-keyboard';
import { navigateToSearchHit } from './ui/find-dialog';
import { studioIsEditing } from './host-readonly';
type Env = { wasm:any; eventBus:any; getInput:()=>any; getCanvas:()=>any; dispatcher:any };
const clone=<T>(v:T):T=>JSON.parse(JSON.stringify(v));
const charKeys=['fontId','fontIds','fontSize','bold','italic','underline','strikethrough','superscript','subscript','textColor','shadeColor','ratios','spacings','charOffsets','relativeSizes','underlineType','underlineColor','underlineShape','strikeColor','strikeShape','emphasisDot','kerning','outlineType','shadowType'];
function picked(value:any,keys:string[]) {return Object.fromEntries(keys.filter(k=>value[k]!==undefined).map(k=>[k,clone(value[k])]));}
function num(value:any,min:number,max:number) {const n=Number(value);if(!Number.isFinite(n)||n<min||n>max)throw new Error('값의 범위를 확인하세요.');return n;}
function ok(value:any) {const result=typeof value==='string'?JSON.parse(value):value;if(result?.ok===false)throw new Error('엔진이 변경을 거절했습니다.');return result;}
export function createStudioMobile(env:Env) {
 const {wasm,eventBus}=env;let epoch=0;let search:any={query:'',hits:[],index:-1,epoch:-1};
 const input=()=>{const ih=env.getInput();if(!ih)throw new Error('문서가 준비되지 않았습니다.');return ih;};
 const composing=()=>Boolean(env.getInput()?.isComposing||env.getInput()?._iosComposing);
 const writable=()=>{if(!studioIsEditing())throw new Error('편집하기를 먼저 선택하세요.');if(composing())throw new Error('한글 조합 입력을 마친 뒤 선택하세요.');};
 const changed=()=>{epoch++;(window.parent as any).documentStudioHost?.toolsChanged();};
 eventBus.on('document-mutated',changed);
 eventBus.on('document-swapped',()=>{epoch++;search={query:'',hits:[],index:-1,epoch:-1};});
 for(const e of ['cursor-format-changed','cursor-para-changed','cursor-cell-changed','cursor-style-changed','cursor-rect-updated','picture-object-selection-changed','cell-selection-phase-changed'])
   eventBus.on(e,()=>{(window.parent as any).documentStudioHost?.toolsChanged();});
 document.addEventListener('compositionstart',()=>setTimeout(()=>(window.parent as any).documentStudioHost?.toolsChanged(),0),true);
 document.addEventListener('compositionend',()=>setTimeout(()=>(window.parent as any).documentStudioHost?.toolsChanged(),0),true);
 function validPos(p:any) {
   if(!p||!Number.isInteger(p.sectionIndex)||!Number.isInteger(p.charOffset)||p.charOffset<0)return false;
   try {
     const length=p.parentParaIndex===undefined?wasm.getParagraphLength(p.sectionIndex,p.paragraphIndex):
       p.cellPath?.length?wasm.getCellParagraphLengthByPath(p.sectionIndex,p.parentParaIndex,JSON.stringify(p.cellPath)):
       wasm.getCellParagraphLength(p.sectionIndex,p.parentParaIndex,p.controlIndex,p.cellIndex,p.cellParaIndex);
     return p.charOffset<=length;
   }catch{return false;}
 }
 function capture() {
   const ih=input(),c=ih.cursor;
   return clone({epoch,pos:ih.getCursorPosition(),range:c.getSelection(),cells:c.captureCellSelection(),
     hf:c.getHeaderFooterSelectionOrdered(),fn:c.getFootnoteSelectionOrdered(),
     object:c.isInPictureObjectSelection()||c.isInTableObjectSelection()});
 }
 function restore(token:any) {
   if(!token)return;
   const ih=input(),c=ih.cursor;
   if(token.epoch!==epoch)throw new Error('문서가 바뀌었습니다. 적용할 글자나 셀을 다시 선택하세요.');
   // Modal focus does not change native HF/note/object selections. Do not reinterpret their coordinate systems.
   if(token.hf||token.fn||token.object)return;
   if(token.cells){if(!c.restoreCellSelection(token.cells))throw new Error('셀 선택 범위를 다시 지정하세요.');}
   else {
     if(!validPos(token.pos)||token.range&&(!validPos(token.range.anchor)||!validPos(token.range.focus)))throw new Error('선택 범위가 유효하지 않습니다.');
     c.exitCellSelectionMode();c.clearSelection();
     if(token.range){c.moveTo(token.range.anchor);c.setAnchor();c.moveTo(token.range.focus);}
     else c.moveTo(token.pos);
   }
   ih.updateCaret(true);
 }
 function state() {
   if(!env.getInput())return {ready:false,epoch};
   const ih=input(),c=ih.cursor,p=ih.getCursorPosition();let cell:any=null;
   if(p.parentParaIndex!==undefined&&!p.isTextBox) {
     try {const path=p.cellPath?.length?JSON.stringify(p.cellPath):null;
       cell={...p,info:path?wasm.getCellInfoByPath(p.sectionIndex,p.parentParaIndex,path):wasm.getCellInfo(p.sectionIndex,p.parentParaIndex,p.controlIndex,p.cellIndex),
         dims:path?wasm.getTableDimensionsByPath(p.sectionIndex,p.parentParaIndex,path):wasm.getTableDimensions(p.sectionIndex,p.parentParaIndex,p.controlIndex),
         props:p.cellPath?.length>1?null:wasm.getCellProperties(p.sectionIndex,p.parentParaIndex,p.controlIndex,p.cellIndex),
         selection:c.getSelectedCellRange()};
     }catch{/* unavailable cells stay disabled */}
   }
   return {ready:true,epoch,composing:composing(),editing:studioIsEditing(),char:ih.getCharProperties(),para:ih.getParaProperties(),selection:ih.hasSelection(),
     cell,object:c.isInPictureObjectSelection(),tableObject:c.isInTableObjectSelection(),style:ih.getCurrentStyleId(),
     canUndo:ih.canUndo(),canRedo:ih.canRedo(),copiedFormat:ih.hasCopiedFormat(),
     scope:ih.isInCellSelectionMode()?'선택한 셀':ih.hasSelection()?'선택한 글자':'커서 이후 입력'};
 }
 function result(){return {selection:capture(),state:state()};}
 function char(props:any,token?:any) {
   writable();restore(token);const ih=input(),mods=picked(props,charKeys);
   if(ih.cursor.isInPictureObjectSelection()||ih.cursor.isInTableObjectSelection())throw new Error('개체 대신 글자를 선택하세요.');
   if(props.fontName) {
     const names=fonts();if(!names.includes(props.fontName))throw new Error('이 앱에 포함된 글꼴을 선택하세요.');
     mods.fontId=wasm.findOrCreateFontId(props.fontName);if(mods.fontId<0)throw new Error('글꼴을 적용할 수 없습니다.');
   }
   if(mods.fontSize!==undefined)mods.fontSize=Math.round(num(mods.fontSize,100,409600));
   for(const [key,min,max] of [['ratios',50,200],['spacings',-50,50],['charOffsets',-100,100]] as const) {
     if(mods[key]){if(!Array.isArray(mods[key])||mods[key].length!==7)throw new Error('언어별 글자 속성이 잘못되었습니다.');mods[key]=mods[key].map((v:any)=>Math.round(num(v,min,max)));}
   }
   for(const k of ['textColor','shadeColor','underlineColor','strikeColor'])if(mods[k]!==undefined&&!/^#[0-9a-f]{6}$/i.test(mods[k]))throw new Error('색상은 #RRGGBB 형식입니다.');
   ih.applyCharFormat(mods);return result();
 }
 function para(props:any,token?:any) {
   writable();restore(token);
   const allowed=['alignment','lineSpacing','lineSpacingType','marginLeft','marginRight','indent','spacingBefore','spacingAfter','headType','numberingId','paraLevel','fillType','fillColor','borderLeft','borderRight','borderTop','borderBottom','keepWithNext'];
   const mods=picked(props,allowed),ih=input();
   if((ih.getCursorPosition().cellPath?.length||0)>1)throw new Error('중첩 표 안의 문단 서식은 현재 지원하지 않습니다.');
   if(mods.lineSpacingType==='Percent')num(mods.lineSpacing,5,500);
   for(const k of ['marginLeft','marginRight','indent','spacingBefore','spacingAfter'])if(mods[k]!==undefined)num(mods[k],k==='indent'?-144000:0,144000);
   if(ih.cursor.isInPictureObjectSelection()||ih.cursor.isInTableObjectSelection())throw new Error('문단이나 셀 안의 글자를 먼저 선택하세요.');
   ih.applyParaPropsAtCursor(mods);return result();
 }
 function fonts() {
   return [...new Set(FONT_RULE_WEBFONT_ENTRIES.filter(f=>!/^https?:|^data:/.test(f.file)&&!/(Cafe24|Happiness)/i.test(f.file)).map(f=>f.name))].sort();
 }
 async function font(name:string,token:any) {
   writable();restore(token);await loadWebFonts([name],undefined,{disableExternalWebFonts:true});
   if(getWebFontSupplySnapshot(name).status!=='loaded')throw new Error('번들 글꼴을 읽지 못했습니다.');
   return char({fontName:name},token);
 }
 function resetChar(token:any) {
   const defaults=wasm.getStyleDetail(0)?.charProps||{};
   return char({...picked(defaults,charKeys),bold:false,italic:false,underline:false,strikethrough:false,superscript:false,subscript:false,
     textColor:defaults.textColor||'#000000',shadeColor:defaults.shadeColor||'#ffffff',ratios:defaults.ratios||Array(7).fill(100),
     spacings:defaults.spacings||Array(7).fill(0),charOffsets:defaults.charOffsets||Array(7).fill(0)},token);
 }
 function resetPara(token:any) {
   const p=wasm.getStyleDetail(0)?.paraProps||{};
   return para({alignment:p.alignment||'left',lineSpacingType:'Percent',lineSpacing:p.lineSpacingType==='Percent'?p.lineSpacing:160,
     marginLeft:Math.round((p.marginLeft||0)*150),marginRight:Math.round((p.marginRight||0)*150),indent:Math.round((p.indent||0)*150),
     spacingBefore:Math.round((p.spacingBefore||0)*75),spacingAfter:Math.round((p.spacingAfter||0)*75),
     headType:'None',numberingId:0,paraLevel:0,fillType:'none',
     borderLeft:{type:0,width:0,color:'#000000'},borderRight:{type:0,width:0,color:'#000000'},borderTop:{type:0,width:0,color:'#000000'},borderBottom:{type:0,width:0,color:'#000000'}},token);
 }
 function dispatch(id:string,params?:any,token?:any) {
   if(!id.startsWith('view:')&&!['edit:copy','edit:select-all','edit:find','edit:find-again'].includes(id))writable();
   if(token)restore(token);
   const answer=env.dispatcher.dispatchWithResult(id,params);if(!answer.ok)throw new Error('현재 선택에서 사용할 수 없는 기능입니다.');
   return result();
 }
 function clipboardPayload() {
   const ih=input(),c=ih.cursor,dt=new DataTransfer();
   if(c.isInTableObjectSelection()) {
     const r=c.getSelectedTableRef();if(!r)throw new Error('표를 선택하세요.');
     wasm.copyControl(r.sec,r.ppi,r.ci);
     const text=wasm.getClipboardText()||'[표]',html=wasm.exportControlHtml(r.sec,r.ppi,r.ci)||'';
     dt.setData('text/plain',text);dt.setData('text/html',prepareRhwpInternalClipboardHtml(ih,html,text));
   } else ih.textarea.dispatchEvent(new ClipboardEvent('copy',{bubbles:true,cancelable:true,clipboardData:dt}));
   const value={text:dt.getData('text/plain'),html:dt.getData('text/html')};
   if(!value.text&&!value.html)throw new Error('복사할 글자나 개체를 선택하세요.');
   return value;
 }
 async function copy(cut=false) {
   if(cut)writable();
   const token=capture(),value=clipboardPayload(),native=(window.parent as any).DocumentStudio;
   if(native?.writeClipboard) {
     if(!native.writeClipboard(JSON.stringify(value)))throw new Error('클립보드에 복사하지 못했습니다. 선택 내용은 삭제하지 않았습니다.');
   }else if(navigator.clipboard?.write&&typeof ClipboardItem!=='undefined'){
     await navigator.clipboard.write([new ClipboardItem({'text/plain':new Blob([value.text],{type:'text/plain'}),'text/html':new Blob([value.html],{type:'text/html'})})]);
   }else await navigator.clipboard.writeText(value.text);
   if(cut){writable();restore(token);input().performDelete();}
   return result();
 }
 function raw(w:any,cmd:any) {try{return cmd.execute(w);}finally{cmd.discard?.(w);}}
 function plainText(text:string) {
   writable();if(!text)return result();
   const ih=input(),c=ih.cursor;
   if(c.isInHeaderFooter()||c.isInFootnote())throw new Error('현재 보조 영역의 텍스트 붙여넣기는 기본 붙여넣기를 사용하세요.');
   if(c.isInPictureObjectSelection()||c.isInTableObjectSelection())throw new Error('글자를 넣을 위치를 먼저 선택하세요.');
   const selection=ih.getSelection(),pos=ih.getCursorPosition();
   ih.flushDeferredPaginationIfNeeded('mobile-paste',true);
   ih.executeOperation({kind:'snapshot',operationType:'mobilePlainPaste',operation:(w:any)=>{
     let at:any={...pos};
     if(ih.hasSelection()&&selection)at=raw(w,new DeleteSelectionCommand(selection.start,selection.end));
     for(const [index,line] of text.replace(/\r\n?/g,'\n').split('\n').entries()){
       if(index)at=raw(w,at.parentParaIndex===undefined?new SplitParagraphCommand(at):new SplitParagraphInCellCommand(at));
       if(line)at=raw(w,new InsertTextCommand(at,line));
     }
     w.flushDeferredPagination();return at;
   }});
   c.clearSelection();ih.updateCaret();return result();
 }
 async function paste(plain=false) {
   writable();const token=capture(),native=(window.parent as any).DocumentStudio;let value:any;
   if(native?.readClipboard)value=JSON.parse(native.readClipboard());
   else if(navigator.clipboard?.read){value={text:'',html:''};for(const item of await navigator.clipboard.read())for(const type of ['text/plain','text/html'])if(item.types.includes(type))value[type==='text/html'?'html':'text']=await(await item.getType(type)).text();}
   else value={text:await navigator.clipboard.readText(),html:''};
   writable();restore(token);
   if(value?.error)throw new Error(value.error);
   if(!value?.text&&!value?.html)throw new Error('텍스트가 있는 클립보드를 선택하세요. 사진은 그림 삽입으로 선택할 수 있습니다.');
   if(plain)return plainText(String(value.text||''));
   const dt=new DataTransfer();dt.setData('text/plain',String(value.text||''));if(value.html)dt.setData('text/html',String(value.html));
   input().textarea.dispatchEvent(new ClipboardEvent('paste',{bubbles:true,cancelable:true,clipboardData:dt}));
   return result();
 }
 function createTable(rows:number,cols:number) {
   writable();rows=Math.floor(num(rows,1,50));cols=Math.floor(num(cols,1,30));if(rows*cols>300)throw new Error('모바일에서는 한 번에 300셀까지 만들 수 있습니다.');
   const ih=input(),pos=ih.getCursorPosition(),c=ih.cursor;
   if(pos.parentParaIndex!==undefined||c.isInHeaderFooter()||c.isInFootnote())throw new Error('본문 위치를 선택하세요.');
   ih.executeOperation({kind:'snapshot',operationType:'mobileCreateTable',operation:(w:any)=>{
     const r=ok(w.createTable(pos.sectionIndex,pos.paragraphIndex,pos.charOffset,rows,cols));
     if(!r.ok)throw new Error('표를 만들지 못했습니다.');
     return {sectionIndex:pos.sectionIndex,paragraphIndex:0,charOffset:0,parentParaIndex:r.paraIdx,controlIndex:r.controlIdx,cellIndex:0,cellParaIndex:0};
   }});c.clearSelection();ih.updateCaret();return result();
 }
 function selectCells(endRow?:number,endCol?:number,all=false) {
   const ih=input(),c=ih.cursor;
   if(!studioIsEditing())throw new Error('편집하기를 먼저 선택하세요.');
   if(!c.isInCellSelectionMode()&&!c.enterCellSelectionMode())throw new Error('표 안의 셀을 먼저 터치하세요.');
   if(all){c.advanceCellSelectionPhase();c.advanceCellSelectionPhase();}
   else if(endRow!==undefined&&endCol!==undefined){const ctx=c.getCellTableContext();num(endRow,0,ctx.rowCount-1);num(endCol,0,ctx.colCount-1);c.setCellSelectionFocus(endRow,endCol);}
   ih.updateCaret(true);eventBus.emit('command-state-changed');return result();
 }
 function cell(props:any,token?:any) {
   writable();restore(token);const ih=input(),p=ih.getCursorPosition(),block=ih.getSelectedCellBlock();
   if(p.parentParaIndex===undefined||p.isTextBox)throw new Error('표 셀을 선택하세요.');
   if(p.cellPath?.length>1)throw new Error('중첩 표 셀의 크기·테두리는 엔진 기본 셀 모양을 사용하세요.');
   const mods=picked(props,['width','height','paddingLeft','paddingRight','paddingTop','paddingBottom','verticalAlign','fillType','fillColor','borderLeft','borderRight','borderTop','borderBottom']);
   for(const k of ['width','height'])if(mods[k]!==undefined)num(mods[k],100,144000);
   for(const k of ['paddingLeft','paddingRight','paddingTop','paddingBottom'])if(mods[k]!==undefined)num(mods[k],0,7200);
   const ids=block?.cellIndices||[p.cellIndex];
   ih.executeOperation({kind:'snapshot',operationType:'mobileCellProperties',operation:(w:any)=>{for(const id of ids)ok(w.setCellProperties(p.sectionIndex,p.parentParaIndex,p.controlIndex,id,mods));return p;}});
   return result();
 }
 function list(kind:string,start=1) {
   writable();const ih=input(),targets=ih.getParaFormatTargetsAtCursor(),pos=ih.getCursorPosition();if(!targets.length)throw new Error('이 영역의 목록은 지원하지 않습니다.');
   num(start,1,999);const formats:any={number:0,roman:2,english:4,korean:8};
   const bullets:any={disc:'●',circle:'○',square:'■',check:'□'};
   if(kind==='none')return para({headType:'None',numberingId:0,paraLevel:0});
   if(kind==='deeper'||kind==='shallower')return para({paraLevel:Math.max(0,Math.min(6,(ih.getParaProperties().paraLevel||0)+(kind==='deeper'?1:-1)))});
   ih.executeOperation({kind:'snapshot',operationType:'mobileList',operation:(w:any)=>{
     const bullet=bullets[kind];if(!bullet&&formats[kind]===undefined)throw new Error('목록 종류를 확인하세요.');
     const id=bullet?w.ensureDefaultBullet(bullet):w.createNumbering(JSON.stringify({levelFormats:Array.from({length:7},(_,i)=>'^'+(i+1)+'.'),numberFormats:Array(7).fill(formats[kind]),startNumber:Math.round(start)}));
     if(id<=0)throw new Error('목록 서식을 만들지 못했습니다.');
     const mods=JSON.stringify({headType:bullet?'Bullet':'Number',numberingId:id,paraLevel:0});
     for(const t of targets)ok(t.kind==='body'?w.applyParaFormat(t.sec,t.para,mods):w.applyParaFormatInCell(t.sec,t.parentPara,t.controlIdx,t.cellIdx,t.cellParaIdx,mods));
     return pos;
   }});return result();
 }
 function hits(query:string,sensitive=false) {
   if(!query||query.length>1000)return [];
   return wasm.searchAllText(query,sensitive,true).filter((h:any)=>h.equationControl===undefined);
 }
 function find(query:string,direction=1,sensitive=false) {
   const found=hits(query,sensitive);const same=search.query===query&&search.epoch===epoch&&search.sensitive===sensitive;
   const index=found.length?((same?search.index:(direction<0?0:-1))+direction+found.length)%found.length:-1;
   search={query,hits:found,index,epoch,sensitive};
   if(index>=0){const h=found[index];navigateToSearchHit(input(),{...h,found:true});}
   return {count:found.length,current:index+1};
 }
 function replace(query:string,text:string,all=false,sensitive=false) {
   writable();if(!query)throw new Error('찾을 내용을 입력하세요.');
   const ih=input(),pos=ih.getCursorPosition();
   if(all)ih.executeOperation({kind:'snapshot',operationType:'mobileReplaceAll',operation:(w:any)=>{const r=ok(w.replaceAll(query,text,sensitive));if(!r.count)return null;const p={...pos};if(p.parentParaIndex===undefined){p.paragraphIndex=Math.min(p.paragraphIndex,w.getParagraphCount(p.sectionIndex)-1);p.charOffset=Math.min(p.charOffset,w.getParagraphLength(p.sectionIndex,p.paragraphIndex));}else p.charOffset=0;return p;}});
   else {
     if(search.query!==query||search.epoch!==epoch||search.index<0)throw new Error('찾기로 바꿀 항목을 먼저 선택하세요.');
     const h=search.hits[search.index];if(h.cellPath?.length>1)throw new Error('중첩 표 안의 개별 바꾸기는 지원하지 않습니다.');
     ih.executeOperation({kind:'snapshot',operationType:'mobileReplaceOne',operation:(w:any)=>{
       ok(h.cellContext?w.replaceTextInCellDeferredPagination(h.sec,h.cellContext.parentPara,h.cellContext.ctrlIdx,h.cellContext.cellIdx,h.cellContext.cellPara,h.charOffset,h.length,text):w.replaceText(h.sec,h.para,h.charOffset,h.length,text));
       return h.cellContext?{sectionIndex:h.sec,paragraphIndex:h.cellContext.parentPara,parentParaIndex:h.cellContext.parentPara,controlIndex:h.cellContext.ctrlIdx,cellIndex:h.cellContext.cellIdx,cellParaIndex:h.cellContext.cellPara,charOffset:h.charOffset+Array.from(text).length}:{sectionIndex:h.sec,paragraphIndex:h.para,charOffset:h.charOffset+Array.from(text).length};
     }});
   }
   search={query:'',hits:[],index:-1,epoch:-1};return {count:hits(query,sensitive).length,...result()};
 }
 return {state,capture,restore,char,para,font,fonts,resetChar,resetPara,dispatch,copy,paste,plainText,createTable,selectCells,cell,list,find,replace,
   count:(q:string,s=false)=>hits(q,s).length,styles:()=>wasm.getStyleList(),
   focus:()=>input().focus(),reveal:()=>input().updateCaret(),
   navigate:(end=false)=>{const ih=input();ih.cursor.clearSelection();if(end)ih.cursor.moveToDocumentEnd();else ih.cursor.moveTo({sectionIndex:0,paragraphIndex:0,charOffset:0});ih.updateCaret();},
 };
}
