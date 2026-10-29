import type { WasmBridge } from './core/wasm-bridge';
import type { DocumentPosition } from './core/types';
import type { EditCommand, TextMutationEffects } from './engine/command';

/** The pinned engine records selection deletion and committed IME text separately.
 * Group those exact two existing commands; preserve fragments, cell paths and formatting on undo. */
export class StudioImeReplacementCommand implements EditCommand {
 readonly type='studioImeReplacement';
 readonly timestamp=Date.now();
 private applied=2;
 constructor(private readonly commands:EditCommand[]) {}
 execute(wasm:WasmBridge):DocumentPosition {
   let result:DocumentPosition|undefined;
   while(this.applied<this.commands.length){result=this.commands[this.applied].execute(wasm);this.applied++;}
   if(!result)throw new Error('조합 입력 이력 상태가 올바르지 않습니다.');return result;
 }
 undo(wasm:WasmBridge):DocumentPosition {
   let result:DocumentPosition|undefined;
   while(this.applied>0){result=this.commands[this.applied-1].undo(wasm);this.applied--;}
   if(!result)throw new Error('조합 입력 복원 상태가 올바르지 않습니다.');return result;
 }
 mergeWith():null{return null;}
 retainOnFailure():boolean{return this.applied>0;}
 snapshotResourceCount():number{return this.commands.reduce((n,c)=>n+(c.snapshotResourceCount?.()||0),0);}
 discard(wasm:WasmBridge):void{for(const command of this.commands)command.discard?.(wasm);}
 consumeTextMutationEffects():TextMutationEffects {
   return {documentPaginationPending:false,flowChanged:true,paginationCompleted:true};
 }
}
