const {chromium}=require('playwright');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../..'),runtime=path.join(root,'.engine-cache/hangul/runtime/hangul'),fixtures=path.join(root,'core/build/visual-fixtures');
const out=path.join(root,'artifacts/hangul-mobile');fs.mkdirSync(out,{recursive:true});
const mime={'.html':'text/html','.js':'text/javascript','.css':'text/css','.wasm':'application/wasm','.woff2':'font/woff2','.woff':'font/woff','.ttf':'font/ttf','.svg':'image/svg+xml','.png':'image/png','.json':'application/json'};
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--no-sandbox']});const results=[];
 try{for(const format of ['hwp','hwpx']){
  const context=await browser.newContext({viewport:{width:412,height:915},isMobile:true,hasTouch:true});
  const page=await context.newPage(),errors=[],external=[],engineErrors=[];
  page.on('pageerror',e=>errors.push(e.message));page.on('console',m=>{if(m.type()==='error'){console.error('ENGINE_CONSOLE',m.text());if(/\[EventBus\]|\[table\]|커맨드 실행 실패/.test(m.text()))engineErrors.push(m.text());}});
  await context.addInitScript(({format})=>{
   let chunks=[];window.__clip={text:'',html:''};window.__writeCount=0;
   window.DocumentStudio={describe:()=>JSON.stringify({name:'모바일 서식.'+format,format,openIntent:'VIEW'}),
    loaded:()=>window.__ready=true,loadFailed:e=>window.__failure=e,requestDiscard:()=>{},
    setEditing:v=>{window.__editing=v;return true;},saveViewState:v=>window.__view=JSON.parse(v),
    writeClipboard:v=>{window.__clip=JSON.parse(v);return true;},readClipboard:()=>JSON.stringify(window.__clip),
    beginWrite:n=>{if(!window.__editing)return false;chunks=[];window.__writeCount++;return true;},
    writeChunk:c=>{chunks.push(c);return true;},finishWrite:r=>{window.__saved=chunks.join('');window.__report=JSON.parse(r);return JSON.stringify({ok:true});},
    abortWrite:()=>{},closeEditor:v=>{window.__closed=true;window.__saveOriginal=v;}};
  },{format});
  await context.route('**/*',route=>{
   const url=new URL(route.request().url());if(url.hostname!=='hangul.documentstudio.local'){external.push(url.href);return route.fulfill({status:403,body:'blocked'});}
   const file=url.pathname==='/document'?path.join(fixtures,'fixture.'+format):path.join(runtime,url.pathname==='/'?'index.html':decodeURIComponent(url.pathname).replace(/^\//,''));
   if(!file.startsWith(runtime+path.sep)&&!file.startsWith(fixtures+path.sep))return route.fulfill({status:403,body:'blocked'});
   if(!fs.existsSync(file))return route.fulfill({status:404,body:'missing'});
   return route.fulfill({body:fs.readFileSync(file),contentType:mime[path.extname(file)]||'application/octet-stream'});
  });
  await page.goto('https://hangul.documentstudio.local/');
  await page.waitForFunction(()=>window.__ready||window.__failure,null,{timeout:90000});
  assert.equal(await page.evaluate(()=>window.__failure),undefined,JSON.stringify(errors));
  const frame=page.frames().find(f=>f.url().includes('/engine/index.html'));assert.ok(frame);
  await page.locator('#mode').click();await page.waitForFunction(()=>document.body.dataset.state==='EDITING'&&!document.getElementById('mode').disabled);
  const call=(method,...args)=>frame.evaluate(({method,args})=>window.rhwpStudio.documentStudio.mobile[method](...args),{method,args});
  if(process.env.CLOSE_RACE_ONLY==='1'){
   await call('find','PAGE_EDIT_ORIGINAL');
   const proof=await require('./hangul-close-race.cjs')({page});
   const saved=await page.evaluate(()=>window.__saved);
   await frame.evaluate(async({saved,format})=>window.rhwpStudio.documentStudio.open(Uint8Array.from(atob(saved),c=>c.charCodeAt(0)),'close-race.'+format),{saved,format});
   await call('find','PAGE_EDIT_ORIGINAL');assert.equal((await call('state')).char.spacings[0],12);
   results.push({format,...proof,savedAndReopened:true});await context.close();continue;
  }
  if(process.env.TABLE_SAVE_DIAGNOSTIC==='1'){
   await call('navigate',true);await call('plainText','\nTABLE_SAVE_PREFIX\n');await call('createTable',2,2);
   for(const [i,text] of ['TABLE_CELL_A','TABLE_CELL_B','TABLE_CELL_C','TABLE_CELL_D'].entries()){if(i)await call('moveCell',true);await call('plainText',text);}
   await page.waitForFunction(()=>!document.getElementById('save').disabled);
   await page.locator('#save').click();await page.waitForFunction(()=>window.__closed,null,{timeout:60000});
   const data=await page.evaluate(()=>window.__saved);assert.ok(data);
   fs.writeFileSync(path.join(fixtures,'table-save.'+format),Buffer.from(data,'base64'));
   console.log('TABLE_SAVE_DIAGNOSTIC',format,Buffer.from(data,'base64').length);
   await context.close();continue;
  }
  let found=await call('find','PAGE_EDIT_ORIGINAL');assert.ok(found.count>0);
  await call('plainText','가나다라 ABC 123 tail');
  await call('find','가나다라');await call('char',{bold:false});
  await call('find','ABC');const outsideBefore=(await call('state')).char;
  await call('find','나다');const selectedBefore=(await call('capture')).range;
  await page.locator('#groups').getByRole('button',{name:'글자',exact:true}).click();
  await page.locator('#tools').getByRole('button',{name:'굵게',exact:true}).click();
  await page.getByLabel('크기(pt)',{exact:true}).fill('18');await page.getByLabel('크기(pt)',{exact:true}).dispatchEvent('change');
  await frame.waitForFunction(()=>window.rhwpStudio.documentStudio.mobile.state().char.fontSize===1800);
  await page.getByRole('button',{name:'글자색 #e53935',exact:true}).click();
  await frame.waitForFunction(()=>window.rhwpStudio.documentStudio.mobile.state().char.textColor?.toLowerCase()==='#e53935');
  const available=await call('fonts');assert.ok(available.length>=2,'At least two bundled fonts are selectable');
  const font=available.find(n=>/나눔고딕|Noto Sans KR|Pretendard/.test(n))||available[0];
  await page.getByRole('button',{name:/^글꼴 선택/}).click();
  await page.getByLabel('글꼴 검색',{exact:true}).fill(font);
  await page.getByRole('option',{name:font,exact:true}).click();
  await frame.waitForFunction(name=>{const p=window.rhwpStudio.documentStudio.mobile.state().char;return p.fontFamily===name||p.fontFamilies?.includes(name);},font);
  for(const [label,value] of [['자간(%)','12'],['장평(%)','85']]){
   await page.getByLabel(label,{exact:true}).fill(value);await page.getByLabel(label,{exact:true}).dispatchEvent('change');
  }
  await frame.waitForFunction(()=>{const p=window.rhwpStudio.documentStudio.mobile.state().char;return p.spacings?.[0]===12&&p.ratios?.[0]===85;});
  const selectedAfter=(await call('capture')).range;assert.deepEqual(selectedAfter,selectedBefore,'Panel focus and combined formatting retain the selected range');
  const combined=(await call('state')).char;assert.equal(combined.bold,true);assert.equal(combined.fontSize,1800);assert.equal(combined.textColor.toLowerCase(),'#e53935');
  // A new selection made in the document while its panel remains open replaces
  // the toolbar target; pressing the next style must not reuse the old range.
  await call('focus');await page.keyboard.press('ArrowRight');await page.keyboard.press('Shift+ArrowRight');await page.waitForTimeout(60);
  await page.locator('#tools').getByRole('button',{name:'기울임',exact:true}).click();
  await call('find','라');assert.equal((await call('state')).char.italic,true,'New selection receives the style while the panel stays open');
  await call('find','나다');assert.equal((await call('state')).char.italic,false,'Previous selection stays unchanged after selection moves');
  await page.locator('#undo').click();await call('find','나다');
  await page.getByRole('button',{name:'설정 완료',exact:true}).click();
  const measure=()=>frame.locator('.selection-highlight').filter({visible:true}).first().boundingBox().then(v=>v?.width||0);
  await call('char',{ratios:Array(7).fill(100),spacings:Array(7).fill(0)});await page.waitForTimeout(150);const base=await measure();
  await call('char',{spacings:Array(7).fill(20)});await page.waitForTimeout(150);const spaced=await measure();
  await call('char',{ratios:Array(7).fill(80),spacings:Array(7).fill(0)});await page.waitForTimeout(150);const narrow=await measure();
  assert.ok(base>0&&spaced>base&&narrow<base,JSON.stringify({base,spaced,narrow}));
  assert.equal((await call('state')).char.fontSize,1800,'Spacing and width do not substitute font size');
  await call('char',{ratios:Array(7).fill(85),spacings:Array(7).fill(12)});
  await call('find','ABC');const outsideAfter=(await call('state')).char;
  for(const key of ['fontSize','bold','italic','textColor','ratios','spacings'])assert.deepEqual(outsideAfter[key],outsideBefore[key],'Unselected '+key+' stays unchanged');
  await call('find','가');assert.equal((await call('state')).char.bold,false,'Adjacent unselected Korean stays non-bold');
  await call('find','나다');await call('copy');
  await call('find','tail');await call('paste',true);assert.equal(await call('count','tail'),0);
  await call('dispatch','edit:undo');assert.equal(await call('count','tail'),1,'One undo restores replacement and all original text');
  await call('dispatch','edit:redo');assert.equal(await call('count','tail'),0);
  await call('dispatch','edit:undo');await call('find','나다');await call('copy');await call('find','tail');await call('paste',false);
  await call('find','나다');await call('find','나다');const pasted=(await call('state')).char;
  assert.equal(pasted.fontSize,1800);assert.equal(pasted.textColor.toLowerCase(),'#e53935');assert.equal(pasted.spacings[0],12);
  await call('dispatch','edit:undo');
  const structureTest=require('./hangul-mobile-structure.cjs');fs.writeFileSync(path.join(fixtures,'mobile-image.png'),structureTest.image);
  const structure=await structureTest({page,frame,call,context});
  await page.waitForFunction(()=>window.__writeCount>0,null,{timeout:20000});
  await call('find','나다');await call('char',{spacings:Array(7).fill(11)});
  const openPanel=page.getByRole('button',{name:'설정 닫기',exact:true});if(await openPanel.isVisible())await openPanel.click();
  await page.locator('#groups').getByRole('button',{name:'글자',exact:true}).click();
  await page.waitForFunction(()=>!document.getElementById('save').disabled);
  const closeRace=await require('./hangul-close-race.cjs')({
   page,
   prepareLatest:()=>page.evaluate(()=>{const input=document.querySelector('[aria-label="자간(%)"]');input.value='12';input.dispatchEvent(new Event('input',{bubbles:true}));}),
   repairLatest:async()=>{
    await call('find','나다');
    await page.evaluate(()=>{const input=document.querySelector('[aria-label="자간(%)"]');input.blur();input.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true}));input.focus();input.value='12';input.dispatchEvent(new Event('input',{bubbles:true}));});
   }
  });
  await page.waitForFunction(()=>window.__closed,null,{timeout:60000});
  const exported=await page.evaluate(()=>({data:window.__saved,report:window.__report,saveOriginal:window.__saveOriginal}));
  assert.equal(exported.report.count,0);assert.equal(exported.saveOriginal,true);
  const bytes=Buffer.from(exported.data,'base64');fs.writeFileSync(path.join(fixtures,'mobile-formatting.'+format),bytes);
  await frame.evaluate(async({data,format})=>window.rhwpStudio.documentStudio.open(Uint8Array.from(atob(data),c=>c.charCodeAt(0)),'mobile-saved.'+format),{data:exported.data,format});
  await call('find','나다');const reopened=(await call('state')).char;
  assert.equal(reopened.fontSize,1800);assert.equal(reopened.bold,true);assert.equal(reopened.textColor.toLowerCase(),'#e53935');
  assert.equal(reopened.ratios[0],85);assert.equal(reopened.spacings[0],12);
  await call('find','ABC');for(const key of ['fontSize','bold','textColor','ratios','spacings'])assert.deepEqual((await call('state')).char[key],outsideBefore[key],'Reopened unselected '+key+' remains');
  assert.deepEqual(errors,[]);assert.deepEqual(engineErrors,[]);assert.deepEqual(external,[]);
  await page.screenshot({path:path.join(out,'mobile-format-'+format+'.png'),fullPage:true});
  results.push({format,...closeRace,phone:true,partialSelection:true,font,structure,combinedCharacterFormatting:true,spacingMetrics:{base,spaced,narrow},unselectedPreserved:true,clipboardRichAndPlain:true,atomicPasteUndoRedo:true,pendingFormatSaved:true,freshSelectionTarget:true,autosave:true,savedAndReopened:true,bytes:bytes.length});
  await context.close();
 }
 fs.writeFileSync(path.join(out,'mobile-engine-results.json'),JSON.stringify(results,null,2));console.log('MOBILE_ENGINE_RESULTS',JSON.stringify(results));
 }catch(e){fs.writeFileSync(path.join(out,'failure.json'),JSON.stringify({message:e.message,stack:e.stack,results},null,2));
  for(const c of browser.contexts())for(const p of c.pages()){await p.screenshot({path:path.join(out,'failure.png'),fullPage:true}).catch(()=>{});fs.writeFileSync(path.join(out,'failure-dom.html'),await p.content().catch(()=>''));const f=p.frames().find(f=>f.url().includes('/engine/'));if(f)console.log('ENGINE_STATE',await f.evaluate(()=>window.rhwpStudio?.documentStudio?.mobile?.state()).catch(String));}
  throw e;
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
