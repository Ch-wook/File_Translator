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
      getToolbarCommandValues:()=>({'Noto Sans KR':[12,14],'Liberation Sans':[12,14],'Android Available Font':[]}),
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
  w.eval(fs.readFileSync(path.join(__dirname,'../../shared/editor-ui/dist/studio-editor-ui.js'),'utf8'));
  w.eval(source);tick();flush();tick();flush();
  const button = label => [...w.document.querySelectorAll('button')].find(b=>b.getAttribute('aria-label')===label || b.textContent===label);
  const click = label => {const b=button(label);assert.ok(b,`Missing ${label}`);b.click();if(label==='편집하기' || label==='보기로 전환') flush();return b;};
  return {
    w,commands,states,keys,mobile,persisted,tick,click,button,flush,viewport,
    requests:()=>requested,grants:()=>grants,resizes:()=>layoutResizes,nativeRestores:()=>nativeRestores,nativeCursor,userEvent:name=>userEvents[name]?.({isTrusted:true,target:w.document.body}),close:()=>w.close(),
    select:value=>{selected=value;tick();},
    edit:value=>{editing=value;listeners.updatepermission?.();},
    changed:()=>listeners.commandstatechanged?.({commandName:'.uno:ModifiedStatus',state:'true'})
  };
}


test('all Office families open in native VIEW with one header and no editing sheet',()=>{
 for(const family of ['text','spreadsheet','presentation']){const s=workspace(family);try{
 assert.equal(s.requests(),0);assert.equal(s.grants(),0);assert.equal(s.w.studioOffice.ready,true);
 assert.equal(s.w.document.querySelector('#studio-office-bottom').hidden,true);
 assert.equal(s.w.document.querySelector('#studio-office-sheet').hidden,true);
 assert.equal(s.w.document.querySelectorAll('.studio-ui-header').length,1);
 assert.equal(s.button('굵게'),undefined);assert.equal(s.button('저장 후 닫기').hidden,true);
 }finally{s.close();}}
});
test('mode transitions preserve actual native part, zoom and camera with delayed cursor invalidation',()=>{
 for(const family of ['text','spreadsheet','presentation']){const s=workspace(family);try{
 const before=s.viewport();s.click('편집하기');assert.equal(s.requests(),1);assert.equal(s.grants(),1);assert.deepEqual(s.viewport(),before);
 assert.equal(s.w.document.querySelector('#studio-office-sheet').hidden,true);
 s.click('글자');assert.equal(s.button('굵게').disabled,false);s.click('굵게');assert.equal(s.commands.at(-1).name,'.uno:Bold');
 s.click('보기로 전환');assert.deepEqual(s.viewport(),before);s.tick();assert.equal(s.requests(),1);
 assert.equal(s.button('굵게').disabled,true);
 if(family==='text')for(let i=0;i<3;i++){s.nativeCursor();assert.deepEqual(s.viewport(),before);}
 }finally{s.close();}}
});
test('unknown intent stays VIEW, explicit EDIT is authorized, readonly capability never requests editing',()=>{
 const a=workspace('text','edit','unexpected'),b=workspace('text','edit','EDIT'),c=workspace('text','readonly','EDIT');try{
 assert.equal(a.requests(),0);assert.equal(b.requests(),1);assert.equal(c.requests(),0);assert.equal(c.button('편집하기').hidden,true);
 }finally{a.close();b.close();c.close();}
});
test('persisted viewport is restored before EDIT intent or startup metadata can overwrite it',()=>{
 const saved={x:800,y:12000,zoom:5,part:0};
 for(const intent of ['VIEW','EDIT']){const s=workspace('text','edit',intent,true,saved);try{
 assert.deepEqual(s.viewport(),saved);assert.ok(s.persisted.length>0);
 for(const value of s.persisted)for(const key of ['x','y','zoom','part'])assert.equal(value[key],saved[key]);
 for(let i=0;i<3;i++){s.nativeCursor();assert.deepEqual(s.viewport(),saved);}
 }finally{s.close();}}
});
test('native refusal and unapproved engine edit state cannot enable mutation',()=>{
 const s=workspace('text','edit','VIEW',false);try{
 s.click('편집하기');assert.equal(s.requests(),0);assert.equal(s.grants(),1);s.edit(true);s.click('글자');s.button('굵게').click();assert.equal(s.commands.length,0);
 assert.equal(s.w.studioOffice.authorized,false);
 }finally{s.close();}
});
test('context object tools require native selection and dispatch actual wrap, placement and drag commands',()=>{
 const s=workspace();try{
 s.click('편집하기');s.click('더보기');s.click('개체');assert.equal(s.button('위치·크기').disabled,true);s.button('위치·크기').click();assert.equal(s.commands.length,0);
 s.select(true);s.click('그림');s.click('위치·크기');assert.equal(s.commands.at(-1).name,'.uno:TransformDialog');
 s.click('자유 배치');assert.deepEqual(s.commands.slice(-2).map(c=>c.name),['.uno:SetAnchorToPara','.uno:WrapThrough']);
 s.click('선택·이동');assert.equal(s.keys[0][2],1280);
 }finally{s.close();}
});
test('disabled state and independent toggle state reach compact controls',()=>{
 const s=workspace();try{
 s.click('편집하기');s.click('글자');s.states['.uno:Undo']='disabled';s.states['.uno:Bold']='true';s.tick();
 assert.equal(s.button('실행 취소').disabled,true);assert.equal(s.button('굵게').getAttribute('aria-pressed'),'true');
 s.click('굵게');assert.equal(s.commands.at(-1).name,'.uno:Bold');s.click('설정 닫기');
 assert.equal(s.w.document.querySelector('#studio-office-sheet').hidden,true);assert.equal(s.w.document.querySelector('#studio-office-sheet').hidden,true);
 }finally{s.close();}
});
test('Writer paragraph, Calc cells/formulas and Impress slides retain format-specific commands',()=>{
 const w=workspace(),c=workspace('spreadsheet'),p=workspace('presentation');try{
 w.click('편집하기');w.click('문단');w.click('쪽 설정');assert.equal(w.commands.at(-1).name,'.uno:PageDialog');
 c.click('편집하기');c.click('셀');c.click('열 삽입');assert.equal(c.commands.at(-1).name,'.uno:InsertColumnsBefore');
 c.click('셀 서식');assert.equal(c.commands.at(-1).name,'.uno:FormatCellDialog');c.click('함수 마법사');assert.equal(c.commands.at(-1).name,'.uno:FunctionDialog');
 p.click('편집하기');p.click('슬라이드');p.click('슬라이드 복제');assert.equal(p.commands.at(-1).name,'.uno:DuplicatePage');
 p.click('더보기');p.click('개체');assert.equal(p.button('자유 배치'),undefined);
 }finally{w.close();c.close();p.close();}
});
test('shared font picker uses native available faces and sends font-only UNO properties',async()=>{
 const s=workspace();try{
 const filtered=[];s.w.app.map._clip={filterExecCopyPaste:command=>{filtered.push(command);return true;}};
 const sizes=[];s.w.app.map.applyFontSize=value=>sizes.push(value);
 s.click('편집하기');s.click('글자');s.click('글꼴 선택 · 문서 글꼴');
 const options=[...s.w.document.querySelectorAll('[role=option]')];assert.deepEqual(options.map(b=>b.getAttribute('aria-label')),['Android Available Font','Liberation Sans','Noto Sans KR']);
 const search=s.w.document.querySelector('[aria-label="글꼴 검색"]');search.value='Noto';search.dispatchEvent(new s.w.Event('input'));
 assert.equal(s.w.document.querySelectorAll('[role=option]').length,1);
 s.button('Noto Sans KR').click();await Promise.resolve();
 const c=s.commands.at(-1);assert.equal(c.name,'.uno:CharFontName');assert.deepEqual(JSON.parse(JSON.stringify(c.args)),{'CharFontName.FamilyName':{type:'string',value:'Noto Sans KR'}});
 s.click('18');assert.deepEqual(sizes,['18']);assert.deepEqual(filtered,[],'Formatting must bypass Android clipboard filtering so parameters reach the engine');
 }finally{s.close();}
});
test('clipboard is dispatched once after approval and blocked in VIEW',()=>{
 const s=workspace('spreadsheet');try{
 const clipboard=[];s.w.app.map._clip={filterExecCopyPaste:name=>{if(['.uno:Cut','.uno:Paste'].includes(name)){clipboard.push(name);return true;}return false;}};
 s.click('편집하기');s.click('더보기');s.click('잘라내기');s.click('붙여넣기');assert.deepEqual(clipboard,['.uno:Cut','.uno:Paste']);
 s.click('보기로 전환');s.button('붙여넣기').click();assert.equal(clipboard.length,2);s.w.eval(source);assert.equal(s.w.document.querySelectorAll('#studio-office-tools').length,1);
 }finally{s.close();}
});
test('mode changes do not mark content modified, actual changes expose save after returning to VIEW',()=>{
 const s=workspace();try{
 s.click('편집하기');assert.equal(s.w.studioOffice.changed,false);assert.equal(s.button('저장 후 닫기').disabled,true);
 s.click('문단');s.click('쪽 나누기');s.button('저장 후 닫기').click();assert.deepEqual(s.mobile,[],'Do not close before native modified acknowledgement');
 s.changed();assert.equal(s.button('저장 후 닫기').disabled,false);s.click('보기로 전환');assert.equal(s.button('저장 후 닫기').hidden,false);
 s.click('저장 후 닫기');assert.deepEqual(s.mobile,['DOCUMENT_STUDIO_SAVE_AND_CLOSE']);
 }finally{s.close();}
});
test('reading close persists viewport without exporting or saving the original',()=>{
 const s=workspace();try{s.click('돌아가기');assert.deepEqual(s.mobile,['BYE']);assert.equal(s.persisted.length,1);assert.deepEqual(s.persisted[0],s.viewport());assert.equal(s.commands.length,0);}finally{s.close();}
});
test('native caret restoration remains pinned until actual document input releases it',()=>{
 const s=workspace();try{
 const before=s.viewport();s.button('편집하기').click();assert.equal(s.button('보기로 전환').disabled,true);
 s.flush();assert.deepEqual(s.viewport(),before);assert.ok(s.nativeRestores()>0);
 for(let i=0;i<3;i++){s.nativeCursor();assert.deepEqual(s.viewport(),before);}
 s.userEvent('compositionstart');s.nativeCursor();assert.equal(s.viewport().y,0);
 }finally{s.close();}
});
