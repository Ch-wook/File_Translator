/* Document Studio: offline native Office workspace, pinned to the bundled engine. */
(() => {
  if (window.studioOffice) return;
  const policy = window.studioOffice = { ready: false, autoEditRequested: false };
  window.enableMacrosExecution = false;
  const style = document.createElement('style');
  style.textContent = `
    #AIAssistant-tab-label, #AIAssistant-tab, [id*="aiassistant"], [id*="ai-assistant"] { display:none !important; }
    #studio-office-tools { --studio-accent:#2858a5; background:var(--color-background-lighter,#fff); color:var(--color-main-text,#243247); border-top:1px solid var(--color-border,#e3e8ef); font:13px system-ui,sans-serif; flex:none; }
    #studio-office-tools[data-family="spreadsheet"] { --studio-accent:#217346; }
    #studio-office-tools[data-family="presentation"] { --studio-accent:#b94d30; }
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
  let bar, actions, hint, tabs;
  let activeTab = '홈';
  const controls = [];
  const map = () => window.app && app.map;
  const objectSelected = () => typeof GraphicSelection !== 'undefined' && GraphicSelection.hasActiveSelection();
  const editable = () => Boolean(map() && map().isEditMode());
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
    controls.forEach(({ b, item }) => {
      const state = item.name && map().stateChangeHandler && map().stateChangeHandler.getItemValue('.uno:' + item.name);
      b.disabled = !editing || (item.needsObject && !object) || state === 'disabled';
      if (state === 'true' || state === 'false') b.setAttribute('aria-pressed', state);
      else b.removeAttribute('aria-pressed');
    });
    hint.textContent = !editing ? '읽기 모드 · 연필 버튼으로 편집을 다시 시작하세요.' : activeTab === '개체'
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
    else if (activeTab === '개체') items = [
      { label:'선택·이동', run:() => { map()._docLayer.postKeyboardEvent('input',0,1280); map().focus(false); } },
      ...(family === 'text' ? [{ label:'자유 배치', needsObject:true, run:() => { uno('SetAnchorToPara'); uno('WrapThrough'); } }] : []),
      command('위치·크기','TransformDialog',true), command('맨 앞으로','BringToFront',true), command('맨 뒤로','SendToBack',true),
      command('왼쪽 맞춤','ObjectAlignLeft',true), command('가운데 맞춤','AlignCenter',true), command('오른쪽 맞춤','ObjectAlignRight',true),
      command('좌우 뒤집기','FlipHorizontal',true), command('상하 뒤집기','FlipVertical',true)
    ];
    else if (family === 'spreadsheet') items = [command('행 삽입','InsertRowsBefore'), command('열 삽입','InsertColumnsBefore'), command('셀 병합','MergeCells'), command('오름차순','SortAscending'), command('내림차순','SortDescending'), command('행 높이','RowHeight'), command('열 너비','ColumnWidth')];
    else items = [command('새 슬라이드','InsertPage'), command('슬라이드 복제','DuplicatePage'), command('슬라이드 삭제','DeletePage')];
    items.forEach(item => { const b=button(item.label, () => { if(!b.disabled) item.run(); update(); },actions); if(item.name) b.dataset.command=item.name; controls.push({b,item}); });
    update(); map().invalidateSize();
  }
  function mount() {
    const wrapper = document.getElementById('toolbar-wrapper');
    if (!wrapper) return false;
    bar = document.createElement('section'); bar.id='studio-office-tools'; bar.dataset.family=map().getDocType(); bar.setAttribute('aria-label','문서 편집 도구');
    tabs=document.createElement('div'); tabs.id='studio-office-tabs'; tabs.setAttribute('role','tablist'); tabs.setAttribute('aria-label','편집 도구 탭');
    actions=document.createElement('div'); actions.id='studio-office-actions'; actions.setAttribute('role','toolbar');
    hint=document.createElement('div'); hint.id='studio-office-hint';
    const names=['홈','삽입','개체']; if(map().getDocType()==='spreadsheet') names.push('셀'); if(map().getDocType()==='presentation') names.push('슬라이드');
    names.forEach(name => {const b=button(name,()=>{activeTab=name;bar.dataset.collapsed='false';render();},tabs);b.setAttribute('role','tab');});
    const toggle=button('도구 접기',()=>{const collapsed=bar.dataset.collapsed!=='true';bar.dataset.collapsed=String(collapsed);toggle.textContent=collapsed?'도구 펼치기':'도구 접기';toggle.setAttribute('aria-label',toggle.textContent);toggle.setAttribute('aria-expanded',String(!collapsed));map().invalidateSize();},tabs);
    toggle.id='studio-office-toggle';toggle.setAttribute('aria-expanded','true');
    bar.append(tabs,actions,hint);wrapper.appendChild(bar);
    map().on('commandstatechanged',update);app.events.on('updatepermission',update);
    render();policy.ready=true;return true;
  }
  const timer=setInterval(() => {
    if(window.app && app.LOUtil) app.LOUtil.isAIAssistantAvailable=()=>false;
    if(map() && map().wopi) {map().wopi.DisableAISettings=true;map().isAIConfigured=false;}
    if(map() && map()._docLoaded) {
      // The mobile upstream deliberately starts in view mode even with permission=edit.
      // Request editing once, after load; never force it again after the user leaves it.
      if(!policy.autoEditRequested && new URLSearchParams(location.search).get('permission')==='edit') {
        policy.autoEditRequested=true;
        if(!editable()) map()._switchToEditMode();
      }
      if(!bar) mount(); else update();
    }
    if(++attempts>=300) clearInterval(timer);
  },200);
  window.addEventListener('pagehide',()=>clearInterval(timer),{once:true});
})();
