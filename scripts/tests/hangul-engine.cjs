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
  for(const {format,phone} of [{format:'hwp',phone:false},{format:'hwpx',phone:false},{format:'hwpx',phone:true}]) {
   const context=await browser.newContext({viewport:phone?{width:412,height:915}:{width:1360,height:950},isMobile:phone,hasTouch:phone});
   const page=await context.newPage();const errors=[],external=[];
   page.on('pageerror',e=>errors.push(e.message));
   await context.addInitScript(({format})=>{
    let chunks=[];window.__nativeReady=false;window.__nativeClosed=false;
    window.DocumentStudio={describe:()=>JSON.stringify({name:`fixture.${format}`,format}),loaded:()=>{window.__nativeReady=true;},loadFailed:e=>{window.__nativeFailure=e;},requestDiscard:()=>{},
     beginWrite:length=>{chunks=[];window.__saveLength=length;return true;},writeChunk:chunk=>{chunks.push(chunk);return true;},
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
   await page.waitForFunction(()=>window.__nativeReady || window.__nativeFailure,{timeout:90000});
   assert.equal(await page.evaluate(()=>window.__nativeFailure),undefined,JSON.stringify(errors));
   const frame=page.frames().find(f=>f.url().includes('/engine/index.html'));assert.ok(frame,'The dedicated document renderer loaded');
   const canvas=frame.locator('canvas.document-page-canvas').first();await canvas.waitFor({timeout:30000});
   const box=await canvas.boundingBox();assert.ok(box.width>200 && box.height>400,'A paginated document canvas is displayed');
   if(phone) await page.touchscreen.tap(box.x+110*(box.width/794),box.y+100*(box.width/794)); else await page.mouse.click(box.x+110,box.y+100);
   await page.keyboard.press('Control+Home');await page.keyboard.press('Home');await page.keyboard.press('Shift+End');
   await page.keyboard.insertText('PAGE_EDIT_CHANGED 한글 직접 편집');
   await frame.waitForFunction(()=>window.rhwpStudio.automation.getContext().canUndo);
   await page.keyboard.press('Control+z');await frame.waitForFunction(()=>window.rhwpStudio.automation.getContext().canRedo);
   await page.keyboard.press('Control+Shift+z');
   if(format==='hwpx' && !phone) {
    await page.mouse.click(box.x+145,box.y+184);
    assert.equal(await frame.evaluate(()=>window.rhwpStudio.automation.getContext().inTable),true,'Clicking the rendered table enters that cell');
    await page.keyboard.press('Home');await page.keyboard.press('Shift+End');await page.keyboard.insertText('CELL_CHANGED');
   }
   await page.waitForTimeout(900);
   await page.locator('#save').click();
   await page.waitForFunction(()=>window.__nativeClosed,{timeout:60000});
   const exported=await page.evaluate(()=>({base64:window.__saved,report:window.__saveReport,original:window.__saveOriginal,status:document.getElementById('status').textContent}));
   assert.ok(exported.base64,exported.status);assert.equal(exported.report.count,0);assert.equal(exported.original,true);
   const bytes=Buffer.from(exported.base64,'base64');assert.ok(bytes.length>1000);
   fs.writeFileSync(path.join(fixtures,`edited${phone?'-phone':''}.${format}`),bytes);
   const reopened=await frame.evaluate(async ({data,format})=>window.rhwpStudio.documentStudio.open(Uint8Array.from(atob(data),c=>c.charCodeAt(0)),`saved.${format}`),{data:exported.base64,format});
   assert.ok(reopened.pages>=1);assert.equal(reopened.format,format);
   await page.screenshot({path:path.join(output,`hangul-${format}-${phone?'phone':'tablet'}.png`),fullPage:true});
   assert.equal(external.length,0,`Document data must stay offline: ${external.join(',')}`);
   results.push({format,phone,bytes:bytes.length,contentLoss:exported.report.count,canvas:true,keyboardEdit:true,koreanInput:true,reopened:true,tableCellEdit:format==='hwpx' && !phone,undoRedo:true,externalRequests:external.length,errors});
   await context.close();
  }
  fs.writeFileSync(path.join(output,'hangul-browser-results.json'),JSON.stringify(results,null,2));console.log(JSON.stringify(results,null,2));
 } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
