// Same-origin offline host. Document bytes stay in the application.
const native=window.DocumentStudio;
const frame=document.getElementById('engine'), status=document.getElementById('status');
const save=document.getElementById('save'), mode=document.getElementById('mode');
const tools=document.getElementById('tools'), groups=document.getElementById('groups');
let editor,format,ready=false,editing=false,pending=false,switching=false,timer,revision=0,savedRevision=0,edited=false,activeGroup='글자';
const commandGroups={
 '글자':[['실행 취소','edit:undo'],['다시 실행','edit:redo'],['굵게','format:bold'],['기울임','format:italic'],['밑줄','format:underline'],['글자 모양','format:char-shape']],
 '문단':[['왼쪽','format:align-left'],['가운데','format:align-center'],['오른쪽','format:align-right'],['양쪽','format:align-justify'],['문단 모양','format:para-shape']],
 '표':[['표 만들기','table:create'],['위에 행','table:insert-row-above'],['아래에 행','table:insert-row-below'],['왼쪽 열','table:insert-col-left'],['오른쪽 열','table:insert-col-right'],['셀 모양','table:cell-props'],['셀 합치기','table:cell-merge']],
 '그림':[['그림 삽입','insert:image'],['그림 속성','insert:picture-props'],['맨 앞으로','insert:arrange-front'],['맨 뒤로','insert:arrange-back'],['그림 삭제','insert:picture-delete']],
 '쪽':[['편집 용지','page:setup'],['쪽 나누기','page:break'],['다단 설정','page:col-settings']]
};
function message(text,error=false){status.textContent=text;status.dataset.error=String(error);document.getElementById('discard').hidden=!error;}
function update(){
 document.body.dataset.state=!ready?'LOADING':editing?'EDITING':'READING';
 mode.textContent=editing?'보기로':'편집하기';mode.disabled=!ready||pending||switching;
 mode.setAttribute('aria-pressed',String(editing));save.hidden=!editing;save.disabled=!ready||pending||switching;
 groups.hidden=!editing;tools.hidden=!editing||document.getElementById('tool-toggle').getAttribute('aria-expanded')==='false';
 document.getElementById('tool-toggle').hidden=!editing;
 tools.querySelectorAll('[data-command]').forEach(button=>{button.disabled=!editing||pending||switching||!editor?.enabled(button.dataset.command);});
}
function renderTools(){
 groups.replaceChildren();tools.replaceChildren();
 for(const name of Object.keys(commandGroups)){
  const button=document.createElement('button');button.textContent=name;button.setAttribute('aria-selected',String(name===activeGroup));
  button.onmousedown=e=>e.preventDefault();button.onclick=()=>{activeGroup=name;renderTools();};groups.append(button);
 }
 for(const [label,command] of commandGroups[activeGroup]){
  const button=document.createElement('button');button.textContent=label;button.dataset.command=command;
  button.onmousedown=e=>e.preventDefault();button.onclick=()=>{if(!button.disabled) editor.command(command);update();};tools.append(button);
 }
 update();
}
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
 finally{pending=false;update();}
}
async function setEditing(value){
 if(!ready||pending||switching||editing===value)return;
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
  persistView();
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
 changed(){if(!ready||!editing)return;revision++;message('수정됨 · 작업 복사본 저장 대기');clearTimeout(timer);timer=setTimeout(checkpoint,700);update();},
 toolsChanged(){if(ready)update();},
 saveAndClose(){return close(true);},
 returnToWorkspace(){return close(false);},
 async back(){if(editing)await setEditing(false);else await close(false);},
};
document.getElementById('back').onclick=()=>window.documentStudioHost.back();
document.getElementById('discard').onclick=()=>native.requestDiscard();
document.getElementById('tool-toggle').onclick=()=>{
 const button=document.getElementById('tool-toggle'),expanded=button.getAttribute('aria-expanded')!=='true';
 button.setAttribute('aria-expanded',String(expanded));button.textContent=expanded?'도구 접기':'도구 펼치기';update();
};
document.getElementById('find').onclick=()=>editor?.command('edit:find');
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
 ready=true;native.loaded();renderTools();
 if(meta.openIntent==='EDIT')await setEditing(true);
 else message(`${info.pages}쪽 · 원본 보기 · 편집하기를 선택해 수정하세요.`);
 for(const id of ['find','zoom-out','zoom-in'])document.getElementById(id).disabled=false;
}
start().catch(error=>{document.body.dataset.state='ERROR';message(String(error.message||error),true);native?.loadFailed(String(error.message||error));});
