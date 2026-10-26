/* Document Studio: offline native Office workspace, pinned to the bundled engine. */
(() => {
  if (window.studioOffice) return;
  const policy = window.studioOffice = { ready: false, authorized: false, wasAuthorized: false, modeSupported: false, changed: false, initialIntentHandled: false };
  const bridge = () => window.COOLMessageHandler;
  window.enableMacrosExecution = false;
  const style = document.createElement('style');
  style.textContent = `
    #AIAssistant-tab-label, #AIAssistant-tab, [id*="aiassistant"], [id*="ai-assistant"] { display:none !important; }
    html[data-studio-office-host] #document-container.readonly.mobile { position:relative !important; top:auto !important; min-height:0; }
    #toolbar-wrapper.studio-office-host { display:flex !important; position:relative; z-index:20 !important; flex-shrink:0; }
    #toolbar-wrapper.studio-office-host.readonly > :not(#studio-office-tools) { display:none !important; }
    #studio-office-tools { position:relative; z-index:1; pointer-events:auto; --studio-accent:#2858a5; background:var(--color-background-lighter,#fff); color:var(--color-main-text,#243247); border-top:1px solid var(--color-border,#e3e8ef); font:13px system-ui,sans-serif; flex:none; }
    #studio-office-tools[data-family="spreadsheet"] { --studio-accent:#217346; }
    #studio-office-tools[data-family="presentation"] { --studio-accent:#b94d30; }
    #studio-office-mode { display:flex; align-items:center; gap:4px; padding:3px 8px; }
    #studio-office-mode button { min-height:44px; }
    #studio-office-mode-label { font-weight:600; margin-inline-end:auto; }
    #studio-office-tools[data-editing="false"] #studio-office-tabs,
    #studio-office-tools[data-editing="false"] #studio-office-actions { display:none; }
    #studio-office-tabs, #studio-office-actions { display:flex; align-items:center; gap:4px; overflow-x:auto; scrollbar-width:none; padding:3px 8px; }
    #studio-office-tools button { flex:none; min-height:36px; border:0; border-radius:6px; padding:6px 11px; color:inherit; background:transparent; font:inherit; cursor:pointer; white-space:nowrap; }
    #studio-office-tools button:hover { background:var(--color-background-dark,#edf1f7); }
    #studio-office-tools button:focus-visible { outline:2px solid var(--studio-accent); outline-offset:-2px; }
    #studio-office-tools button[aria-selected="true"], #studio-office-tools button[aria-pressed="true"] { color:var(--studio-accent); background:color-mix(in srgb,var(--studio-accent) 10%,transparent); font-weight:600; }
    #studio-office-tools button:disabled { opacity:.38; cursor:default; }
    #studio-office-hint { font-size:11px; padding:2px 14px 6px; opacity:.75; }
    #studio-office-toggle { margin-inline-start:auto; }
    #studio-office-tools[data-collapsed="true"] #studio-office-actions, #studio-office-tools[data-collapsed="true"] #studio-office-hint { display:none; }
    @media(max-height:480px) { #studio-office-hint { display:none; } #studio-office-tools button { min-height:30px; } }
  `;
  document.head.appendChild(style);
  let attempts = 0;
  let bar, actions, hint, tabs, modeButton, modeLabel, saveButton;
  let transitionViewport, persistTimer, lastViewport='';
  let activeTab = '홈';
  let modeTransition=false, transitionGeneration=0;
  const controls = [];
  const map = () => window.app && app.map;
  const objectSelected = () => typeof GraphicSelection !== 'undefined' && GraphicSelection.hasActiveSelection();
  const editable = () => Boolean(policy.authorized && map() && map().isEditMode());
  function refreshLayout() {
    // The pinned Writer/Impress map no longer exposes Leaflet invalidateSize.
    const layout=window.app?.activeDocument?.activeLayout;
    if(typeof layout?.onResize==='function') layout.onResize();
    else window.dispatchEvent(new Event('resize'));
  }
  function snapshot() {
    const layout = window.app?.activeDocument?.activeLayout;
    const rect = layout?.viewedRectangle;
    if (!rect || !map()?.getZoom) return null;
    return { x:Number(rect.x1), y:Number(rect.y1), zoom:Number(map().getZoom()), part:map().getDocType()==='text' ? 0 : Number(map().getCurrentPartNumber?.() || 0) };
  }
  function validViewport(value) {
    return value && ['x','y','zoom','part'].every(key => Number.isFinite(value[key]))
      && Math.abs(value.x)<=1e12 && Math.abs(value.y)<=1e12 && value.zoom>=0 && value.zoom<=30
      && Number.isInteger(value.part) && value.part>=0 && value.part<=1000000;
  }
  function restore(value, done, generation) {
    const current=()=>generation===undefined || generation===transitionGeneration;
    if(!current()) return;
    if (!validViewport(value)) {done?.();return;}
    const drain = task => window.app?.layoutingService?.onDrain ? app.layoutingService.onDrain(task) : setTimeout(task,0);
    drain(() => {
      if (!current() || !map()) return;
      const layout = window.app?.activeDocument?.activeLayout;
      if (!layout) {done?.();return;}
      if (map().getDocType()!=='text' && map().getCurrentPartNumber?.() !== value.part) map().setPart?.(value.part);
      layout.applyZoom?.(value.zoom);
      drain(() => {
        if(!current()) return;
        const active=window.app?.activeDocument?.activeLayout, ratio=window.app.twipsToPixels;
        if (Number.isFinite(ratio)) active?.scrollTo?.(value.x*ratio,value.y*ratio);
        drain(()=>{if(current()) done?.();});
      });
    });
  }
  function beginModeTransition(value) {
    transitionViewport=value;modeTransition=true;
    return ++transitionGeneration;
  }
  function settleModeTransition(value, generation) {
    // Native cursor activation can arrive after the synchronous permission event.
    // Keep tools gated through the layout drain and that short Android UI transition.
    restore(value,()=>setTimeout(()=>restore(value,()=>{
      if(generation!==transitionGeneration) return;
      modeTransition=false;update();persistViewport();
    },generation),500),generation);
  }
  function cancelModeRestore(event) {
    if(modeTransition && !bar?.contains(event.target)) {
      ++transitionGeneration;modeTransition=false;update();
    }
  }
  for(const name of ['pointerdown','touchstart','wheel','keydown'])
    document.addEventListener(name,cancelModeRestore,true);
  function persistViewport() {
    let value;
    try { value=(modeTransition && validViewport(transitionViewport) ? transitionViewport : snapshot()) || {error:'layout-unavailable',family:map()?.getDocType?.()}; }
    catch(error) { value={error:String(error.message || error),family:map()?.getDocType?.()}; }
    // The native validator stores only valid coordinates. Invalid values are
    // retained separately as bounded view diagnostics, never as document data.
    const text=JSON.stringify(value);
    if (text!==lastViewport) {bridge()?.saveStudioViewState?.(text);lastViewport=text;}
  }
  function rememberViewport() {
    clearTimeout(persistTimer); persistTimer=setTimeout(persistViewport,250);
  }
  function installModeGate() {
    const m=map(), enter=m._switchToEditMode;
    if (typeof enter!=='function' || typeof m.setPermission!=='function') return false;
    m._switchToEditMode=function(...args) {
      if(modeTransition) return false;
      const value=snapshot();
      if (new URLSearchParams(location.search).get('permission')!=='edit' || !bridge()?.requestStudioEdit?.()) {
        hint.textContent='이 문서는 편집할 수 없습니다.';return false;
      }
      const generation=beginModeTransition(value);
      policy.authorized=true;policy.wasAuthorized=true;
      const result=enter.apply(this,args);
      update();refreshLayout();settleModeTransition(value,generation);return result;
    };
    const permission=m.setPermission;
    m.setPermission=function(value,...args) {
      const viewport=modeTransition && validViewport(transitionViewport) ? transitionViewport : snapshot();
      const generation=beginModeTransition(viewport);
      const result=permission.call(this,value,...args);
      // Upstream commits a pending Calc cell before entering read-only mode.
      // Do not revoke the gate before that commit has reached the native core.
      if (value==='readonly' || value==='view') policy.authorized=false;
      update();refreshLayout();settleModeTransition(viewport,generation);return result;
    };
    return true;
  }
  function uno(name, args) {
    if (!editable()) return;
    if (!map()._clip || !map()._clip.filterExecCopyPaste('.uno:' + name, args)) map().sendUnoCommand('.uno:' + name, args);
    map().focus(false);
  }
  function command(label, name, needsObject = false) {
    return { label, name, needsObject, run: () => uno(name) };
  }
  function button(label, run, parent) {
    const b = document.createElement('button');
    b.type = 'button'; b.textContent = label; b.setAttribute('aria-label', label);
    // Toolbar interactions retain the current document/cell/shape selection.
    b.addEventListener('mousedown', e => e.preventDefault());
    b.addEventListener('click', run); parent.appendChild(b); return b;
  }
  function update() {
    if (!bar) return;
    const editing = editable(), object = objectSelected();
    bar.dataset.editing=String(editing);
    modeLabel.textContent=editing?'편집 중':'원본 보기';
    modeButton.textContent=editing?'보기로 전환':'편집하기';
    modeButton.setAttribute('aria-label',modeButton.textContent);
    modeButton.hidden=!policy.modeSupported || new URLSearchParams(location.search).get('permission')!=='edit';
    saveButton.hidden=!policy.changed;
    modeButton.disabled=modeTransition;saveButton.disabled=modeTransition;
    controls.forEach(({ b, item }) => {
      const state = item.name && map().stateChangeHandler && map().stateChangeHandler.getItemValue('.uno:' + item.name);
      b.disabled = !editing || modeTransition || (item.needsObject && !object) || state === 'disabled';
      if (state === 'true' || state === 'false') b.setAttribute('aria-pressed', state);
      else b.removeAttribute('aria-pressed');
    });
    hint.textContent = !editing ? '원본 보기 · 편집하기를 선택하면 같은 문서에서 수정할 수 있습니다.' : activeTab === '개체'
      ? (object ? '선택한 개체를 드래그해 이동 · 테두리 핸들로 크기 변경 · 위치·크기로 수치 지정' : '그림·도형·텍스트 상자를 눌러 선택하세요. 텍스트 입력 중이면 Esc로 빠져나오세요.')
      : map().getDocType() === 'spreadsheet' ? '셀을 두 번 눌러 입력 · 선택 핸들로 범위 지정 · 행·열 경계를 끌어 크기 조절'
      : map().getDocType() === 'presentation' ? '개체를 눌러 선택하고 드래그 · 텍스트는 두 번 눌러 편집'
      : '본문을 눌러 입력 · 글자를 선택해 서식 변경 · 그림은 개체 탭에서 배치';
  }
  function render() {
    actions.replaceChildren(); controls.length = 0;
    tabs.querySelectorAll('[role="tab"]').forEach(b => b.setAttribute('aria-selected', String(b.textContent === activeTab)));
    const family = map().getDocType();
    let items;
    if (activeTab === '홈') items = [command('실행 취소','Undo'), command('다시 실행','Redo'), command('잘라내기','Cut'), command('복사','Copy'), command('붙여넣기','Paste'), command('굵게','Bold'), command('기울임','Italic'), command('밑줄','Underline'), command('왼쪽 정렬','LeftPara'), command('가운데 정렬','CenterPara'), command('오른쪽 정렬','RightPara')];
    else if (activeTab === '삽입') items = [
      { label:'그림', run:() => document.getElementById('insertgraphic').click() },
      command('텍스트 상자','Text'), command('사각형','BasicShapes.rectangle'), command('타원','BasicShapes.ellipse'),
      ...(family === 'text' ? [command('표','InsertTable')] : []),
      ...(family === 'presentation' ? [command('새 슬라이드','InsertPage')] : [])
    ];
    else if (activeTab === '문단') items = [command('글꼴','FontDialog'),command('문단','ParagraphDialog'),command('쪽 설정','PageDialog'),command('쪽 나누기','InsertPagebreak')];
    else if (activeTab === '개체') items = [
      { label:'선택·이동', run:() => { map()._docLayer.postKeyboardEvent('input',0,1280); map().focus(false); } },
      ...(family === 'text' ? [{ label:'자유 배치', needsObject:true, run:() => { uno('SetAnchorToPara'); uno('WrapThrough'); } }] : []),
      command('위치·크기','TransformDialog',true), command('맨 앞으로','BringToFront',true), command('맨 뒤로','SendToBack',true),
      command('왼쪽 맞춤','ObjectAlignLeft',true), command('가운데 맞춤','AlignCenter',true), command('오른쪽 맞춤','ObjectAlignRight',true),
      command('좌우 뒤집기','FlipHorizontal',true), command('상하 뒤집기','FlipVertical',true)
    ];
    else if (family === 'spreadsheet') items = [command('행 삽입','InsertRowsBefore'), command('열 삽입','InsertColumnsBefore'), command('셀 병합','MergeCells'), command('오름차순','SortAscending'), command('내림차순','SortDescending'), command('행 높이','RowHeight'), command('열 너비','ColumnWidth'),command('셀 서식','FormatCellDialog'),command('함수 마법사','FunctionDialog')];
    else items = [command('새 슬라이드','InsertPage'), command('슬라이드 복제','DuplicatePage'), command('슬라이드 삭제','DeletePage')];
    items.forEach(item => { const b=button(item.label, () => { if(!b.disabled) item.run(); update(); },actions); if(item.name) b.dataset.command=item.name; controls.push({b,item}); });
    update(); refreshLayout();
  }
  function mount() {
    const wrapper = document.getElementById('toolbar-wrapper');
    if (!wrapper) return false;
    document.documentElement.dataset.studioOfficeHost='true';wrapper.classList.add('studio-office-host');
    bar = document.createElement('section'); bar.id='studio-office-tools'; bar.dataset.family=map().getDocType(); bar.setAttribute('aria-label','문서 편집 도구');
    const mode=document.createElement('div');mode.id='studio-office-mode';
    modeLabel=document.createElement('span');modeLabel.id='studio-office-mode-label';modeLabel.setAttribute('aria-live','polite');mode.appendChild(modeLabel);
    modeButton=button('편집하기',()=>{
      if(editable()) map().setPermission('readonly'); else map()._switchToEditMode();
      update();refreshLayout();
    },mode);
    saveButton=button('저장 후 닫기',()=>{persistViewport();window.postMobileMessage('DOCUMENT_STUDIO_SAVE_AND_CLOSE');},mode);
    button('돌아가기',()=>{persistViewport();window.postMobileMessage('BYE');},mode);
    tabs=document.createElement('div'); tabs.id='studio-office-tabs'; tabs.setAttribute('role','tablist'); tabs.setAttribute('aria-label','편집 도구 탭');
    actions=document.createElement('div'); actions.id='studio-office-actions'; actions.setAttribute('role','toolbar');
    hint=document.createElement('div'); hint.id='studio-office-hint';
    const names=['홈','삽입','개체']; if(map().getDocType()==='text') names.push('문단'); if(map().getDocType()==='spreadsheet') names.push('셀'); if(map().getDocType()==='presentation') names.push('슬라이드');
    names.forEach(name => {const b=button(name,()=>{activeTab=name;bar.dataset.collapsed='false';render();},tabs);b.setAttribute('role','tab');});
    const toggle=button('도구 접기',()=>{const collapsed=bar.dataset.collapsed!=='true';bar.dataset.collapsed=String(collapsed);toggle.textContent=collapsed?'도구 펼치기':'도구 접기';toggle.setAttribute('aria-label',toggle.textContent);toggle.setAttribute('aria-expanded',String(!collapsed));refreshLayout();},tabs);
    toggle.id='studio-office-toggle';toggle.setAttribute('aria-expanded','true');
    bar.append(mode,tabs,actions,hint);wrapper.appendChild(bar);
    // Keep map touch/drag handlers from consuming host toolbar taps.
    if(window.L?.DomEvent?.disableClickPropagation) L.DomEvent.disableClickPropagation(bar);
    else for(const name of ['mousedown','touchstart','pointerdown','dblclick','contextmenu']) bar.addEventListener(name,event=>event.stopPropagation());
    policy.modeSupported=installModeGate();
    if(!policy.modeSupported) {hint.textContent='이 엔진에서는 모드 전환을 지원하지 않습니다.';modeButton.hidden=true;}
    map().on('commandstatechanged',event=>{
      if(event.commandName==='.uno:ModifiedStatus' && String(event.state)==='true' && policy.wasAuthorized) policy.changed=true;
      update();
    });
    app.events.on('updatepermission',()=>{update();refreshLayout();if(modeTransition) restore(transitionViewport,undefined,transitionGeneration);});
    map().on('moveend zoomend updateparts',rememberViewport);
    try {restore(JSON.parse(bridge()?.getStudioViewState?.() || '{}'));} catch(ignore) { }
    render();policy.ready=true;return true;
  }
  const timer=setInterval(() => {
    try {
    if(window.app && app.LOUtil) app.LOUtil.isAIAssistantAvailable=()=>false;
    if(map() && map().wopi) {map().wopi.DisableAISettings=true;map().isAIConfigured=false;}
    if(map() && map()._docLoaded) {
      if(!bar) mount(); else update();
      // Only an explicit host EDIT launch may enter editing without a toolbar tap.
      if(bar && !policy.initialIntentHandled) {
        policy.initialIntentHandled=true;
        if(policy.modeSupported && new URLSearchParams(location.search).get('studioopen')==='EDIT') map()._switchToEditMode();
      }
    }
    if(policy.ready && attempts%5===0) persistViewport();
    } catch(error) {
      // Keep bounded local diagnostics when an upstream UI API prevents mounting.
      // No document text is read, serialized or sent to a logging service.
      bridge()?.saveStudioViewState?.(JSON.stringify({
        error:'host-policy-update',name:String(error.name),message:String(error.message).slice(0,300),
        ready:policy.ready,family:map()?.getDocType?.()
      }));
    }
    if(++attempts>=300 && !policy.ready) clearInterval(timer);
  },200);
  window.addEventListener('pagehide',()=>{persistViewport();clearInterval(timer);clearTimeout(persistTimer);},{once:true});
})();
