const {test}=require('node:test');
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),{JSDOM}=require('jsdom');
function picker(){
 const d=new JSDOM('<body><section></section>',{url:'https://editor.local',runScripts:'outside-only'}),w=d.window,picked=[];
 w.eval(fs.readFileSync(path.join(__dirname,'../../shared/editor-ui/dist/studio-editor-ui.js'),'utf8'));
 const panel=w.document.querySelector('section');
 const start=()=>w.StudioEditorUI.fontPicker(panel,{fonts:['Nanum Gothic','Lato','Nanum Gothic'],current:'Lato',onPick:n=>picked.push(n),preview:()=>true,key:'test-fonts'});
 start();const button=n=>[...panel.querySelectorAll('button')].find(b=>b.getAttribute('aria-label')===n||b.textContent===n);
 return {w,d,panel,picked,button,options:()=>[...panel.querySelectorAll('[role=option]')].map(b=>b.getAttribute('aria-label'))};
}
test('font favorites and script filtering use only real engine catalog entries',()=>{
 const s=picker();try{
  assert.deepEqual(s.options(),['Nanum Gothic','Lato']);
  s.button('한국어').click();assert.deepEqual(s.options(),['Nanum Gothic']);
  s.button('Nanum Gothic 즐겨찾기').click();s.button('즐겨찾기').click();assert.deepEqual(s.options(),['Nanum Gothic']);
  assert.deepEqual(JSON.parse(s.w.localStorage.getItem('test-fonts-favorites')),['Nanum Gothic']);
  s.button('Nanum Gothic 즐겨찾기').click();assert.deepEqual(s.options(),[]);
  s.button('영문·기타').click();assert.deepEqual(s.options(),['Lato']);
 }finally{s.d.window.close();}
});
test('font pick keeps selection state and recent ordering without adding unavailable aliases',async()=>{
 const s=picker();try{s.button('Nanum Gothic').click();await Promise.resolve();await Promise.resolve();
  assert.deepEqual(s.picked,['Nanum Gothic']);
  assert.equal(s.button('Nanum Gothic').getAttribute('aria-selected'),'true');
  assert.deepEqual(JSON.parse(s.w.localStorage.getItem('test-fonts')),['Nanum Gothic']);
  const input=s.panel.querySelector('input');input.value='맑은 고딕';input.dispatchEvent(new s.w.Event('input'));assert.deepEqual(s.options(),[]);
 }finally{s.d.window.close();}
});
