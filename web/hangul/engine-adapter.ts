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
    dirty: () => documentState.isDirty(),
    acknowledge: () => completeHostSave(),
    info: () => ({pages:wasm.pageCount, format:wasm.getSourceFormat()}),
    tools: () => ({inTable:inputHandler?.isInTable() ?? false, object:inputHandler?.isInPictureObjectSelection() ?? false}),
    enabled: (id: string) => dispatcher.isEnabled(id),
    command: (id: string) => dispatcher.dispatch(id),
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
eventBus.on('document-agent-changed', () => {
  if(studioIsEditing()) (window.parent as any).documentStudioHost?.changed();
});
eventBus.on('command-state-changed', () => (window.parent as any).documentStudioHost?.toolsChanged());
const nativeStyle=document.createElement('style');
nativeStyle.textContent=`
 #menu-bar,#icon-toolbar,#style-bar {display:none!important}
 html[data-studio-mode="view"] .caret,html[data-studio-mode="view"] .caret-composition,
 html[data-studio-mode="view"] .table-object-layer,html[data-studio-mode="view"] .table-resize-layer,
 html[data-studio-mode="view"] .hf-edit-surface-layer {display:none!important;pointer-events:none!important}
 html[data-studio-mode="view"] #h-ruler,html[data-studio-mode="view"] #v-ruler {pointer-events:none!important}
 [data-cmd="file:open"],[data-cmd="file:new-doc"],[data-recent] {display:none!important}
`;
document.head.appendChild(nativeStyle);
