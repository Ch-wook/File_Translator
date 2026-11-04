const assert=require('node:assert/strict');
/** Pause a real engine export; both save failure and overlapping close must retain data. */
module.exports=async({page,prepareLatest,repairLatest})=>{
 await page.waitForFunction(()=>!document.getElementById('save').disabled);
 await page.evaluate(()=>{
  const e=document.getElementById('engine').contentWindow.rhwpStudio.documentStudio;
  window.__savedBeforeFailure=window.__saved;
  const finish=DocumentStudio.finishWrite;
  DocumentStudio.finishWrite=report=>{
   if(window.__rejectNextWrite){window.__rejectNextWrite=false;return JSON.stringify({ok:false,error:'Injected checkpoint failure'});}
   return finish(report);
  };
  const close=DocumentStudio.closeEditor;window.__closeCalls=0;
  DocumentStudio.closeEditor=value=>{window.__closeCalls++;close(value);};
  window.__startHeldCheckpoint=()=>{
   window.__exportEntered=false;
   const original=e.exportDocument.bind(e);let release;
   const gate=new Promise(resolve=>release=resolve);window.__releaseExport=release;
   e.exportDocument=async(...args)=>{e.exportDocument=original;window.__exportEntered=true;await gate;return original(...args);};
   window.__checkpointWork=documentStudioHost.checkpoint();
  };
  e.mobile.char({spacings:Array(7).fill(10)});
  window.__rejectNextWrite=true;window.__startHeldCheckpoint();
 });
 await page.waitForFunction(()=>window.__exportEntered);
 await page.evaluate(()=>{document.getElementById('save').click();document.getElementById('save').click();window.__releaseExport();});
 await page.evaluate(()=>window.__checkpointWork);
 await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
 assert.equal(await page.evaluate(()=>Boolean(window.__closed)),false,'A failed autosave must not close the document');
 assert.equal(await page.evaluate(()=>window.__saved),await page.evaluate(()=>window.__savedBeforeFailure),'A rejected checkpoint retains the prior saved bytes');
 await page.evaluate(()=>window.__startHeldCheckpoint());
 await page.waitForFunction(()=>window.__exportEntered);
 if(prepareLatest)await prepareLatest();
 else await page.evaluate(()=>document.getElementById('engine').contentWindow.rhwpStudio.documentStudio.mobile.char({spacings:Array(7).fill(12)}));
 await page.evaluate(()=>{document.getElementById('save').click();document.getElementById('save').click();window.__releaseExport();});
 console.log('CLOSE_RACE_REQUEST_WHILE_EXPORT_HELD');
 if(prepareLatest){
  await page.waitForFunction(()=>{const s=documentStudioHost.operationState();return !s.closing&&!s.pending;});
  assert.equal(await page.evaluate(()=>Boolean(window.__closed)),false,'A failed stale formatting command must block closing');
  assert.equal(await page.evaluate(()=>document.getElementById('status').dataset.error),'true','Explain the failed formatting request');
  assert.equal(await page.evaluate(()=>window.__closeCalls),0);
  await repairLatest();
  await page.evaluate(()=>{document.getElementById('save').click();document.getElementById('save').click();});
 }
 await page.waitForFunction(()=>window.__closed,null,{timeout:15000});
 assert.equal(await page.evaluate(()=>window.__closeCalls),1,'Overlapping save/close requests close exactly once');
 assert.equal(await page.evaluate(()=>window.__saveOriginal),true);
 return {queuedCloseJoinsAutosave:true,failedClosePreservesWork:true,duplicateCloseCoalesced:true,failedFormatBlocksClose:Boolean(prepareLatest)};
};
