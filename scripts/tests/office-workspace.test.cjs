const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require('jsdom');
const source = fs.readFileSync(path.join(__dirname, '../../officeruntime/src/main/assets/dist/studio-policy.js'), 'utf8');

function workspace(family = 'text', permission = 'edit', intent = 'VIEW', grant = true, savedView = null) {
  const dom = new JSDOM('<!doctype html><head></head><body><div id="toolbar-wrapper"></div><input id="insertgraphic" type="file"></body>', {
    url: `file:///android_asset/dist/cool.html?permission=${permission}&startreadonly=true&studioopen=${intent}`, runScripts: 'outside-only'
  });
  const w = dom.window, commands = [], states = {}, listeners = {}, mobile = [], persisted = [], keys = [];
  const timeouts = new Map();
  let tick, editing = false, selected = false, requested = 0, grants = 0, serial = 0;
  let zoom = 7, part = family === 'text' ? 0 : 2, x = 120, y = 340, layoutResizes = 0, nativeRestores = 0;
  w.postMobileMessage = message => mobile.push(message);
  w.setInterval = f => { tick=f; return 1; }; w.clearInterval = () => {};
  w.setTimeout = f => { const id=++serial;timeouts.set(id,f);return id; };
  w.clearTimeout = id => timeouts.delete(id);
  w.COOLMessageHandler = {
    requestStudioEdit: () => {grants++;return grant && permission === 'edit';},
    getStudioViewState: () => JSON.stringify(savedView || {}),
    saveStudioViewState: state => persisted.push(JSON.parse(state))
  };
  const viewport = () => ({x,y,zoom,part});
  const userEvents={};
  const addListener=w.document.addEventListener.bind(w.document);
  w.document.addEventListener=(name,callback,options)=>{
    if(['pointerdown','touchstart','wheel','keydown','beforeinput','compositionstart'].includes(name)) userEvents[name]=callback;
    addListener(name,callback,options);
  };
  w.GraphicSelection = { hasActiveSelection: () => selected };
  w.app = {
    LOUtil: {}, events: { on: (name, fn) => { listeners[name]=fn; } }, twipsToPixels: 0.1,
    activeDocument: { activeLayout: {
      type: family === 'text' ? 'ViewLayoutWriter' : 'ViewLayoutCalc',
      get viewedRectangle() {return {x1:x,y1:y};},
      scrollProperties:{viewX:x*0.1,viewY:y*0.1},
      refreshVisibleAreaRectangle() {x=this.scrollProperties.viewX/0.1;y=this.scrollProperties.viewY/0.1;},
      onResize: () => {layoutResizes++;},
      applyZoom: value => {zoom=value;},
      scrollTo(px,py) {this.scrollProperties.viewX=px;this.scrollProperties.viewY=py;this.refreshVisibleAreaRectangle();}
    }},
    map: {
      _docLoaded: true, wopi: {}, isEditMode: () => editing,
      _switchToEditMode: () => {requested++;editing=true;zoom=10;w.app.activeDocument.activeLayout.scrollTo(0,0);listeners.updatepermission?.();w.setTimeout(()=>w.app.activeDocument.activeLayout.scrollTo(0,0),50);},
      setPermission: value => {editing=value==='edit';w.app.activeDocument.activeLayout.scrollTo(0,0);listeners.updatepermission?.();},
      getDocType: () => family, getZoom:()=>zoom, getCurrentPartNumber:()=>part, setPart:value=>{part=value;},
      sendUnoCommand: (name,args) => commands.push({name,args}), focus: () => {},
      stateChangeHandler: { getItemValue: name => states[name] },
      _docLayer: { postKeyboardEvent: (...args) => keys.push(args) },
      on: (name,fn) => {listeners[name]=fn;}
    }
  };
  // Match the pinned engine's ViewState/_onUpdateCursor contract, not only
  // direct layout scrolling. The next native caret update reaffirms this offset.
  const layer=w.app.map._docLayer;
  layer._viewState={onViewPositionMsg:message=>{
    const values=Object.fromEntries(message.split(' ').slice(1).map(token=>token.split('=')));
    layer._savedScrollPos={pX1:Number(values.x)*0.1,pY1:Number(values.y)*0.1};
    layer._restoringViewScroll=true;nativeRestores++;
    w.app.activeDocument.activeLayout.scrollTo(layer._savedScrollPos.pX1,layer._savedScrollPos.pY1);
  }};
  const nativeCursor=()=>{
    if(layer._restoringViewScroll && layer._savedScrollPos) {
      w.app.activeDocument.activeLayout.scrollTo(layer._savedScrollPos.pX1,layer._savedScrollPos.pY1);
      layer._restoringViewScroll=false;layer._savedScrollPos=null;
    } else {w.app.activeDocument.activeLayout.scrollTo(0,0);}
  };
  const flush = () => {
    for(let round=0;timeouts.size && round<10;round++) {
      const tasks=[...timeouts.values()];timeouts.clear();tasks.forEach(fn=>fn());
    }
  };
  w.eval(source);tick();flush();tick();flush();
  const button = label => [...w.document.querySelectorAll('#studio-office-tools button')].find(b=>b.textContent===label);
  const click = label => {const b=button(label);assert.ok(b,`Missing ${label}`);b.click();if(label==='편집하기' || label==='보기로 전환') flush();return b;};
  return {
    w,commands,states,keys,mobile,persisted,tick,click,button,flush,viewport,
    requests:()=>requested,grants:()=>grants,resizes:()=>layoutResizes,nativeRestores:()=>nativeRestores,nativeCursor,userEvent:name=>userEvents[name]?.({isTrusted:true,target:w.document.body}),close:()=>w.close(),
    select:value=>{selected=value;tick();},
    edit:value=>{editing=value;listeners.updatepermission?.();},
    changed:()=>listeners.commandstatechanged?.({commandName:'.uno:ModifiedStatus',state:'true'})
  };
}

