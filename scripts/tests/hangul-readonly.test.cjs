const test=require('node:test');
const assert=require('node:assert/strict');
const listeners=new Map();
global.document={documentElement:{dataset:{}},querySelectorAll:()=>[],addEventListener:(name,callback)=>listeners.set(name,callback)};
const policy=require('../../web/hangul/host-readonly.ts');
class Input {
 onInput(){return 'mutated';} onCompositionStart(){return 'composition';}
 onCut(){return 'cut';} onPaste(){return 'paste';} focusTextarea(){return 'focus';}
 performUndo(){return 'undo';} executeOperation(){return 'operation';}
}
class Dispatcher {
 dispatchWithResult(){return {ok:true};} isEnabled(){return true;}
}
policy.installStudioInput(Input,Dispatcher);
class Bridge {
 constructor(){this.mutations=0;}
 getDocumentInfo(){return {pages:1};}
 initialize(){this.installMeasureTextWidth();return this.getDocumentInfo();}
 installMeasureTextWidth(){this.rendererCallbackInstalled=true;}
 setPageMargin(){this.mutations++;return true;}
 findOrCreateFontId(){this.mutations++;return 1;}
 futureUnknownMutation(){this.mutations++;return true;}
 borrowDocumentHandle(){return {exportHwp(){return new Uint8Array([1]);},setFieldValue(){throw new Error('raw mutation escaped');}};}
 setCaretPosition(){this.mutations++;}
}
test('VIEW refuses actual bridge mutations and unknown methods',()=>{
 policy.studioSetEditing(false);const bridge=new Bridge();policy.installStudioBridge(bridge);
 assert.deepEqual(bridge.getDocumentInfo(),{pages:1});
 for(const method of ['setPageMargin','findOrCreateFontId','futureUnknownMutation'])assert.throws(()=>bridge[method](),/읽기 모드/);
 bridge.setCaretPosition();assert.equal(bridge.mutations,0);
 const raw=bridge.borrowDocumentHandle();assert.deepEqual(raw.exportHwp(),new Uint8Array([1]));
 assert.throws(()=>raw.setFieldValue(),/읽기 모드/);
});
test('VIEW blocks undo, structure changes and unknown commands',()=>{
 policy.studioSetEditing(false);const dispatcher=new Dispatcher();
 for(const id of ['edit:undo','edit:redo','table:create','view:form-mode','future:mutation']){
  assert.equal(dispatcher.isEnabled(id),false);assert.equal(dispatcher.dispatchWithResult(id).ok,false);
 }
 for(const id of ['edit:find','edit:copy','view:zoom-in'])assert.equal(dispatcher.dispatchWithResult(id).ok,true);
});
test('input, IME, clipboard and focus are blocked in VIEW',()=>{
 policy.studioSetEditing(false);const input=new Input();
 for(const method of ['onInput','onCompositionStart','onCut','onPaste','focusTextarea','performUndo','executeOperation'])assert.equal(input[method](),undefined);
});
test('only host-approved EDIT enables existing engine operations',()=>{
 policy.studioSetEditing(true);const bridge=new Bridge();policy.installStudioBridge(bridge);
 assert.equal(bridge.setPageMargin(),true);assert.equal(bridge.mutations,1);
 assert.equal(new Input().onInput(),'mutated');assert.equal(new Dispatcher().dispatchWithResult('table:create').ok,true);
 policy.studioSetEditing(false);
});
test('document loading is scoped and never grants input editing',async()=>{
 const bridge=new Bridge();policy.installStudioBridge(bridge);
 await policy.studioDocumentLoad(async()=>{assert.equal(bridge.setPageMargin(),true);assert.equal(new Input().onInput(),undefined);});
 assert.throws(()=>bridge.setPageMargin(),/읽기 모드/);
});

test('VIEW permits renderer bootstrap helper without granting later document writes',()=>{
 policy.studioSetEditing(false);const bridge=new Bridge();policy.installStudioBridge(bridge);
 assert.deepEqual(bridge.initialize(),{pages:1});assert.equal(bridge.rendererCallbackInstalled,true);
 assert.equal(policy.studioIsEditing(),false);assert.equal(bridge.mutations,0);
 assert.throws(()=>bridge.setPageMargin(),/setPageMargin/);
 assert.throws(()=>bridge.futureUnknownMutation(),/futureUnknownMutation/);
});
