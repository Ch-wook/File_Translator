// App-specific adapter appended to pinned rhwp-studio main.ts at build time.
// Rendering, hit testing, editing, pagination and serialization remain in rhwp.
Object.assign((window as any).rhwpStudio, {
  documentStudio: {
    async open(bytes: Uint8Array, name: string) {
      await initPromise;
      if (!rendererInitialized) throw new Error(rendererInitializationError || '한글 렌더러를 시작하지 못했습니다.');
      await loadBytes(bytes, name, null, undefined, { suppressDialogs: true, skipRecent: true });
      return { pages: wasm.pageCount, format: wasm.getSourceFormat() };
    },
    async exportDocument(format: string) {
      await initPromise;
      if (wasm.getSourceFormat() !== format) throw new Error('원본 형식과 편집 형식이 다릅니다.');
      const artifact = format === 'hwpx' ? wasm.exportHwpxWithReport() : wasm.exportHwpWithReport();
      if (artifact.contentLoss.count !== 0) throw new Error(`보존할 수 없는 문서 요소 ${artifact.contentLoss.count}개가 감지되어 저장을 중단했습니다.`);
      return artifact;
    },
    dirty: () => documentState.isDirty(),
    acknowledge: () => completeHostSave(),
    info: () => ({ pages: wasm.pageCount, format: wasm.getSourceFormat() }),
  },
});
// The host owns document identity, SAF writes and recovery. Keep the engine's
// document editing commands; route its file actions through the native host.
const nativeFileCommands = new Set(['file:open','file:new-doc','file:save-as','file:save-as-hwp','file:save-as-hwpx','file:export-doc','file:export-html','file:print','file:print-to-pdf']);
document.addEventListener('click', e => {
  const command = (e.target as Element)?.closest?.('[data-cmd]')?.getAttribute('data-cmd');
  if (command === 'file:save') {
    e.preventDefault(); e.stopImmediatePropagation();
    (window.parent as any).documentStudioHost?.saveAndClose();
  } else if (command && nativeFileCommands.has(command)) {
    e.preventDefault(); e.stopImmediatePropagation();
  }
}, true);
document.addEventListener('keydown', e => {
  if ((e.ctrlKey || e.metaKey) && (e.code === 'KeyS' || ['s','ㄴ'].includes(e.key.toLowerCase()))) {
    e.preventDefault(); e.stopImmediatePropagation();
    (window.parent as any).documentStudioHost?.saveAndClose();
  }
}, true);
document.addEventListener('drop', e => {
  if (e.dataTransfer?.files.length && Array.from(e.dataTransfer.files).some(f => /\.(hwp|hwpx|hml)$/i.test(f.name))) {
    e.preventDefault(); e.stopImmediatePropagation();
  }
}, true);
eventBus.on('document-agent-changed', () => (window.parent as any).documentStudioHost?.changed());
const nativeStyle = document.createElement('style');
nativeStyle.textContent = '[data-cmd="file:open"],[data-cmd="file:new-doc"],[data-cmd="file:save-as"],[data-cmd="file:save-as-hwp"],[data-cmd="file:save-as-hwpx"],[data-cmd="file:export-doc"],[data-cmd="file:export-html"],[data-cmd="file:print"],[data-cmd="file:print-to-pdf"],[data-recent]{display:none!important}';
document.head.appendChild(nativeStyle);
