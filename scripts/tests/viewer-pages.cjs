const {chromium}=require('playwright');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const root=path.resolve(__dirname,'../..'),runtime=path.join(root,'.engine-cache/hangul/runtime/hangul');
const assets=path.join(root,'artifacts/open-fixtures/android-assets'),out=path.join(root,'artifacts/viewer');
fs.mkdirSync(out,{recursive:true});
const digest=b=>crypto.createHash('sha256').update(b).digest('hex');
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
 const context=await browser.newContext({viewport:{width:412,height:915},isMobile:true,hasTouch:true});
 const page=await context.newPage(),errors=[],external=[];
 page.on('pageerror',e=>errors.push(e.message));
 await context.addInitScript(()=>{
  window.__writes=0;
  window.DocumentStudio={describe:()=>JSON.stringify({name:'viewer-20.hwpx',format:'hwpx',openIntent:'VIEW'}),
   loaded:()=>window.__ready=true,loadFailed:e=>window.__failure=e,setEditing:()=>true,
   saveViewState:v=>window.__view=JSON.parse(v),beginWrite:()=>{window.__writes++;return false;},
   closeEditor:()=>{window.__closed=true},requestDiscard:()=>{}};
 });
 await context.route('**/*',route=>{
  const u=new URL(route.request().url());if(u.hostname!=='hangul.documentstudio.local'){external.push(u.href);return route.fulfill({status:403,body:'blocked'});}
  const f=u.pathname==='/document'?path.join(assets,'viewer-20.hwpx'):path.join(runtime,u.pathname==='/'?'index.html':decodeURIComponent(u.pathname).slice(1));
  if(!f.startsWith(runtime+path.sep)&&!f.startsWith(assets+path.sep))return route.fulfill({status:403,body:'blocked'});
  if(!fs.existsSync(f))return route.fulfill({status:404,body:'missing'});
  const type={'.html':'text/html','.js':'text/javascript','.css':'text/css','.wasm':'application/wasm','.woff2':'font/woff2','.woff':'font/woff','.ttf':'font/ttf','.json':'application/json'}[path.extname(f)]||'application/octet-stream';
  return route.fulfill({body:fs.readFileSync(f),contentType:type});
 });
 try {
  const before=digest(fs.readFileSync(path.join(assets,'viewer-20.hwpx'))),started=Date.now();
  await page.goto('https://hangul.documentstudio.local/');
  await page.waitForFunction(()=>window.__ready||window.__failure,null,{timeout:90000});
  assert.equal(await page.evaluate(()=>window.__failure),undefined);
  const firstReadyMs=Date.now()-started;
  const frame=page.frames().find(f=>f.url().includes('/engine/index.html'));
  const call=(method,...args)=>frame.evaluate(({method,args})=>window.rhwpStudio.documentStudio[method](...args),{method,args});
  assert.equal((await call('info')).pages,20,'The fixture contains twenty real layout pages');
  await page.locator('#page-number').getAttribute('aria-label').then(v=>assert.match(v,/20쪽/));
  const rendered=[];
  for(let i=0;i<20;i++){
   await call('goToPage',i);
   console.log('VIEWER_PAGE_ATTEMPT',i+1,await call('pageState'),await call('captureView'));
   await frame.waitForFunction(index=>window.rhwpStudio.documentStudio.pageState().page===index,i);
   await frame.waitForFunction(()=>{
    const box=document.getElementById('scroll-container').getBoundingClientRect();
    return Array.from(document.querySelectorAll('canvas.document-page-canvas')).some(c=>{
      const r=c.getBoundingClientRect();return c.width>0&&c.height>0&&r.bottom>box.top&&r.top<box.bottom;
    });
   });
   const raster=await call('pdfPage',i);
   rendered.push({page:i+1,hash:digest(Buffer.from(raster.png,'base64')),width:raster.width,height:raster.height});
   if([0,9,19].includes(i))await page.screenshot({path:path.join(out,'hwpx-page-'+(i+1)+'.png')});
  }
  assert.equal(new Set(rendered.map(r=>r.hash)).size,20,'Unique real pages cannot be duplicated first-page copies');
  await call('goToPage',0);
  const scroll=frame.locator('#scroll-container'),box=await scroll.boundingBox();
  await page.mouse.move(box.x+box.width*.65,box.y+box.height*.6);await page.mouse.wheel(0,2500);
  await frame.waitForFunction(()=>window.rhwpStudio.documentStudio.pageState().page>0);
  const scrolled=await call('pageState');assert.ok(scrolled.page>0);
  await call('goToPage',9);
  page.once('dialog',dialog=>dialog.accept('20'));await page.locator('#page-number').click();
  await frame.waitForFunction(()=>window.rhwpStudio.documentStudio.pageState().page===19);
  const client=await context.newCDPSession(page),z=(await call('captureView')).zoom;
  const cx=box.x+box.width/2,cy=box.y+box.height/2;
  await client.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:cx-40,y:cy,id:0},{x:cx+40,y:cy,id:1}]});
  for(const d of [50,65,80])await client.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:cx-d,y:cy,id:0},{x:cx+d,y:cy,id:1}]});
  await client.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
  await frame.waitForFunction(zoom=>window.rhwpStudio.documentStudio.captureView().zoom>zoom*1.5,z);
  const zoomed=await call('captureView');
  assert.equal((await call('pageState')).page,19);
  await frame.evaluate(()=>{const sc=document.getElementById('scroll-container');sc.scrollLeft=120;});
  assert.ok((await call('captureView')).scrollLeft>0,'Zoomed page can pan horizontally');
  await page.locator('#mode').click();await page.waitForFunction(()=>document.body.dataset.state==='EDITING'&&!document.getElementById('mode').disabled);
  await page.locator('#back').click();await page.waitForFunction(()=>document.body.dataset.state==='READING'&&!document.getElementById('mode').disabled);
  assert.equal((await call('pageState')).page,19);assert.equal(await call('dirty'),false);
  const view=await call('captureView');
  await page.setViewportSize({width:915,height:412});await page.waitForTimeout(250);
  await call('restoreView',view);assert.equal((await call('pageState')).page,19);
  await page.setViewportSize({width:412,height:915});await page.waitForTimeout(250);await call('restoreView',view);
  await call('fitPage');assert.equal((await call('pageState')).page,19);
  await call('retryPages');await page.waitForTimeout(100);
  const finalView=await call('captureView');
  await page.locator('#back').click();await page.waitForFunction(()=>window.__closed);
  assert.equal(await page.evaluate(()=>window.__writes),0);assert.equal(digest(fs.readFileSync(path.join(assets,'viewer-20.hwpx'))),before);
  assert.deepEqual(errors,[]);assert.deepEqual(external,[]);
  const result={pages:20,uniqueRenderedPages:20,continuousScroll:true,directLastPage:true,actualChromiumTouchPinch:true,
   panAfterZoom:true,modeSwitchPagePreserved:true,phoneLandscapeViewport:true,fitPage:true,retry:true,documentWrites:0,
   originalUnchanged:true,firstReadyMs,zoomBefore:z,zoomAfter:zoomed.zoom,finalView,rendered,
   limitations:['Chromium mobile viewport; not physical Android or Android screen rotation']};
  fs.writeFileSync(path.join(out,'hwpx-20-results.json'),JSON.stringify(result,null,2));console.log('HWPX_20_VIEWER_ACCEPTANCE',JSON.stringify(result));
 } finally {await context.close();await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
