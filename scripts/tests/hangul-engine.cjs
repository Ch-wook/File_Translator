const { chromium }=require('playwright');
const fs=require('node:fs');const path=require('node:path');const assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../..');
const runtime=path.join(root,'.engine-cache/hangul/runtime/hangul');
const fixtures=path.join(root,'core/build/visual-fixtures');
const output=path.join(root,'artifacts/document-engines');fs.mkdirSync(output,{recursive:true});
const mime={'.html':'text/html','.js':'text/javascript','.css':'text/css','.wasm':'application/wasm','.woff2':'font/woff2','.woff':'font/woff','.ttf':'font/ttf','.svg':'image/svg+xml','.png':'image/png','.json':'application/json'};
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--no-sandbox']});const results=[];
 try {
  for(const {format,phone,readonly=false} of [{format:'hwp',phone:false},{format:'hwpx',phone:false},{format:'hwpx',phone:true},{format:'hwp',phone:false,readonly:true},{format:'hwpx',phone:true,readonly:true}]) {
   const context=await browser.newContext({viewport:phone?{width:412,height:915}:{width:1360,height:950},isMobile:phone,hasTouch:phone});
   const page=await context.newPage();const errors=[],external=[];
   page.on('pageerror',e=>errors.push(e.message));
   await context.addInitScript(({format})=>{
    let chunks=[];window.__nativeReady=false;window.__nativeClosed=false;window.__nativeEditing=false;window.__writeCount=0;
    window.DocumentStudio={describe:()=>JSON.stringify({name:`fixture.${format}`,format,openIntent:'VIEW'}),loaded:()=>{window.__nativeReady=true;},loadFailed:e=>{window.__nativeFailure=e;},requestDiscard:()=>{},setEditing:value=>{if(!window.__nativeReady)return false;window.__nativeEditing=value;return true;},saveViewState:value=>{window.__viewState=JSON.parse(value);},
     beginWrite:length=>{if(!window.__nativeEditing)return false;window.__writeCount++;chunks=[];window.__saveLength=length;return true;},writeChunk:chunk=>{chunks.push(chunk);return true;},
     finishWrite:report=>{const parsed=JSON.parse(report);if(parsed.count!==0)return JSON.stringify({ok:false});window.__saved=chunks.join('');window.__saveReport=parsed;return JSON.stringify({ok:true});},
     abortWrite:()=>{chunks=[];},closeEditor:saveOriginal=>{window.__nativeClosed=true;window.__saveOriginal=saveOriginal;}};
   },{format});
   await context.route('**/*',async route=>{
    const url=new URL(route.request().url());
    if(url.hostname!=='hangul.documentstudio.local'){external.push(url.href);return route.fulfill({status:403,body:'blocked'});}
    const file=url.pathname==='/document'?path.join(fixtures,`fixture.${format}`):path.join(runtime,url.pathname==='/'?'index.html':decodeURIComponent(url.pathname).replace(/^\//,''));
    if(!file.startsWith(runtime+path.sep) && !file.startsWith(fixtures+path.sep))return route.fulfill({status:403,body:'blocked'});
    if(!fs.existsSync(file) || !fs.statSync(file).isFile())return route.fulfill({status:404,body:'missing'});
    return route.fulfill({body:fs.readFileSync(file),contentType:mime[path.extname(file)]||'application/octet-stream',headers:{'Cache-Control':'no-store','Content-Security-Policy':"default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; font-src 'self' data: blob:; img-src 'self' data: blob:; connect-src 'self'; frame-src 'self'; worker-src 'none'"}});
   });
   await page.goto('https://hangul.documentstudio.local/');
   await page.waitForFunction(()=>window.__nativeReady || window.__nativeFailure,null,{timeout:90000});
   assert.equal(await page.evaluate(()=>window.__nativeFailure),undefined,JSON.stringify(errors));
   const frame=page.frames().find(f=>f.url().includes('/engine/index.html'));assert.ok(frame,'The dedicated document renderer loaded');
   const canvas=frame.locator('canvas.document-page-canvas').first();await canvas.waitFor({timeout:30000});
   let box=await canvas.boundingBox();assert.ok(box.width>200 && box.height>400,'A paginated document canvas is displayed');
   assert.equal(await frame.evaluate(()=>window.rhwpStudio.documentStudio.isEditing()),false,'Opening always starts in VIEW');
   assert.equal(await page.locator('body').getAttribute('data-state'),'READING');
   assert.equal(await page.evaluate(()=>window.__writeCount),0,'Opening a read-only document writes no document bytes');
   if(readonly) {
    const initial=await frame.evaluate(async ()=>Array.from((await window.rhwpStudio.documentStudio.exportDocument?.('invalid').catch(()=>({bytes:[]}))).bytes));
    assert.equal(initial.length,0,'Export is refused in VIEW');
    await page.mouse.click(box.x+110,box.y+100);
    await page.keyboard.insertText('BLOCKED 한글');
    await frame.evaluate(()=>{
     const input=document.querySelector('[aria-label="문서 편집 입력"]');
     input.dispatchEvent(new CompositionEvent('compositionstart',{bubbles:true,data:'가'}));
     input.dispatchEvent(new InputEvent('beforeinput',{bubbles:true,cancelable:true,data:'가',inputType:'insertText'}));
     input.dispatchEvent(new InputEvent('input',{bubbles:true,data:'가',inputType:'insertText'}));
     input.dispatchEvent(new ClipboardEvent('paste',{bubbles:true,cancelable:true}));
     input.dispatchEvent(new CompositionEvent('compositionend',{bubbles:true,data:'가'}));
    });
    for(const id of ['edit:undo','edit:redo','format:bold','table:insert-row-below','unknown:mutate']) {
     const answer=await frame.evaluate(id=>window.rhwpStudio.automation.execute(id),id);
     assert.equal(answer.ok,false,id+' must be refused in VIEW');
    }
    await page.mouse.move(box.x+120,box.y+130);await page.mouse.down();await page.mouse.move(box.x+230,box.y+250,{steps:8});await page.mouse.up();
    assert.equal(await frame.evaluate(()=>window.rhwpStudio.documentStudio.dirty()),false,'VIEW input and drag do not mutate');
    await page.locator('#back').click();await page.waitForFunction(()=>window.__nativeClosed);
    assert.equal(await page.evaluate(()=>window.__writeCount),0,'Reading and closing never exports/writes a document');
    assert.equal(await page.evaluate(()=>window.__saveOriginal),false);
    assert.equal(external.length,0);assert.deepEqual(errors,[]);
    await page.screenshot({path:path.join(output,`hangul-${format}-${phone?'phone':'tablet'}-readonly.png`),fullPage:true});
    results.push({format,phone,readonly:true,inputBlocked:true,commandsBlocked:true,writes:0,externalRequests:0,errors});
    await context.close();continue;
   }
   await frame.evaluate(()=>{
    const viewport=document.getElementById('scroll-container');
    viewport.scrollTop=Math.min(70,viewport.scrollHeight-viewport.clientHeight);viewport.scrollLeft=0;
   });
   const beforeMode=await frame.evaluate(()=>window.rhwpStudio.documentStudio.captureView());
   await page.locator('#mode').click();
   await frame.waitForFunction(()=>window.rhwpStudio.documentStudio.isEditing());
   const afterMode=await frame.evaluate(()=>window.rhwpStudio.documentStudio.captureView());
   assert.ok(Math.abs(beforeMode.scrollTop-afterMode.scrollTop)<=2,'VIEW to EDIT preserves scroll');
   assert.equal(beforeMode.zoom,afterMode.zoom,'VIEW to EDIT preserves zoom');
   box=await canvas.boundingBox();
   if(phone) await page.touchscreen.tap(box.x+110*(box.width/794),box.y+100*(box.width/794)); else await page.mouse.click(box.x+110,box.y+100);
   await page.keyboard.press('Control+Home');await page.keyboard.press('Home');await page.keyboard.press('Shift+End');
   await page.keyboard.insertText('PAGE_EDIT_CHANGED 한글 직접 편집');
   await frame.waitForFunction(()=>window.rhwpStudio.automation.getContext().canUndo);
   await page.keyboard.press('Control+z');await frame.waitForFunction(()=>window.rhwpStudio.automation.getContext().canRedo);
   await page.keyboard.press('Control+Shift+z');
   if(format==='hwpx' && !phone) {
    box=await canvas.boundingBox();
    await page.mouse.click(box.x+145,box.y+184);
    assert.equal(await frame.evaluate(()=>window.rhwpStudio.automation.getContext().inTable),true,'Clicking the rendered table enters that cell');
    await page.keyboard.press('Home');await page.keyboard.press('Shift+End');await page.keyboard.insertText('CELL_CHANGED');
   }
   await page.waitForTimeout(900);
   await page.locator('#mode').click();await frame.waitForFunction(()=>!window.rhwpStudio.documentStudio.isEditing());
   const savedView=await frame.evaluate(()=>window.rhwpStudio.documentStudio.captureView());
   await page.locator('#mode').click();await frame.waitForFunction(()=>window.rhwpStudio.documentStudio.isEditing());
   const editingView=await frame.evaluate(()=>window.rhwpStudio.documentStudio.captureView());
   assert.equal(savedView.zoom,editingView.zoom);assert.ok(Math.abs(savedView.scrollTop-editingView.scrollTop)<=2);
   await page.locator('#save').click();
   await page.waitForFunction(()=>window.__nativeClosed,null,{timeout:60000});
   const exported=await page.evaluate(()=>({base64:window.__saved,report:window.__saveReport,original:window.__saveOriginal,status:document.getElementById('status').textContent}));
   assert.ok(exported.base64,exported.status);assert.equal(exported.report.count,0);assert.equal(exported.original,true);
   const bytes=Buffer.from(exported.base64,'base64');assert.ok(bytes.length>1000);
   fs.writeFileSync(path.join(fixtures,`edited${phone?'-phone':''}.${format}`),bytes);
   const reopened=await frame.evaluate(async ({data,format})=>window.rhwpStudio.documentStudio.open(Uint8Array.from(atob(data),c=>c.charCodeAt(0)),`saved.${format}`),{data:exported.base64,format});
   assert.ok(reopened.pages>=1);assert.equal(reopened.format,format);
   await page.screenshot({path:path.join(output,`hangul-${format}-${phone?'phone':'tablet'}.png`),fullPage:true});
   assert.equal(external.length,0,`Document data must stay offline: ${external.join(',')}`);assert.deepEqual(errors,[]);
   results.push({format,phone,bytes:bytes.length,contentLoss:exported.report.count,canvas:true,keyboardEdit:true,koreanInput:true,reopened:true,tableCellEdit:format==='hwpx' && !phone,undoRedo:true,externalRequests:external.length,errors});
   await context.close();
  }
  fs.writeFileSync(path.join(output,'hangul-browser-results.json'),JSON.stringify(results,null,2));console.log(JSON.stringify(results,null,2));
 } catch(error) {
  fs.writeFileSync(path.join(output,'hangul-browser-failure.json'),JSON.stringify({message:error.message,stack:error.stack,completed:results},null,2));
  for(const context of browser.contexts())for(const page of context.pages())await page.screenshot({path:path.join(output,'hangul-browser-failure.png'),fullPage:true}).catch(()=>{});
  throw error;
 } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
