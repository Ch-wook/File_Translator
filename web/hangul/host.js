import { openSignature } from './signature.js';
import { createMobileTools } from './mobile-tools.js';
// Same-origin offline host. Document bytes stay in the application.
const native=window.DocumentStudio,UI=window.StudioEditorUI;
let rulers=false,toolsVisible=true;
const frame=document.getElementById('engine'), status=document.getElementById('status');
const save=document.getElementById('save'), mode=document.getElementById('mode');
const tools=document.getElementById('tools'), groups=document.getElementById('groups');
let readOnlyReason=null,conversionOnly=false;
let editor,format,ready=false,editing=false,pending=false,switching=false,timer,revision=0,savedRevision=0,edited=false,activeGroup='글자';
const panel=document.getElementById('mobile-panel'),dock=document.getElementById('edit-dock');
const undo=document.getElementById('undo'),redo=document.getElementById('redo');
const mobileUI=createMobileTools({groups,tools,panel,getEditor:()=>editor,isEditable:()=>ready&&editing&&!switching,message,
 layoutChanged:view=>{if(ready) requestAnimationFrame(()=>editor.restoreView(view).catch(error=>message(String(error.message),true)));}});
function message(text,error=false){status.textContent=text;status.dataset.error=String(error);document.getElementById('discard').hidden=!error;}
function update(){
 document.body.dataset.state=!ready?'LOADING':readOnlyReason?'READ_ONLY':editing?'EDITING':'READING';
 mode.hidden=Boolean(readOnlyReason)||editing||conversionOnly;
 document.getElementById('readonly-badge').hidden=!readOnlyReason;
 mode.textContent=editing?'보기로':'편집하기';mode.disabled=!ready||pending||switching;
 mode.setAttribute('aria-pressed',String(editing));save.hidden=!editing;save.disabled=!ready||pending||switching;
 groups.hidden=!editing;
 dock.hidden=!editing||!toolsVisible;
 undo.hidden=redo.hidden=!editing;
 undo.disabled=!ready||pending||switching||editor?.composing()||!editor?.enabled('edit:undo');
 redo.disabled=!ready||pending||switching||editor?.composing()||!editor?.enabled('edit:redo');
 const back=document.getElementById('back');back.disabled=pending||switching;back.setAttribute('aria-label',editing?'보기로':'작업 화면');back.title=back.getAttribute('aria-label');
 mobileUI.update();
}
function renderTools(){mobileUI.render();update();}
function toBase64(bytes){let result='';for(let i=0;i<bytes.length;i+=8192)result+=String.fromCharCode(...bytes.subarray(i,i+8192));return btoa(result);}
function persistView(){if(ready)native.saveViewState(JSON.stringify(editor.captureView()));}
async function checkpoint(){
 if(!ready||pending)return false;
 if(!editor.dirty()&&savedRevision===revision)return true;
 if(!editing){message('보기 상태의 변경을 저장하지 않았습니다.',true);return false;}
 if(editor.composing()){clearTimeout(timer);timer=setTimeout(checkpoint,300);return false;}
 pending=true;const saving=revision;update();
 try{
  const {bytes,contentLoss}=await editor.exportDocument(format);
  if(contentLoss.outputFormat!==format||contentLoss.count!==0)throw new Error('문서 요소 보존 검사에 실패했습니다.');
  if(!native.beginWrite(bytes.byteLength))throw new Error('작업 파일을 준비하지 못했습니다.');
  try{
   for(let i=0;i<bytes.length;i+=49152)if(!native.writeChunk(toBase64(bytes.subarray(i,i+49152))))throw new Error('작업 파일 기록에 실패했습니다.');
   const answer=JSON.parse(native.finishWrite(JSON.stringify(contentLoss)));
   if(!answer.ok)throw new Error(answer.error||'저장 검증에 실패했습니다.');
  }catch(error){native.abortWrite();throw error;}
  edited=true;savedRevision=saving;
  if(revision===saving)await editor.acknowledge();
  message('작업 복사본을 저장했습니다. ‘저장 후 닫기’로 원본 저장을 요청할 수 있습니다.');
  return true;
 }catch(error){message(String(error.message||error),true);return false;}
 finally{pending=false;update();if(savedRevision===saving&&revision>savedRevision&&editing){clearTimeout(timer);timer=setTimeout(checkpoint,300);}}
}
async function setEditing(value){
 if(!ready||pending||switching||editing===value||readOnlyReason||conversionOnly)return;
 const view=editor.captureView();
 switching=true;clearTimeout(timer);update();
 try{
  if(value){
   if(!native.setEditing(true))throw new Error('편집을 시작할 수 없습니다.');
   try{await editor.setEditing(true);}catch(error){native.setEditing(false);throw error;}
   editing=true;message('편집 모드 · 문서 본문·표·그림을 직접 선택해 수정하세요.');
  }else{
   await mobileUI.flush();await editor.finishInput();
   if(!await checkpoint())return;
   await editor.setEditing(false);
   if(!native.setEditing(false))throw new Error('보기 모드로 전환하지 못했습니다.');
   editing=false;message('원본 보기 · 편집하기를 선택하면 같은 위치에서 수정할 수 있습니다.');
  }
  // Apply host tool/status layout before restoring the engine's document position.
  // Showing the mobile toolbar resizes the iframe after the engine mode changes.
  update();await editor.restoreView(view);persistView();
 }catch(error){message(String(error.message||error),true);}
 finally{switching=false;update();}
}
async function close(saveOriginal){
 clearTimeout(timer);
 if(!ready){native.closeEditor(false);return;}
 if(pending||switching){message('현재 작업이 끝난 뒤 다시 선택하세요.');return;}
 try{
  if(editing){await mobileUI.flush();await editor.finishInput();}
  if(!await checkpoint())return;
  persistView();native.closeEditor(Boolean(saveOriginal&&edited));
 }catch(error){message(String(error.message||error),true);}
}
async function convertPdf(){
 if(!ready||pending||switching||readOnlyReason)return;
 if(!confirm('전체 페이지를 원본 레이아웃으로 PDF 변환합니다. 한글 PDF는 페이지 이미지 방식이며 텍스트 선택·검색·링크는 보존되지 않습니다. 현재 편집 내용도 포함합니다.'))return;
 try{if(editing){await mobileUI.flush();await editor.finishInput();if(!await checkpoint())return;}native.requestPdf();}
 catch(error){message(error.message,true);}
}
async function writePdf(){
 if(pending||switching){message('현재 저장이 끝난 뒤 PDF 변환을 다시 선택하세요.');return;}
 try{if(editing){await mobileUI.flush();await editor.finishInput();if(!await checkpoint())return;}}
 catch(error){message(error.message,true);return;}
 const restoreEditing=editing,view=editor.captureView();pending=true;switching=true;update();
 status.parentElement.dataset.operation='pdf';let cancelled=false;const cancel=document.createElement('button');cancel.textContent='변환 취소';cancel.onclick=()=>{cancelled=true;};status.after(cancel);
 try{
  // Freeze mutations in the actual engine for a consistent multi-page export.
  if(restoreEditing)await editor.setEditing(false);
  const count=editor.info().pages;if(!native.beginPdf(count))throw Error('PDF를 준비하지 못했습니다.');
  for(let i=0;i<count;i++){
   if(cancelled)throw Error('PDF 변환을 취소했습니다.');
   message('PDF 변환 중 · '+(i+1)+' / '+count+'쪽');await new Promise(r=>setTimeout(r,0));
   const page=editor.pdfPage(i);if(!native.pdfPage(JSON.stringify(page)))throw Error('페이지 변환에 실패했습니다.');
  }
  if(cancelled)throw Error('PDF 변환을 취소했습니다.');
  const result=JSON.parse(native.finishPdf());if(!result.ok)throw Error(result.error||'PDF 검증 실패');message('PDF 변환 완료 · '+count+'쪽');
 }catch(error){native.abortPdf();message(error.message,!cancelled);document.getElementById('discard').hidden=true;}
 finally{
  try{if(restoreEditing)await editor.setEditing(true);}catch(error){message(error.message,true);}
  cancel.remove();delete status.parentElement.dataset.operation;pending=false;switching=false;update();await editor.restoreView(view);
 }
}
window.documentStudioHost={writePdf,convertPdf,signature:()=>openSignature({editor,native,message}),
 changed(){if(!ready||!editing)return;revision++;message('수정됨 · 작업 복사본 저장 대기');clearTimeout(timer);timer=setTimeout(checkpoint,1200);update();},
 toolsChanged(){if(ready)update();},
 checkpoint,
 mobileAction:name=>mobileUI.action(name),
 selectionContext:name=>mobileUI.context(name),
 selectionChanged(){if(ready&&editing)mobileUI.selectionChanged();},
 saveAndClose(){return close(true);},
 returnToWorkspace(){return close(false);},
 async back(){if(editing)await setEditing(false);else await close(false);},
};
UI.iconButton(document.getElementById('back'),'작업 화면','back',()=>window.documentStudioHost.back());
UI.iconButton(undo,'실행 취소','undo',()=>mobileUI.history('undo'));
UI.iconButton(redo,'다시 실행','redo',()=>mobileUI.history('redo'));
UI.iconButton(save,'저장 후 닫기','done',()=>close(true));
const more=UI.iconButton(document.getElementById('more'),'문서 메뉴','more');
UI.popup(more,()=>[
 {label:editing?'읽기 모드로 전환':'편집하기',hidden:Boolean(readOnlyReason)||conversionOnly,disabled:!ready||pending||switching,action:()=>setEditing(!editing)},
 {label:'찾기'+(editing?'·바꾸기':''),disabled:!ready,action:()=>editing?mobileUI.showSearch():editor.command('edit:find')},
 {label:'확대 '+(ready?Math.round(editor.captureView().zoom*100)+'%':''),disabled:!ready,action:()=>editor.command('view:zoom-in')},
 {label:'축소',disabled:!ready,action:()=>editor.command('view:zoom-out')},
 {label:'화면 너비에 맞춤',disabled:!ready,action:()=>editor.command('view:zoom-fit-width')},
 {label:rulers?'눈금자 숨기기':'눈금자 표시',disabled:!ready,action:()=>{rulers=!rulers;editor.setRulers(rulers);}},
 {label:toolsVisible?'도구 접기':'도구 펼치기',hidden:!editing,action:()=>{toolsVisible=!toolsVisible;update();}},
 {label:'서명/필기',hidden:!editing,disabled:pending||switching,action:()=>openSignature({editor,native,message})},
 {label:'PDF로 변환',disabled:!ready||pending||switching||Boolean(readOnlyReason),action:()=>convertPdf()},
 {separator:true},
 {label:pending?'저장 중…':savedRevision<revision?'자동 저장 대기':'작업 복사본 저장됨',disabled:true},
 {label:readOnlyReason||'',hidden:!readOnlyReason,disabled:true},
 {label:'작업 화면으로 돌아가기',disabled:pending,action:()=>close(false)},
 {label:'저장되지 않은 변경 취소',hidden:!editing,action:()=>native.requestDiscard()},
]);
document.getElementById('discard').onclick=()=>native.requestDiscard();
mode.onclick=()=>setEditing(true);
async function start(){
 if(!native){message('앱의 문서 연결을 찾지 못했습니다.',true);return;}
 const meta=JSON.parse(native.describe());format=meta.format;readOnlyReason=meta.readOnlyReason||null;conversionOnly=meta.conversionOnly===true;
 document.getElementById('title').textContent=meta.name;
 for(let attempt=0;attempt<600;attempt++){
  editor=frame.contentWindow?.rhwpStudio?.documentStudio;if(editor)break;
  await new Promise(resolve=>setTimeout(resolve,100));
 }
 if(!editor)throw new Error('한글 엔진을 시작하지 못했습니다.');
 const response=await fetch('/document');if(!response.ok)throw new Error('작업 복사본을 읽지 못했습니다.');
 const info=await editor.open(new Uint8Array(await response.arrayBuffer()),meta.name);
 if(info.format!==format||info.pages<1)throw new Error('원본 문서의 형식 또는 페이지를 확인할 수 없습니다.');
 if(meta.viewState&&Number.isFinite(Number(meta.viewState.zoom)))await editor.restoreView(meta.viewState);
 else await editor.command('view:zoom-fit-width');
 ready=true;renderTools();native.loaded();
 if(meta.conversionOnly)setTimeout(convertPdf,100);
 if(readOnlyReason)message(`${info.pages}쪽 · 읽기 전용 · ${readOnlyReason}`);
 else if(meta.openIntent==='EDIT')await setEditing(true);
 else message(`${info.pages}쪽 · 원본 보기 · 편집하기를 선택해 수정하세요.`);

}
start().catch(error=>{document.body.dataset.state='ERROR';message(String(error.message||error),true);native?.loadFailed(String(error.message||error));});

document.addEventListener('visibilitychange',()=>{if(document.visibilityState==='hidden'){persistView();checkpoint();}});
