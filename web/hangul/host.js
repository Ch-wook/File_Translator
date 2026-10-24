// Same-origin offline bridge. No document bytes leave the application.
const native=window.DocumentStudio;
const frame=document.getElementById('engine');
const status=document.getElementById('status');
const save=document.getElementById('save');
let editor,format,ready=false,pending=false,timer,revision=0,savedRevision=0;
function message(text,error=false){status.textContent=text;status.dataset.error=String(error);document.getElementById('discard').hidden=!error;}
function toBase64(bytes){let result='';for(let i=0;i<bytes.length;i+=8192) result+=String.fromCharCode(...bytes.subarray(i,i+8192));return btoa(result);}
async function checkpoint(){
  if(!ready || pending) return false;
  if(!editor.dirty() && savedRevision===revision) return true;
  pending=true;const saving=revision;save.disabled=true;
  try {
    const {bytes,contentLoss}=await editor.exportDocument(format);
    if(contentLoss.outputFormat!==format || contentLoss.count!==0) throw new Error('문서 요소 보존 검사에 실패했습니다.');
    if(!native.beginWrite(bytes.byteLength)) throw new Error('작업 파일을 준비하지 못했습니다.');
    try {
      for(let i=0;i<bytes.length;i+=49152) if(!native.writeChunk(toBase64(bytes.subarray(i,i+49152)))) throw new Error('작업 파일 기록에 실패했습니다.');
      const answer=JSON.parse(native.finishWrite(JSON.stringify(contentLoss)));
      if(!answer.ok) throw new Error(answer.error || '저장 검증에 실패했습니다.');
    } catch(error){native.abortWrite();throw error;}
    savedRevision=saving;
    if(revision===saving) await editor.acknowledge();
    message('작업 복구본을 저장했습니다. 원본 파일 반영은 ‘저장 후 닫기’를 사용하세요.');
    return true;
  } catch(error){message(String(error.message || error),true);return false;}
  finally {pending=false;save.disabled=!ready;}
}
async function close(saveOriginal){
  clearTimeout(timer);
  if(!ready){native.closeEditor(false);return;}
  if(pending){message('저장 작업이 끝난 뒤 다시 눌러 주세요.');return;}
  if(await checkpoint()) native.closeEditor(saveOriginal);
}
window.documentStudioHost={
  changed(){if(!ready)return;revision++;message('수정됨 · 복구본 자동 저장 대기');clearTimeout(timer);timer=setTimeout(checkpoint,700);},
  saveAndClose(){return close(true);},
  returnToWorkspace(){return close(false);},
};
document.getElementById('back').onclick=()=>close(false);
document.getElementById('discard').onclick=()=>native.requestDiscard();
save.onclick=()=>close(true);
async function start(){
  if(!native){message('앱의 문서 연결을 찾지 못했습니다.',true);return;}
  const meta=JSON.parse(native.describe());format=meta.format;
  document.getElementById('title').textContent=meta.name;
  for(let attempt=0;attempt<600;attempt++){
    editor=frame.contentWindow?.rhwpStudio?.documentStudio;
    if(editor)break;
    await new Promise(resolve=>setTimeout(resolve,100));
  }
  if(!editor)throw new Error('한글 엔진을 시작하지 못했습니다.');
  const response=await fetch('/document');if(!response.ok)throw new Error('작업 복사본을 읽지 못했습니다.');
  const bytes=new Uint8Array(await response.arrayBuffer());
  const info=await editor.open(bytes,meta.name);
  if(info.format!==format || info.pages<1)throw new Error('원본 문서의 형식 또는 페이지를 확인할 수 없습니다.');
  ready=true;save.disabled=false;native.loaded();
  message(`${info.pages}쪽 · 원본 문서 위에서 클릭해 편집 · 없는 글꼴은 대체 글꼴로 표시됩니다.`);
}
start().catch(error=>{message(String(error.message || error),true);native?.loadFailed(String(error.message || error));});
