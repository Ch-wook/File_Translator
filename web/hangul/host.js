import { createMobileTools } from './mobile-tools.js';
// Same-origin offline host. Document bytes stay in the application.
const native=window.DocumentStudio;
const frame=document.getElementById('engine'), status=document.getElementById('status');
const save=document.getElementById('save'), mode=document.getElementById('mode');
const tools=document.getElementById('tools'), groups=document.getElementById('groups');
let editor,format,ready=false,editing=false,pending=false,switching=false,timer,revision=0,savedRevision=0,edited=false,activeGroup='글자';
const panel=document.getElementById('mobile-panel'),dock=document.getElementById('edit-dock');
const mobileUI=createMobileTools({groups,tools,panel,getEditor:()=>editor,isEditable:()=>ready&&editing&&!switching,message,
 layoutChanged:view=>{if(ready) requestAnimationFrame(()=>editor.restoreView(view).catch(error=>message(String(error.message),true)));}});
function message(text,error=false){status.textContent=text;status.dataset.error=String(error);document.getElementById('discard').hidden=!error;}
function update(){
 document.body.dataset.state=!ready?'LOADING':editing?'EDITING':'READING';
 mode.textContent=editing?'보기로':'편집하기';mode.disabled=!ready||pending||switching;
 mode.setAttribute('aria-pressed',String(editing));save.hidden=!editing;save.disabled=!ready||pending||switching;
 groups.hidden=!editing;tools.hidden=!editing||document.getElementById('tool-toggle').getAttribute('aria-expanded')==='false';
 document.getElementById('tool-toggle').hidden=!editing;
 dock.hidden=!editing;mobileUI.update();
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
 if(!ready||pending||switching||editing===value)return;
 const view=editor.captureView();
 switching=true;clearTimeout(timer);update();
 try{
  if(value){
   if(!native.setEditing(true))throw new Error('편집을 시작할 수 없습니다.');
   try{await editor.setEditing(true);}catch(error){native.setEditing(false);throw error;}
   editing=true;message('편집 모드 · 문서 본문·표·그림을 직접 선택해 수정하세요.');
  }else{
   await editor.finishInput();
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
  if(editing)await editor.finishInput();
  if(!await checkpoint())return;
  persistView();native.closeEditor(Boolean(saveOriginal&&edited));
 }catch(error){message(String(error.message||error),true);}
}
window.documentStudioHost={
 changed(){if(!ready||!editing)return;revision++;message('수정됨 · 작업 복사본 저장 대기');clearTimeout(timer);timer=setTimeout(checkpoint,1200);update();},
 toolsChanged(){if(ready)update();},
 checkpoint,
 mobileAction:name=>mobileUI.action(name),
 selectionContext:name=>mobileUI.context(name),
 saveAndClose(){return close(true);},
 returnToWorkspace(){return close(false);},
 async back(){if(editing)await setEditing(false);else await close(false);},
};
document.getElementById('back').onclick=()=>window.documentStudioHost.back();
document.getElementById('discard').onclick=()=>native.requestDiscard();
document.getElementById('tool-toggle').onclick=()=>{
 const button=document.getElementById('tool-toggle'),expanded=button.getAttribute('aria-expanded')!=='true';
 const view=editor.captureView();button.setAttribute('aria-expanded',String(expanded));button.textContent=expanded?'도구 접기':'도구 펼치기';panel.hidden=!expanded;update();editor.restoreView(view);
};
document.getElementById('find').onclick=()=>{if(editing)mobileUI.showSearch();else editor?.command('edit:find');};
document.getElementById('zoom-out').onclick=()=>editor?.command('view:zoom-out');
document.getElementById('zoom-in').onclick=()=>editor?.command('view:zoom-in');
mode.onclick=()=>setEditing(!editing);save.onclick=()=>close(true);
async function start(){
 if(!native){message('앱의 문서 연결을 찾지 못했습니다.',true);return;}
 const meta=JSON.parse(native.describe());format=meta.format;
 document.getElementById('title').textContent=meta.name;
 for(let attempt=0;attempt<600;attempt++){
  editor=frame.contentWindow?.rhwpStudio?.documentStudio;if(editor)break;
  await new Promise(resolve=>setTimeout(resolve,100));
 }
 if(!editor)throw new Error('한글 엔진을 시작하지 못했습니다.');
 const response=await fetch('/document');if(!response.ok)throw new Error('작업 복사본을 읽지 못했습니다.');
 const info=await editor.open(new Uint8Array(await response.arrayBuffer()),meta.name);
 if(info.format!==format||info.pages<1)throw new Error('원본 문서의 형식 또는 페이지를 확인할 수 없습니다.');
 await editor.restoreView(meta.viewState);
 ready=true;renderTools();native.loaded();
 if(meta.openIntent==='EDIT')await setEditing(true);
 else message(`${info.pages}쪽 · 원본 보기 · 편집하기를 선택해 수정하세요.`);
 for(const id of ['find','zoom-out','zoom-in'])document.getElementById(id).disabled=false;
}
start().catch(error=>{document.body.dataset.state='ERROR';message(String(error.message||error),true);native?.loadFailed(String(error.message||error));});

document.addEventListener('visibilitychange',()=>{if(document.visibilityState==='hidden'){persistView();checkpoint();}});
