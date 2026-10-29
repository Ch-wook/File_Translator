const {chromium}=require('playwright');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../..'),runtime=path.join(root,'artifacts/ui-baseline-runtime/hangul'),out=path.join(root,'artifacts/ui-audit');
const mime={'.html':'text/html','.js':'text/javascript','.css':'text/css','.wasm':'application/wasm','.woff2':'font/woff2','.woff':'font/woff','.ttf':'font/ttf','.svg':'image/svg+xml','.png':'image/png','.json':'application/json'};
(async()=>{
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
  await page.locator('#mode').click();await page.waitForFunction(()=>document.body.dataset.state==='EDITING'&&!document.getElementById('mode').disabled);
  const metrics=()=>page.evaluate(()=>Object.fromEntries(['header','nav','#status-row','#engine','#groups','#tools','#mobile-panel','#edit-dock'].map(key=>{const e=document.querySelector(key),r=e.getBoundingClientRect();return[key,{top:r.top,bottom:r.bottom,height:r.height,width:r.width,hidden:e.hidden}];})));
  const closed=await metrics();await page.screenshot({path:path.join(out,'baseline-web-'+viewport.width+'x'+viewport.height+'.png')});
  await page.locator('#tools').getByRole('button',{name:'글자 모양',exact:true}).click();const opened=await metrics();
  results.push({viewport,closed,opened,fonts:await page.getByLabel('글꼴',{exact:true}).locator('option').allTextContents()});
  await context.close();
 }}finally{await browser.close();fs.writeFileSync(path.join(out,'baseline-web-metrics.json'),JSON.stringify(results,null,2));console.log('BASELINE_WEB_METRICS',JSON.stringify(results));}
})().catch(e=>{console.error(e);process.exitCode=1;});