test('all Office families open in VIEW with mutation controls blocked and no native edit request', () => {
  for(const family of ['text','spreadsheet','presentation']) {
    const s=workspace(family);
    try {
      assert.equal(s.requests(),0);assert.equal(s.grants(),0);assert.equal(s.w.studioOffice.ready,true);
      assert.equal(s.button('굵게').disabled,true);
      assert.equal(s.w.document.querySelector('#studio-office-tools').dataset.editing,'false');
      assert.match(s.w.document.querySelector('#studio-office-hint').textContent,/원본 보기/);
      s.button('굵게').click();assert.equal(s.commands.length,0);
      assert.equal(s.button('저장 후 닫기').hidden,true);
    } finally {s.close();}
  }
});
test('explicit editing requests native authorization once and preserves part, zoom and viewport both ways', () => {
  for (const family of ['text','spreadsheet','presentation']) {
    const s=workspace(family);
    try {
      const before=s.viewport();s.click('편집하기');s.flush();
      assert.equal(s.requests(),1);assert.equal(s.grants(),1);assert.deepEqual(s.viewport(),before);
      assert.equal(s.button('굵게').disabled,false);
      s.click('보기로 전환');s.flush();assert.deepEqual(s.viewport(),before);
      assert.equal(s.button('굵게').disabled,true);s.tick();assert.equal(s.requests(),1);
    } finally {s.close();}
  }
});
test('unknown open intent stays in VIEW; explicit host EDIT is authorized, readonly capability cannot edit', () => {
  const unknown=workspace('text','edit','unexpected'), explicit=workspace('text','edit','EDIT'), readonly=workspace('text','readonly','EDIT');
  try {
    assert.equal(unknown.requests(),0);assert.equal(explicit.requests(),1);assert.equal(explicit.grants(),1);
    assert.equal(readonly.requests(),0);assert.equal(readonly.button('굵게').disabled,true);
    assert.equal(readonly.button('편집하기').hidden,true);
  } finally {unknown.close();explicit.close();readonly.close();}
});
test('explicit EDIT waits for the persisted viewport before it captures its mode position', () => {
  const saved={x:800,y:12000,zoom:5,part:0};
  const s=workspace('text','edit','EDIT',true,saved);
  try {
    assert.equal(s.requests(),1);assert.deepEqual(s.viewport(),saved);
    for(let i=0;i<3;i++) {s.nativeCursor();assert.deepEqual(s.viewport(),saved);}
  } finally {s.close();}
});
test('view metadata is not overwritten with startup coordinates before a saved view restores', () => {
  const saved={x:800,y:12000,zoom:5,part:0};
  const s=workspace('text','edit','VIEW',true,saved);
  try {
    assert.deepEqual(s.viewport(),saved);
    assert.ok(s.persisted.length>0);
    for(const value of s.persisted) {
      for(const key of ['x','y','zoom','part']) assert.equal(value[key],saved[key]);
    }
  } finally {s.close();}
});
test('native refusal and unapproved engine edit state cannot enable commands', () => {
  const s=workspace('text','edit','VIEW',false);
  try {
    s.click('편집하기');assert.equal(s.requests(),0);assert.equal(s.grants(),1);
    s.edit(true);s.button('굵게').click();assert.equal(s.commands.length,0);
    assert.equal(s.w.studioOffice.authorized,false);
  } finally {s.close();}
});
test('object commands require selection and Writer placement dispatches actual anchor and wrap commands', () => {
  const s=workspace();try {
    s.click('편집하기');s.click('개체');
    const position=s.button('위치·크기');assert.equal(position.disabled,true);
    position.click();assert.equal(s.commands.length,0);
    s.select(true);s.click('위치·크기');assert.equal(s.commands.at(-1).name,'.uno:TransformDialog');
    s.click('자유 배치');assert.deepEqual(s.commands.slice(-2).map(c=>c.name),['.uno:SetAnchorToPara','.uno:WrapThrough']);
    s.click('선택·이동');assert.equal(s.keys[0][2],1280);
  } finally {s.close();}
});
test('engine-disabled commands and toggle state propagate to compact controls', () => {
  const s=workspace();try {
    s.click('편집하기');s.states['.uno:Undo']='disabled';s.states['.uno:Bold']='true';s.tick();
    assert.equal(s.button('실행 취소').disabled,true);
    assert.equal(s.button('굵게').getAttribute('aria-pressed'),'true');
    s.click('굵게');assert.equal(s.commands.at(-1).name,'.uno:Bold');
    s.click('도구 접기');assert.equal(s.w.document.querySelector('#studio-office-tools').dataset.collapsed,'true');
    s.click('삽입');assert.equal(s.w.document.querySelector('#studio-office-tools').dataset.collapsed,'false');
  } finally {s.close();}
});
test('format tools dispatch Writer paragraph, Calc cell/formula and Impress slide operations', () => {
  const writer=workspace(),calc=workspace('spreadsheet'),slides=workspace('presentation');
  try {
    writer.click('편집하기');writer.click('문단');writer.click('쪽 설정');
    assert.equal(writer.commands.at(-1).name,'.uno:PageDialog');
    calc.click('편집하기');calc.click('셀');calc.click('열 삽입');
    assert.equal(calc.commands.at(-1).name,'.uno:InsertColumnsBefore');
    calc.click('셀 서식');assert.equal(calc.commands.at(-1).name,'.uno:FormatCellDialog');
    calc.click('함수 마법사');assert.equal(calc.commands.at(-1).name,'.uno:FunctionDialog');
    slides.click('편집하기');slides.click('슬라이드');slides.click('슬라이드 복제');
    assert.equal(slides.commands.at(-1).name,'.uno:DuplicatePage');
    slides.click('개체');assert.equal(slides.button('자유 배치'),undefined);
  } finally {writer.close();calc.close();slides.close();}
});
test('clipboard bridge dispatches once after approval and is blocked after switching back to VIEW', () => {
  const s=workspace('spreadsheet');try {
    const clipboard=[];s.w.app.map._clip={filterExecCopyPaste:name=>{
      if(name==='.uno:Cut' || name==='.uno:Paste') {clipboard.push(name);return true;}return false;
    }};
    s.click('편집하기');s.click('잘라내기');s.click('붙여넣기');s.click('굵게');
    assert.deepEqual(clipboard,['.uno:Cut','.uno:Paste']);assert.deepEqual(s.commands.map(c=>c.name),['.uno:Bold']);
    s.click('보기로 전환');s.click('붙여넣기');assert.equal(clipboard.length,2);
    s.w.eval(source);assert.equal(s.w.document.querySelectorAll('#studio-office-tools').length,1);
  } finally {s.close();}
});
test('mode switches alone do not expose save; actual modified state enables explicit native save and close', () => {
  const s=workspace();try {
    s.click('편집하기');assert.equal(s.button('저장 후 닫기').hidden,true);
    s.changed();assert.equal(s.button('저장 후 닫기').hidden,false);
    s.click('보기로 전환');s.click('저장 후 닫기');
    assert.deepEqual(s.mobile,['DOCUMENT_STUDIO_SAVE_AND_CLOSE']);
  } finally {s.close();}
});
test('reading close stores only viewport metadata and requests close without a save', () => {
  const s=workspace();try {
    s.click('돌아가기');assert.deepEqual(s.mobile,['BYE']);
    assert.equal(s.persisted.length,1);assert.deepEqual(s.persisted[0],s.viewport());
    assert.equal(s.requests(),0);assert.equal(s.commands.length,0);
  } finally {s.close();}
});

