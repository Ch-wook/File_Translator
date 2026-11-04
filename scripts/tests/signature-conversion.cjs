const {chromium}=require('playwright');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../..'),runtime=path.join(root,'.engine-cache/hangul/runtime/hangul'),fixtures=path.join(root,'core/build/visual-fixtures');
const out=path.join(root,'artifacts/signature-conversion');fs.mkdirSync(out,{recursive:true});
const mime={'.html':'text/html','.js':'text/javascript','.css':'text/css','.wasm':'application/wasm','.woff2':'font/woff2','.woff':'font/woff','.ttf':'font/ttf','.svg':'image/svg+xml','.png':'image/png','.json':'application/json'};
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--no-sandbox']});const results=[];
 try{for(const format of ['hwp','hwpx']){
  const context=await browser.newContext({viewport:{width:412,height:915},isMobile:true,hasTouch:true});
  const page=await context.newPage(),errors=[],external=[],engineErrors=[];
  page.on('pageerror',e=>errors.push(e.message));page.on('console',m=>{if(m.type()==='error'){console.error('ENGINE_CONSOLE',m.text());if(/\[EventBus\]|\[table\]|커맨드 실행 실패/.test(m.text()))engineErrors.push(m.text());}});
  await context.addInitScript(({format})=>{
   let chunks=[];window.__clip={text:'',html:''};window.__writeCount=0;window.__signatureLibrary='[]';window.__signatureWriteFails=false;
   window.DocumentStudio={describe:()=>JSON.stringify({name:'모바일 서식.'+format,format,openIntent:'VIEW'}),
    loaded:()=>window.__ready=true,loadFailed:e=>window.__failure=e,requestDiscard:()=>{},
    readSignatures:()=>window.__signatureLibrary,
    writeSignatures:value=>{if(window.__signatureWriteFails)return false;window.__signatureLibrary=value;return true;},
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

  await page.evaluate(()=>{window.__signatureLibrary='{"error":"damaged"}';window.documentStudioHost.signature();});
  await page.locator('.studio-signature').getByRole('alert').filter({hasText:'서명 보관함을 확인하지 못했습니다.'}).waitFor({state:'visible'});
  assert.equal(await page.locator('.studio-signature').getByRole('button',{name:'내 서명에 저장',exact:true}).isDisabled(),true);
  await page.locator('.studio-signature').getByRole('button',{name:'취소',exact:true}).click();
  await page.evaluate(()=>window.__signatureLibrary='[]');
  await call('navigate',true);await call('plainText','\nSIGNATURE_TEXT_PRESERVE\n');await call('createTable',2,2);
  await call('plainText','SIGNATURE_CELL');await call('moveCell',true);await call('plainText','OTHER_CELL_PRESERVE');
  await call('find','SIGNATURE_CELL');await call('focus');
  const before=(await call('state')).cell;assert.equal(before.dims.rowCount,2);
  const insertion=await frame.locator('.caret').filter({visible:true}).first().boundingBox();assert.ok(insertion);
  await page.evaluate(()=>window.documentStudioHost.signature());
  const panel=page.locator('.studio-signature canvas');await panel.waitFor();
  await page.locator('.studio-signature').getByRole('button',{name:'완료',exact:true}).click();
  await page.locator('.studio-signature').getByRole('alert').filter({hasText:'서명을 먼저 작성하세요.'}).waitFor({state:'visible'});
  const box=await panel.boundingBox(),cdp=await context.newCDPSession(page);
  const touch=(type,x,y)=>cdp.send('Input.dispatchTouchEvent',{type,touchPoints:type==='touchEnd'?[]:[{x,y,id:1,radiusX:2,radiusY:2,force:1}]});
  const draw=async(a,b)=>{await touch('touchStart',a.x,a.y);for(let i=1;i<=12;i++)await touch('touchMove',a.x+(b.x-a.x)*i/12,a.y+(b.y-a.y)*i/12);await touch('touchEnd');};
  await draw({x:box.x+30,y:box.y+90},{x:box.x+box.width-30,y:box.y+130});
  await page.locator('.studio-signature').getByRole('button',{name:'파랑',exact:true}).click();
  await draw({x:box.x+80,y:box.y+60},{x:box.x+box.width-70,y:box.y+180});
  await page.locator('.studio-signature').getByRole('button',{name:'실행 취소',exact:true}).click();
  await page.locator('.studio-signature').getByRole('button',{name:'다시 실행',exact:true}).click();
  page.once('dialog',d=>d.accept('검증 서명'));
  await page.locator('.studio-signature').getByRole('button',{name:'내 서명에 저장',exact:true}).click();
  assert.equal(await page.locator('.studio-signature img[alt="검증 서명"]').count(),1);
  await page.evaluate(()=>window.__signatureWriteFails=true);
  page.once('dialog',d=>d.accept('저장 실패 서명'));
  await page.locator('.studio-signature').getByRole('button',{name:'내 서명에 저장',exact:true}).click();
  assert.equal(await page.evaluate(()=>JSON.parse(window.__signatureLibrary).length),1);
  assert.equal(await page.locator('.studio-signature img').count(),1);
  page.once('dialog',d=>d.accept());
  await page.locator('.studio-signature').getByRole('button',{name:'삭제',exact:true}).click();
  assert.equal(await page.locator('.studio-signature img').count(),1);
  assert.equal(await page.evaluate(()=>JSON.parse(window.__signatureLibrary).length),1);
  await page.evaluate(()=>window.__signatureWriteFails=false);
  page.once('dialog',d=>d.accept());
  await page.locator('.studio-signature').getByRole('button',{name:'삭제',exact:true}).click();
  assert.equal(await page.locator('.studio-signature img').count(),0);
  assert.equal(await page.evaluate(()=>JSON.parse(window.__signatureLibrary).length),0);
  await page.locator('.studio-signature').getByRole('button',{name:'완료',exact:true}).click();
  await frame.waitForFunction(()=>window.rhwpStudio.documentStudio.mobile.state().placement);
  const bounds=await page.locator('#engine').boundingBox();const start={x:Math.max(bounds.x+40,Math.min(bounds.x+bounds.width-100,insertion.x+10)),y:Math.max(bounds.y+40,Math.min(bounds.y+bounds.height-100,insertion.y+10))};
  await draw(start,{x:start.x+60,y:start.y+30});
  await frame.waitForFunction(()=>window.rhwpStudio.documentStudio.mobile.objects().some(o=>o.type==='image'));
  assert.equal(await call('count','SIGNATURE_CELL'),1);assert.equal(await call('count','OTHER_CELL_PRESERVE'),1);
  await page.waitForFunction(()=>!document.getElementById('save').disabled);
  await page.evaluate(()=>window.documentStudioHost.checkpoint());
  await page.waitForFunction(()=>window.__saved);
  const data=await page.evaluate(()=>window.__saved);fs.writeFileSync(path.join(out,'signature.'+format),Buffer.from(data,'base64'));
  const beforePages=await frame.evaluate(()=>{const e=window.rhwpStudio.documentStudio;return Array.from({length:e.info().pages},(_,i)=>e.pdfPage(i));});
  for(const [i,p] of beforePages.entries())fs.writeFileSync(path.join(out,format+'-before-'+i+'.png'),Buffer.from(p.png,'base64'));
  await frame.evaluate(async({data,format})=>window.rhwpStudio.documentStudio.open(Uint8Array.from(atob(data),c=>c.charCodeAt(0)),'signed.'+format),{data,format});
  assert.equal(await call('count','SIGNATURE_CELL'),1);assert.equal(await call('count','OTHER_CELL_PRESERVE'),1);
  await call('find','SIGNATURE_CELL');assert.equal((await call('state')).cell.dims.rowCount,2);assert.equal((await call('state')).cell.dims.colCount,2);
  const afterPages=await frame.evaluate(()=>{const e=window.rhwpStudio.documentStudio;return Array.from({length:e.info().pages},(_,i)=>e.pdfPage(i));});
  assert.equal(afterPages.length,beforePages.length);
  for(const [i,p] of afterPages.entries())fs.writeFileSync(path.join(out,format+'-after-'+i+'.png'),Buffer.from(p.png,'base64'));
  fs.writeFileSync(path.join(out,format+'-pages.json'),JSON.stringify(afterPages.map(({width,height})=>({width,height}))));
  await frame.evaluate(()=>window.rhwpStudio.documentStudio.setEditing(true));
  await page.evaluate(()=>{
    window.__pdfAborted=false;window.__pdfFinished=false;
    Object.assign(window.DocumentStudio,{beginPdf:()=>{window.__holdNextPdfYield=true;return true;},pdfPage:()=>true,finishPdf:()=>{window.__pdfFinished=true;return JSON.stringify({ok:true});},abortPdf:()=>window.__pdfAborted=true});
    const original=window.setTimeout.bind(window);window.__originalTimeout=original;
    window.setTimeout=(fn,delay,...args)=>{
      if(delay===0&&window.__holdNextPdfYield){window.__holdNextPdfYield=false;window.__pdfYieldWaiting=true;window.__releasePdfYield=()=>original(fn,0,...args);return 0;}
      return original(fn,delay,...args);
    };
    window.__pdfTask=window.documentStudioHost.writePdf();
  });
  const cancel=page.getByRole('button',{name:'변환 취소',exact:true});await cancel.waitFor({state:'visible'});
  await page.waitForFunction(()=>window.__pdfYieldWaiting);
  assert.equal(await frame.evaluate(()=>window.rhwpStudio.documentStudio.isEditing()),false);
  await assert.rejects(call('plainText','PDF_EXPORT_MUTATION'));
  await cancel.click();await page.evaluate(()=>window.__releasePdfYield());await page.waitForFunction(()=>window.__pdfAborted&&!document.getElementById('save').disabled);
  assert.equal(await page.evaluate(()=>window.__pdfFinished),false);assert.equal(await call('count','PDF_EXPORT_MUTATION'),0);
  assert.equal(await frame.evaluate(()=>window.rhwpStudio.documentStudio.isEditing()),true);
  await page.evaluate(()=>window.setTimeout=window.__originalTimeout);
  assert.deepEqual(errors,[]);assert.deepEqual(external,[]);
  results.push({format,signatureTouch:true,damagedLibraryBlocked:true,failedLibraryPersistenceRetainsVisibleAndSavedEntries:true,visibleSignatureErrors:true,pdfCancellation:true,exportMutationLocked:true,tableTextPreserved:true,tableStructurePreserved:true,pages:afterPages.length,savedAndReopened:true});
  await context.close();
 }
 fs.writeFileSync(path.join(out,'signature-results.json'),JSON.stringify(results,null,2));console.log('SIGNATURE_RESULTS',JSON.stringify(results));
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
