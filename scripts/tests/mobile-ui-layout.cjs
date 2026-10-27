const {chromium}=require('playwright');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../..'),runtime=path.join(root,'.engine-cache/hangul/runtime/hangul'),out=path.join(root,'artifacts/mobile-ui');
const mime={'.html':'text/html','.js':'text/javascript','.css':'text/css','.wasm':'application/wasm','.woff2':'font/woff2','.woff':'font/woff','.ttf':'font/ttf','.svg':'image/svg+xml','.png':'image/png','.json':'application/json'};
(async()=>{
 fs.mkdirSync(out,{recursive:true});
 const browser=await chromium.launch({headless:true,args:['--no-sandbox']});const results=[];
 try{for(const viewport of [{width:412,height:915},{width:360,height:800},{width:412,height:500}]){
  const context=await browser.newContext({viewport,isMobile:true,hasTouch:true});const page=await context.newPage();
  await context.addInitScript(()=>{window.DocumentStudio={describe:()=>JSON.stringify({name:'baseline.hwpx',format:'hwpx',openIntent:'VIEW'}),loaded:()=>window.__ready=true,loadFailed:e=>window.__failure=e,setEditing:()=>true,saveViewState:()=>{},writeClipboard:()=>true,readClipboard:()=>'{"text":"","html":""}'};});
  await context.route('**/*',route=>{
   const u=new URL(route.request().url());if(u.hostname!=='hangul.documentstudio.local')return route.fulfill({status:403,body:'blocked'});
   const file=u.pathname==='/document'?path.join(root,'core/build/visual-fixtures/fixture.hwpx'):path.join(runtime,u.pathname==='/'?'index.html':decodeURIComponent(u.pathname).replace(/^\//,''));
   return fs.existsSync(file)?route.fulfill({body:fs.readFileSync(file),contentType:mime[path.extname(file)]||'application/octet-stream'}):route.fulfill({status:404,body:'missing'});
  });
  await page.goto('https://hangul.documentstudio.local/');
  await page.waitForFunction(()=>window.__ready||window.__failure,null,{timeout:90000});assert.equal(await page.evaluate(()=>window.__failure),undefined);
  const frameForPinch=page.frames().find(f=>f.url().includes('/engine/index.html'));
  const cdp=await context.newCDPSession(page);
  async function pinchCheck(){
    const before=await frameForPinch.evaluate(()=>window.rhwpStudio.documentStudio.captureView());
    const selected=await frameForPinch.evaluate(()=>window.rhwpStudio.documentStudio.mobile.capture().range);
    const box=await page.locator('#engine').boundingBox(),cx=box.x+box.width/2,cy=box.y+Math.min(230,box.height/2);
    const points=d=>[{x:cx-d,y:cy,radiusX:3,radiusY:3},{x:cx+d,y:cy,radiusX:3,radiusY:3}];
    await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:points(35)});
    for(const d of [42,50,60,70])await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:points(d)});
    await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
    const after=await frameForPinch.evaluate(()=>window.rhwpStudio.documentStudio.captureView());
    assert.ok(after.zoom>before.zoom*1.5,JSON.stringify({before,after}));
    assert.deepEqual(await frameForPinch.evaluate(()=>window.rhwpStudio.documentStudio.mobile.capture().range),selected);
    assert.equal(await frameForPinch.evaluate(()=>window.rhwpStudio.documentStudio.dirty()),false);
    await frameForPinch.evaluate(view=>window.rhwpStudio.documentStudio.restoreView(view),before);
  }
  await pinchCheck();
  await page.locator('#mode').click();await page.waitForFunction(()=>document.body.dataset.state==='EDITING'&&!document.getElementById('mode').disabled);
  const metrics=()=>page.evaluate(()=>Object.fromEntries(['header','#status-row','#engine','#groups','#tools','#mobile-panel','#edit-dock','#detail-sheet'].map(key=>{const e=document.querySelector(key),r=e.getBoundingClientRect();return[key,{top:r.top,bottom:r.bottom,height:r.height,width:r.width,hidden:e.hidden}];})));
  await pinchCheck();
  const closed=await metrics();await page.screenshot({path:path.join(out,'compact-web-'+viewport.width+'x'+viewport.height+'.png')});
  assert.equal(closed.header.height,48);assert.equal(closed['#edit-dock'].height,48);assert.equal(closed['#engine'].height,viewport.height-96);
  await page.locator('#groups').getByRole('button',{name:'글자',exact:true}).click();const opened=await metrics();
  assert.ok(opened['#detail-sheet'].height<=Math.min(viewport.height*.38,300)+1);
  const frame=page.frames().find(f=>f.url().includes('/engine/index.html'));
  assert.equal(await frame.locator('#h-ruler').isVisible(),false);
  await page.getByRole('button',{name:/^글꼴 선택/}).click();
  const fonts=await page.getByRole('option').allTextContents();assert.ok(fonts.length>2);
  await page.getByLabel('글꼴 검색').fill('not-a-real-font');assert.equal(await page.getByRole('option').count(),0);
  await page.getByRole('button',{name:'설정 닫기',exact:true}).click();assert.equal((await metrics())['#engine'].height,viewport.height-96);
  await page.screenshot({path:path.join(out,'compact-web-'+viewport.width+'x'+viewport.height+'.png')});
  results.push({viewport,closed,opened,fonts});
  await context.close();
 }}finally{await browser.close();fs.writeFileSync(path.join(out,'compact-web-metrics.json'),JSON.stringify(results,null,2));console.log('COMPACT_WEB_METRICS',JSON.stringify(results));}
})().catch(e=>{console.error(e);process.exitCode=1;});
