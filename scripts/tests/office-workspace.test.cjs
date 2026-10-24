const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require('jsdom');
const source = fs.readFileSync(path.join(__dirname, '../../officeruntime/src/main/assets/dist/studio-policy.js'), 'utf8');
function workspace(family = 'text', permission = 'edit') {
  const dom = new JSDOM('<!doctype html><head></head><body><div id="toolbar-wrapper"></div><input id="insertgraphic" type="file"></body>', {
    url: `file:///android_asset/dist/cool.html?permission=${permission}`, runScripts: 'outside-only'
  });
  const w = dom.window, commands = [], states = {}, listeners = {};
  const mobile=[];w.postMobileMessage=message=>mobile.push(message);
  let tick, editing = false, selected = false, requested = 0, keys = [];
  w.setInterval = f => { tick=f; return 1; }; w.clearInterval = () => {};
  w.GraphicSelection = { hasActiveSelection: () => selected };
  w.app = {
    LOUtil: {}, events: { on: (name, fn) => { listeners[name]=fn; } },
    map: {
      _docLoaded: true, wopi: {}, isEditMode: () => editing,
      _switchToEditMode: () => { requested++; editing=true; }, getDocType: () => family,
      sendUnoCommand: (name, args) => commands.push({name,args}), focus: () => {}, invalidateSize: () => {},
      stateChangeHandler: { getItemValue: name => states[name] },
      _docLayer: { postKeyboardEvent: (...args) => keys.push(args) },
      on: (name,fn) => { listeners[name]=fn; }
    }
  };
  w.eval(source); tick();
  const click = label => {
    const b=[...w.document.querySelectorAll('#studio-office-tools button')].find(b=>b.textContent===label);
    assert.ok(b, `Missing ${label}`); b.click(); return b;
  };
  return {w, commands, states, click, keys, mobile, tick, close:()=>w.close(), requests:()=>requested,
    select: value=>{selected=value;tick();}, edit:value=>{editing=value;listeners.updatepermission?.();},
    button:label=>[...w.document.querySelectorAll('button')].find(b=>b.textContent===label)};
}
test('opens each Office family in edit mode once, and respects later read mode', () => {
  for (const family of ['text','spreadsheet','presentation']) {
    const s=workspace(family);
    try {
      assert.equal(s.requests(),1);assert.equal(s.w.studioOffice.ready,true);
      s.edit(false);s.tick();assert.equal(s.requests(),1);
      assert.equal(s.button('굵게').disabled,true);
      assert.match(s.w.document.querySelector('#studio-office-hint').textContent,/읽기 모드/);
    } finally {s.close();}
  }
});
test('does not request edit access for read-only files', () => {
  const s=workspace('text','readonly');try {assert.equal(s.requests(),0);assert.equal(s.button('굵게').disabled,true);} finally {s.close();}
});
test('object commands require an actual selection; Writer free positioning updates anchor and wrap', () => {
  const s=workspace();try {
    s.click('개체');const position=s.button('위치·크기');assert.equal(position.disabled,true);
    position.click();assert.equal(s.commands.length,0);
    s.select(true);assert.equal(position.disabled,false);s.click('위치·크기');
    assert.equal(s.commands.at(-1).name,'.uno:TransformDialog');
    s.click('자유 배치');assert.deepEqual(s.commands.slice(-2).map(c=>c.name),['.uno:SetAnchorToPara','.uno:WrapThrough']);
    s.click('선택·이동');assert.equal(s.keys.length,1);assert.equal(s.keys[0][2],1280);
  } finally {s.close();}
});
test('engine-disabled commands and toggle state propagate to visible controls', () => {
  const s=workspace();try {
    s.states['.uno:Undo']='disabled';s.states['.uno:Bold']='true';s.tick();
    assert.equal(s.button('실행 취소').disabled,true);assert.equal(s.button('굵게').getAttribute('aria-pressed'),'true');
    s.click('굵게');assert.equal(s.commands.at(-1).name,'.uno:Bold');
    s.click('도구 접기');assert.equal(s.w.document.querySelector('#studio-office-tools').dataset.collapsed,'true');
    s.click('삽입');assert.equal(s.w.document.querySelector('#studio-office-tools').dataset.collapsed,'false');
  } finally {s.close();}
});
test('Calc and Impress expose format-specific operations, keeping object tools available', () => {
  const calc=workspace('spreadsheet'), slides=workspace('presentation');try {
    calc.click('셀');calc.click('열 삽입');assert.equal(calc.commands.at(-1).name,'.uno:InsertColumnsBefore');
    slides.click('슬라이드');slides.click('슬라이드 복제');assert.equal(slides.commands.at(-1).name,'.uno:DuplicatePage');
    slides.click('개체');assert.equal(slides.button('자유 배치'),undefined);
  } finally {calc.close();slides.close();}
});
test('clipboard commands use the engine clipboard bridge without duplicate UNO dispatch', () => {
  const s=workspace('spreadsheet');try {
    const clipboard=[];
    s.w.app.map._clip={filterExecCopyPaste:(name)=>{if(name==='.uno:Cut' || name==='.uno:Paste') {clipboard.push(name);return true;}return false;}};
    s.click('잘라내기');s.click('붙여넣기');s.click('굵게');
    assert.deepEqual(clipboard,['.uno:Cut','.uno:Paste']);assert.deepEqual(s.commands.map(c=>c.name),['.uno:Bold']);
    s.w.eval(source);assert.equal(s.w.document.querySelectorAll('#studio-office-tools').length,1);assert.equal(s.requests(),1);
  } finally {s.close();}
});

test('save and close requests the native durable-save lifecycle only in edit mode', () => {
  const s=workspace();try {
    s.click('저장 후 닫기');assert.deepEqual(s.mobile,['DOCUMENT_STUDIO_SAVE_AND_CLOSE']);
    s.edit(false);s.click('저장 후 닫기');assert.equal(s.mobile.length,1);
  } finally {s.close();}
});
