// Pinned Rhwp host policy. Unknown bridge methods and commands are blocked in VIEW.
// UI visibility is secondary: mutations are stopped before entering the WASM bridge.
let editing = false;
let documentLoads = 0;
export const studioMayWrite = (): boolean => editing || documentLoads > 0;
export const studioIsEditing = (): boolean => editing;
export function studioSetEditing(value: boolean): void {
  editing = value === true;
  document.documentElement.dataset.studioMode = editing ? 'edit' : 'view';
  document.querySelectorAll('[aria-label="문서 편집 입력"]').forEach(element => {
    if (element instanceof HTMLTextAreaElement) element.readOnly = !editing;
    else (element as HTMLElement).contentEditable = editing ? 'true' : 'false';
    if (!editing) (element as HTMLElement).blur();
  });
}
export async function studioDocumentLoad<T>(work: () => Promise<T>): Promise<T> {
  documentLoads++;
  try { return await work(); } finally { documentLoads--; }
}
const readMethods = new Set(["beginDeferredPagination","borrowDocumentHandle","cancelDeferredPagination","canvasMetricDocument","clearLayerResourceCache","copyControl","copySelection","copySelectionInCell","copySelectionInCellByPath","copySelectionInHeaderFooter","discardDeleteFragment","discardPictureTransform","discardSectionRaw","discardSnapshot","exportControlHtml","exportHml","exportHwp","exportHwpVerify","exportHwpWithPassword","exportHwpWithPasswordAndReport","exportHwpWithReport","exportHwpx","exportHwpxWithPassword","exportHwpxWithPasswordAndReport","exportHwpxWithReport","exportSelectionHtml","exportSelectionInCellHtml","exportSelectionInCellHtmlByPath","findNearestControlBackward","findNearestControlForward","findNextEditableControl","flushDeferredPagination","getBookmarks","getBulletList","getCanvasKitDocumentPreflight","getCanvasKitReplayPlan","getCaretPosition","getCellCharPropertiesAt","getCellCharPropertiesAtByPath","getCellInfo","getCellInfoByPath","getCellOwnProperties","getCellParaPropertiesAt","getCellParagraphCount","getCellParagraphCountByPath","getCellParagraphLength","getCellParagraphLengthByPath","getCellPicturePropertiesByPath","getCellProperties","getCellPropertiesByPath","getCellShapePropertiesByPath","getCellStyleAt","getCellTextDirection","getCharPropertiesAt","getCharPropertiesInHeaderFooter","getCharShapeRuns","getCharShapeRunsInCellByPath","getChartData","getChartDataByIndex","getClickHereProps","getClipboardText","getColumnDef","getControlImageData","getControlImageMime","getControlTextPositions","getControls","getCursorRect","getCursorRectByPath","getCursorRectByPathNear","getCursorRectInCell","getCursorRectInFootnote","getCursorRectInHeaderFooter","getCursorRectInNote","getCursorRectOnLine","getDocumentInfo","getEndnoteShape","getEquationProperties","getFieldInfoAt","getFieldList","getFieldValue","getFieldValueByName","getFontDecisionTrace","getFootnoteAtCursor","getFootnoteInfo","getFormObjectAt","getFormObjectInfo","getFormValue","getHeaderFooter","getHeaderFooterEditTarget","getHeaderFooterList","getHeaderFooterParaInfo","getHeaderFooterPictureProperties","getHeaderFooterPreviewPage","getHmlOpenMetadata","getHmlSaveState","getHostCanvasFontDiagnostics","getHyperlinkContext","getLineInfo","getLineInfoInCell","getNoteEditInfo","getNoteEquationProperties","getNumberingList","getPageBorderFill","getPageControlLayout","getPageDef","getPageFlowImageOps","getPageFootnoteInfo","getPageInfo","getPageLayerTree","getPageLayerTreeObject","getPageOfPosition","getPageOverlayImages","getPageSourceImageKeys","getParaPropertiesAt","getParaPropertiesInFootnote","getParaPropertiesInHf","getParagraphCount","getParagraphLength","getParagraphStableId","getPictureProperties","getPositionOfPage","getSectionCount","getSectionDef","getSelectionRects","getSelectionRectsInCell","getSelectionRectsInCellByPath","getSelectionRectsInFootnote","getSelectionRectsInHeaderFooter","getShapeBBox","getShapeProperties","getShapeText","getShowControlCodes","getShowParagraphMarks","getShowTransparentBorders","getSourceFontBytes","getSourceFormat","getSourceImageBytes","getStyleAt","getStyleDetail","getStyleList","getTableBBox","getTableBBoxAtPage","getTableCellBboxes","getTableCellBboxesByPath","getTableDimensions","getTableDimensionsByPath","getTableProperties","getTableSignature","getTextBoxControlIndex","getTextInCell","getTextInCellByPath","getTextRange","getValidationWarnings","getWasmLinearMemoryBytes","getWasmModuleExports","hasCellBorderFillInverse","hasHmlExportCapability","hasInternalClipboard","hasLoadedDocument","hasShapeZOrderInverse","hasTableTransposeClipboard","hitTest","hitTestBodyFootnoteMarker","hitTestFootnote","hitTestHeaderFooter","hitTestInFootnote","hitTestInHeaderFooter","hitTestInHeaderFooterTarget","initialize","installMeasureTextWidth","invalidateCanvasMetricFonts","invalidateCanvasMetrics","moveVertical","moveVerticalByPath","navigateNextEditable","pageHasFootnoteFootholds","prepareCanvasMetrics","reflowLinesegs","refreshLayout","releaseCanvasFontResources","releaseDocument","rememberRawCanvasFontDescriptor","renderEquationPreview","renderHeaderFooterEditPreviewToCanvas","renderPagePatchToCanvasFiltered","renderPageSvg","renderPageSvgWithProfile","renderPageToCanvas","renderPageToCanvasFiltered","searchAllText","searchText","setClipEnabled","setShowControlCodes","setShowParagraphMarks","setShowTransparentBorders","snapshotCapacity","stepDeferredPagination","textToLogicalOffset","withPortableMetrics"]);
const readCommands = new Set([
 'edit:copy','edit:select-all','edit:find','edit:find-again','edit:goto',
 'view:zoom-in','view:zoom-out','view:zoom-dialog','view:zoom-fit-page','view:zoom-fit-width',
 'view:zoom-50','view:zoom-75','view:zoom-100','view:zoom-125','view:zoom-150','view:zoom-200',
 'view:ctrl-mark','view:para-mark','view:border-transparent','view:toggle-clip','view:toggle-grid',
 'view:grid-settings','view:toolbox-basic','view:toolbox-format'
]);
export const studioMayCommand = (id: string): boolean => studioMayWrite() || readCommands.has(id);
function blocked(operation: string): never { throw new Error(`읽기 모드에서는 문서를 변경할 수 없습니다. 편집하기를 선택하세요. (${operation})`); }
export function installStudioBridge(bridge: any): void {
  const prototype = Object.getPrototypeOf(bridge);
  for (const name of Object.getOwnPropertyNames(prototype)) {
    const descriptor = Object.getOwnPropertyDescriptor(prototype, name);
    if (name === 'constructor' || typeof descriptor?.value !== 'function') continue;
    const original = descriptor.value;
    bridge[name] = function(...args: unknown[]) {
      // Caret stamps are serialized metadata, not a navigation requirement.
      if (!studioMayWrite() && name === 'setCaretPosition') return;
      if (!studioMayWrite() && !readMethods.has(name)) return blocked(name);
      const answer = Reflect.apply(original, bridge, args);
      if (name !== 'borrowDocumentHandle' || !answer) return answer;
      return new Proxy(answer, {get(target, key) {
        const value = Reflect.get(target, key, target);
        if (typeof value !== 'function') return value;
        return (...rawArgs: unknown[]) => {
          if (!studioMayWrite() && !readMethods.has(String(key))) return blocked(String(key));
          return Reflect.apply(value, target, rawArgs);
        };
      }});
    };
  }
}
export function installStudioInput(Input: any, Dispatcher: any): void {
  const input = Input.prototype;
  const stop = ['onInput','onCompositionStart','onCompositionEnd','onCut','onPaste','focusTextarea',
    'handleUndo','handleRedo','performUndo','performRedo','executeOperation','executeDocumentAgentOperation',
    'insertDroppedImageAtClientPoint','startResizeDrag','finishResizeDrag','finishMoveDrag',
    'finishPictureResizeDrag','finishPictureMoveDrag','finishPictureRotateDrag','finishLineEndpointDrag'];
  for (const name of stop) {
    const original = input[name];
    if (typeof original !== 'function') continue;
    input[name] = function(...args: any[]) {
      if (!studioIsEditing()) { args[0]?.preventDefault?.(); return; }
      return Reflect.apply(original, this, args);
    };
  }
  const dispatcher = Dispatcher.prototype;
  const dispatch = dispatcher.dispatchWithResult;
  dispatcher.dispatchWithResult = function(id: string, ...args: unknown[]) {
    return studioMayCommand(id) ? Reflect.apply(dispatch, this, [id, ...args]) : {ok:false, reason:'disabled'};
  };
  const enabled = dispatcher.isEnabled;
  dispatcher.isEnabled = function(id: string) {
    return studioMayCommand(id) && Reflect.apply(enabled, this, [id]);
  };
  const blockInput = (event: Event) => {
    if (studioMayWrite()) return;
    const target = event.target as Element | null;
    if (!target?.closest?.('#scroll-container, [aria-label="문서 편집 입력"]')) return;
    if (['mousedown','dblclick','contextmenu','pointerdown'].includes(event.type)) {
      // Keep native touch scrolling; object hit/drag handlers must not start.
      event.stopImmediatePropagation();
      return;
    }
    if (event.type === 'keydown') {
      const key = event as KeyboardEvent;
      if (!key.ctrlKey && !key.metaKey && ['ArrowLeft','ArrowRight','ArrowUp','ArrowDown','Home','End','PageUp','PageDown','Tab','Escape'].includes(key.key)) return;
      if ((key.ctrlKey || key.metaKey) && ['c','a','f'].includes(key.key.toLowerCase())) return;
    }
    event.preventDefault(); event.stopImmediatePropagation();
  };
  for (const event of ['mousedown','dblclick','contextmenu','pointerdown','keydown','beforeinput','input','compositionstart','compositionend','cut','paste','drop']) {
    document.addEventListener(event, blockInput, true);
  }
  studioSetEditing(false);
}
