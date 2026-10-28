import { createStudioMobile } from './studio-mobile';
import { installStudioTouch } from './studio-touch';
// App-specific adapter appended to the exact pinned Rhwp main.ts.
// Page rendering, editing, pagination and serialization stay in the real engine.
const studioFrame = document.getElementById('scroll-container');
function studioFrameTick(): Promise<void> { return new Promise(resolve => requestAnimationFrame(() => resolve())); }
function studioCaptureView() {
  const viewport = canvasView?.getViewportManager();
  return {zoom:viewport?.getZoom() ?? 1, scrollTop:studioFrame?.scrollTop ?? 0, scrollLeft:studioFrame?.scrollLeft ?? 0};
}
async function studioRestoreView(state: any): Promise<void> {
  if (!state || !canvasView || !studioFrame) return;
  const viewport = canvasView.getViewportManager();
  const zoom = Number(state.zoom);
  if (Number.isFinite(zoom) && zoom >= 0.1 && zoom <= 5 && Math.abs(zoom-viewport.getZoom()) > 0.0001) viewport.setZoom(zoom);
  await studioFrameTick(); await studioFrameTick();
  const top = Math.max(0, Number(state.scrollTop) || 0), left = Math.max(0, Number(state.scrollLeft) || 0);
  viewport.setScrollTop(top); viewport.setScrollLeft(left);
  studioFrame.scrollTop=top; studioFrame.scrollLeft=left;
}
async function studioFinishInput(): Promise<void> {
  if (!studioIsEditing()) return;
  const active = document.activeElement as HTMLElement | null;
  if (active?.matches('[aria-label="문서 편집 입력"]')) active.blur();
  await studioFrameTick(); await studioFrameTick();
  const input = inputHandler as any;
  if (input?.isComposing || input?._iosComposing) throw new Error('한글 조합 입력을 마친 뒤 다시 선택하세요.');
  inputHandler?.flushDeferredPaginationIfNeeded('host-save', true);
}
Object.assign((window as any).rhwpStudio, {
  documentStudio: {
    async open(bytes: Uint8Array, name: string) {
      await initPromise;
      if (!rendererInitialized) throw new Error(rendererInitializationError || '한글 렌더러를 시작하지 못했습니다.');
      studioSetEditing(false);
      await studioDocumentLoad(() => loadBytes(bytes, name, null, undefined, { suppressDialogs:true, skipRecent:true }));
      studioSetEditing(false);
      return {pages:wasm.pageCount, format:wasm.getSourceFormat()};
    },
    async setEditing(value: boolean) {
      const view=studioCaptureView();
      if (!value) { await studioFinishInput(); inputHandler?.deactivate(); }
      studioSetEditing(value === true);
      eventBus.emit('command-state-changed');
      await studioRestoreView(view);
      return studioIsEditing();
    },
    isEditing: studioIsEditing,
    captureView: studioCaptureView,
    restoreView: studioRestoreView,
    finishInput: studioFinishInput,
    composing: () => Boolean((inputHandler as any)?.isComposing || (inputHandler as any)?._iosComposing),
    async exportDocument(format: string) {
      await initPromise;
      if (!studioIsEditing()) throw new Error('편집 모드에서만 변경 내용을 저장할 수 있습니다.');
      if ((inputHandler as any)?.isComposing || (inputHandler as any)?._iosComposing) throw new Error('한글 조합 입력이 진행 중입니다.');
      inputHandler?.flushDeferredPaginationIfNeeded('host-save', true);
      if (wasm.getSourceFormat() !== format) throw new Error('원본 형식과 편집 형식이 다릅니다.');
      const artifact=format==='hwpx' ? wasm.exportHwpxWithReport() : wasm.exportHwpWithReport();
      if (artifact.contentLoss.count!==0) throw new Error(`보존할 수 없는 문서 요소 ${artifact.contentLoss.count}개가 감지되어 저장을 중단했습니다.`);
      return artifact;
    },
    async placeSignature(url: string, width: number, height: number, token: any) {
      if(!studioIsEditing())throw new Error('편집하기를 먼저 선택하세요.');
      await studioFinishInput();studioMobile.restore(token);(inputHandler as any).cursor.clearSelection();
      if(!url.startsWith('data:image/png;base64,')||url.length>4*1024*1024)throw new Error('서명 이미지가 올바르지 않습니다.');
      const bytes=Uint8Array.from(atob(url.split(',')[1]),c=>c.charCodeAt(0));
      inputHandler!.enterImagePlacementMode(bytes,'png',width,height,'자필서명.png');
    },
    pdfPage(index: number) {
      if(!Number.isInteger(index)||index<0||index>=wasm.pageCount)throw new Error('잘못된 페이지입니다.');
      const info=wasm.getPageInfo(index),scale=Math.min(2,Math.sqrt(10000000/(info.width*info.height)));
      const canvas=document.createElement('canvas');canvas.width=Math.ceil(info.width*scale);canvas.height=Math.ceil(info.height*scale);
      wasm.renderPageToCanvas(index,canvas,scale);
      return {width:info.width*.75,height:info.height*.75,png:canvas.toDataURL('image/png').split(',')[1]};
    },
    dirty: () => documentState.isDirty(),
    acknowledge: () => completeHostSave(),
    info: () => ({pages:wasm.pageCount, format:wasm.getSourceFormat()}),
    tools: () => ({inTable:inputHandler?.isInTable() ?? false, object:inputHandler?.isInPictureObjectSelection() ?? false}),
    enabled: (id: string) => dispatcher.isEnabled(id),
    command: (id: string) => dispatcher.dispatch(id),
    setRulers(value: boolean) {
      const view=studioCaptureView();
      document.documentElement.dataset.studioRulers=String(value);
      window.dispatchEvent(new Event('resize'));
      requestAnimationFrame(()=>{(inputHandler as any)?.updateCaret?.();void studioRestoreView(view);});
    },
  },
});
const nativeFileCommands = new Set(['file:open','file:new-doc','file:save-as','file:save-as-hwp','file:save-as-hwpx','file:export-doc','file:export-html','file:print','file:print-to-pdf']);
document.addEventListener('click', e => {
  const command=(e.target as Element)?.closest?.('[data-cmd]')?.getAttribute('data-cmd');
  if (command==='file:save') {
    e.preventDefault(); e.stopImmediatePropagation();
    if(studioIsEditing()) (window.parent as any).documentStudioHost?.saveAndClose();
  } else if (command && (nativeFileCommands.has(command) || !studioMayCommand(command))) {
    e.preventDefault(); e.stopImmediatePropagation();
  }
}, true);
document.addEventListener('keydown', e => {
  if ((e.ctrlKey || e.metaKey) && (e.code==='KeyS' || ['s','ㄴ'].includes(e.key.toLowerCase()))) {
    e.preventDefault(); e.stopImmediatePropagation();
    if(studioIsEditing()) (window.parent as any).documentStudioHost?.saveAndClose();
  }
}, true);
document.addEventListener('drop', e => {
  if (!studioIsEditing() || (e.dataTransfer?.files.length && Array.from(e.dataTransfer.files).some(f => /\.(hwp|hwpx|hml)$/i.test(f.name)))) {
    e.preventDefault(); e.stopImmediatePropagation();
  }
}, true);
let studioChangeQueued=false;
function studioNotifyChanged() {
  if(!studioIsEditing()||studioChangeQueued)return;
  studioChangeQueued=true;
  queueMicrotask(()=>{studioChangeQueued=false;if(studioIsEditing()) (window.parent as any).documentStudioHost?.changed();});
}
eventBus.on('document-agent-changed',studioNotifyChanged);
eventBus.on('document-mutated',studioNotifyChanged);
eventBus.on('command-state-changed', () => (window.parent as any).documentStudioHost?.toolsChanged());
const nativeStyle=document.createElement('style');
nativeStyle.textContent=`
 #menu-bar,#icon-toolbar,#style-bar,#status-bar {display:none!important}
 html:not([data-studio-rulers="true"]) #h-ruler,html:not([data-studio-rulers="true"]) #v-ruler,html:not([data-studio-rulers="true"]) #ruler-corner {display:none!important}
 html:not([data-studio-rulers="true"]) #scroll-container {grid-column:2;grid-row:2}
 html:not([data-studio-rulers="true"]) #editor-area {grid-template-columns:0px minmax(0,1fr)!important;grid-template-rows:0px minmax(0,1fr)!important}
 html[data-studio-mode="view"] .caret,html[data-studio-mode="view"] .caret-composition,
 html[data-studio-mode="view"] .table-object-layer,html[data-studio-mode="view"] .table-resize-layer,
 html[data-studio-mode="view"] .hf-edit-surface-layer {display:none!important;pointer-events:none!important}
 html[data-studio-mode="view"] #h-ruler,html[data-studio-mode="view"] #v-ruler {pointer-events:none!important}
 [data-cmd="file:open"],[data-cmd="file:new-doc"],[data-recent] {display:none!important}
`;
document.head.appendChild(nativeStyle);