test('pinned native layouts resize without the removed map invalidateSize API', () => {
  for(const family of ['text','spreadsheet','presentation']) {
    const s=workspace(family);
    try {
      assert.equal(typeof s.w.app.map.invalidateSize,'undefined');
      assert.equal(s.w.studioOffice.ready,true);
      assert.ok(s.resizes()>0);
      s.click('편집하기');const before=s.resizes();
      s.click('도구 접기');assert.ok(s.resizes()>before);
      s.click('돌아가기');assert.deepEqual(s.mobile,['BYE']);
      assert.ok(s.persisted.some(value=>Number.isFinite(value.x) && Number.isFinite(value.zoom)));
    } finally {s.close();}
  }
});

test('mode tools wait for delayed native caret activation before restoring the original viewport', () => {
  const s=workspace('text');
  try {
    const before=s.viewport();
    s.button('편집하기').click();
    assert.equal(s.button('보기로 전환').disabled,true);
    assert.equal(s.button('굵게').disabled,true);
    s.button('보기로 전환').click();
    assert.equal(s.w.app.map.isEditMode(),true);
    s.flush();
    assert.deepEqual(s.viewport(),before);
    assert.equal(s.button('보기로 전환').disabled,false);
    assert.equal(s.button('굵게').disabled,false);
    s.click('보기로 전환');assert.deepEqual(s.viewport(),before);
  } finally {s.close();}
});

test('native cursor invalidation after the mode layout settles reaffirms the saved Writer position', () => {
  const s=workspace('text');
  try {
    const before=s.viewport();
    s.click('편집하기');
    assert.ok(s.nativeRestores()>0);
    for(let i=0;i<3;i++) {s.nativeCursor();assert.deepEqual(s.viewport(),before);}
    s.click('보기로 전환');
    for(let i=0;i<3;i++) {s.nativeCursor();assert.deepEqual(s.viewport(),before);}
    s.userEvent('compositionstart');s.nativeCursor();
    assert.equal(s.viewport().y,0,'actual user input releases the saved camera');
  } finally {s.close();}
});
