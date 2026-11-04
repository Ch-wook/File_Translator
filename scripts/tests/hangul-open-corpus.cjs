const {chromium}=require('playwright');
const fs=require('node:fs'),path=require('node:path');
const root=path.resolve(__dirname,'../..'),runtime=path.join(root,'.engine-cache/hangul/runtime/hangul'),corpus=path.join(root,'artifacts/open-corpus');
const mime={'.html':'text/html','.js':'text/javascript','.css':'text/css','.wasm':'application/wasm','.woff2':'font/woff2','.woff':'font/woff','.ttf':'font/ttf','.svg':'image/svg+xml','.png':'image/png','.json':'application/json'};
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--no-sandbox']});const results=[];
 try{for(const name of fs.readdirSync(corpus).filter(n=>n.endsWith('.hwp')).sort()){
  const context=await browser.newContext({viewport:{width:412,height:915},isMobile:true,hasTouch:true});
  const page=await context.newPage();
  await context.addInitScript(({name})=>{window.__writes=0;window.DocumentStudio={
   describe:()=>JSON.stringify({name,format:'hwp',openIntent:'VIEW'}),loaded:()=>window.__ready=true,loadFailed:e=>window.__failure=e,
   saveViewState:()=>{},setEditing:()=>false,requestDiscard:()=>{},beginWrite:()=>{window.__writes++;return false;},
   closeEditor:()=>{}};},{name});
  await context.route('**/*',route=>{
   const url=new URL(route.request().url());if(url.hostname!=='hangul.documentstudio.local')return route.fulfill({status:403,body:'blocked'});
   const file=url.pathname==='/document'?path.join(corpus,name):path.join(runtime,url.pathname==='/'?'index.html':decodeURIComponent(url.pathname).replace(/^\//,''));
   if(!fs.existsSync(file))return route.fulfill({status:404,body:'missing'});
   return route.fulfill({body:fs.readFileSync(file),contentType:mime[path.extname(file)]||'application/octet-stream'});
  });
  let result;
  try{
   await page.goto('https://hangul.documentstudio.local/');
   await page.waitForFunction(()=>window.__ready||window.__failure,null,{timeout:30000});
   result=await page.evaluate(()=>({ready:!!window.__ready,error:window.__failure||null,status:document.querySelector('#status').textContent,writes:window.__writes}));
  }catch(e){result={ready:false,error:e.message};}
  results.push({name,...result});console.log('OPEN_ENGINE',JSON.stringify(results.at(-1)));
  await context.close();
 }}finally{await browser.close();fs.writeFileSync(path.join(corpus,'engine-results.json'),JSON.stringify(results,null,2));}
})().catch(e=>{console.error(e);process.exitCode=1;});