const studioMobile=createStudioMobile({wasm,eventBus,getInput:()=>inputHandler,getCanvas:()=>canvasView,dispatcher});
Object.assign((window as any).rhwpStudio.documentStudio,{mobile:studioMobile});
const studioTouch=installStudioTouch(()=>inputHandler,eventBus);
eventBus.on('command-state-changed',()=>studioTouch.refresh());
const mobileStyle=document.createElement('style');
mobileStyle.textContent=`
 @media(max-width:700px) {
  .modal-overlay{align-items:flex-end!important;padding:0!important}
  .dialog-wrap{width:100%!important;max-width:100vw!important;max-height:80dvh!important;border-radius:14px 14px 0 0!important;margin:0!important}
  .dialog-body{max-height:60dvh;overflow:auto!important;min-width:0!important}
  .dialog-wrap button,.dialog-wrap input,.dialog-wrap select,.find-dialog button,.find-dialog input{min-height:44px!important;font-size:16px!important}
  .dialog-wrap input[type=checkbox],.dialog-wrap input[type=radio]{min-height:24px!important;min-width:24px!important}
  .find-dialog{left:0!important;right:0!important;top:0!important;max-width:100%!important;box-sizing:border-box!important;width:100%!important}
  .find-dialog-row{flex-wrap:wrap!important}.find-dialog-input{min-width:80px!important;flex:1}
 }
`;
document.head.append(mobileStyle);

for(const name of ['pointerup','keyup','input','compositionend'])document.addEventListener(name,(event:Event)=>{
  const target=event.target as Element|null;
  if(!event.isTrusted||!target?.closest?.('#scroll-container,#studio-touch-layer,[aria-label="문서 편집 입력"]'))return;
  requestAnimationFrame(()=>(window.parent as any).documentStudioHost?.selectionChanged?.());
},true);
