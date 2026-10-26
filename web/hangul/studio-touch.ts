// Touch gestures delegate selection/hit testing to the pinned engine.
import { studioIsEditing } from './host-readonly';
export function installStudioTouch(getInput:()=>any,eventBus:any) {
 const layer=document.createElement('div');layer.id='studio-touch-layer';
 layer.style.cssText='position:fixed;inset:0;pointer-events:none;z-index:45';
 const handles=['start','end'].map((side)=>{
   const b=document.createElement('button');b.type='button';b.className='studio-selection-handle';b.setAttribute('aria-label',side==='start'?'선택 시작 핸들':'선택 끝 핸들');b.dataset.side=side;
   b.style.cssText='position:fixed;width:44px;height:44px;border:0;border-radius:22px;background:transparent;touch-action:none;pointer-events:auto;padding:0;display:none';
   b.innerHTML='<span style="display:block;margin:8px auto;width:18px;height:18px;border-radius:50%;background:#245ca8;border:2px solid white;box-shadow:0 1px 4px #3458"></span>';
   layer.append(b);return b;
 });
 const menu=document.createElement('div');menu.id='studio-selection-menu';menu.style.cssText='position:fixed;max-width:calc(100vw - 16px);display:none;gap:2px;overflow-x:auto;background:white;border:1px solid #ccd7e3;border-radius:10px;box-shadow:0 3px 12px #2343;pointer-events:auto';
 for(const [label,action] of [['복사','copy'],['잘라내기','cut'],['붙여넣기','paste'],['굵게','bold'],['밑줄','underline'],['글자색','colors'],['크기','character']]) {
   const b=document.createElement('button');b.textContent=label;b.type='button';
   b.style.cssText='min-height:44px;min-width:44px;border:0;background:white;color:#245ca8;font:13px system-ui;white-space:nowrap;padding:6px 10px';
   b.addEventListener('pointerdown',e=>e.preventDefault());b.onclick=()=>{(window.parent as any).documentStudioHost?.mobileAction?.(action);};
   menu.append(b);
 }
 layer.append(menu);document.body.append(layer);
 let frame=0,drag:any=null,press:any=null,pressTimer:any=0,suppressUntil=0;
 const input=()=>getInput();
 function point(rect:any,ih:any) {
   const sc=ih.container.querySelector('#scroll-content') as HTMLElement;if(!sc)return null;
   const box=sc.getBoundingClientRect(),zoom=ih.viewportManager.getZoom(),vs=ih.virtualScroll;
   return {x:box.left+vs.getPageLeftResolved(rect.pageIndex,sc.clientWidth)+rect.x*zoom,y:box.top+vs.getPageOffset(rect.pageIndex)+(rect.y+rect.height)*zoom};
 }
 function rectAt(pos:any,ih:any) {
   const w=ih.wasm;
   return pos.parentParaIndex===undefined?w.getCursorRect(pos.sectionIndex,pos.paragraphIndex,pos.charOffset):
     pos.cellPath?.length?w.getCursorRectByPath(pos.sectionIndex,pos.parentParaIndex,JSON.stringify(pos.cellPath),pos.charOffset):
     w.getCursorRectInCell(pos.sectionIndex,pos.parentParaIndex,pos.controlIndex,pos.cellIndex,pos.cellParaIndex,pos.charOffset);
 }
 function hide(){handles.forEach(b=>b.style.display='none');menu.style.display='none';}
 function render() {
   frame=0;const ih=input();
   if(!ih||!(navigator.maxTouchPoints>0||matchMedia('(pointer:coarse)').matches)||!studioIsEditing()||ih.isComposing||ih._iosComposing||!ih.hasSelection()||ih.cursor.isInHeaderFooter()||ih.cursor.isInFootnote()||ih.cursor.isInCellSelectionMode()){hide();return;}
   try {
     const s=ih.getSelection();if(!s){hide();return;}
     const points=[point(rectAt(s.start,ih),ih),point(rectAt(s.end,ih),ih)];
     const box=ih.container.getBoundingClientRect();
     points.forEach((p,j)=>{const b=handles[j];if(!p||p.y<box.top||p.y>box.bottom){b.style.display='none';return;}
       b.style.display='block';b.style.left=(Math.min(innerWidth-22,Math.max(22,p.x))-22)+'px';b.style.top=(p.y-3)+'px';
     });
     const p=points[0];if(p&&p.y>=box.top&&p.y<=box.bottom&&!drag){
       menu.style.display='flex';menu.style.left='8px';menu.style.top=Math.max(box.top+4,Math.min(box.bottom-52,p.y-70))+'px';
     }else menu.style.display='none';
   }catch{hide();}
 }
 function refresh(){if(!frame)frame=requestAnimationFrame(render);}
 function hit(x:number,y:number,ih:any) {
   const sc=ih.container.querySelector('#scroll-content') as HTMLElement,zoom=ih.viewportManager.getZoom(),box=sc.getBoundingClientRect();
   const dx=x-box.left,dy=y-box.top,vs=ih.virtualScroll,pi=vs.getPageAtPoint(dx,dy);
   if(pi<0)return null;
   const p=ih.wasm.hitTest(pi,(dx-vs.getPageLeftResolved(pi,sc.clientWidth))/zoom,(dy-vs.getPageOffset(pi))/zoom);
   return p.paragraphIndex>=0xFFFFFF00?null:p;
 }
 function sameContainer(a:any,b:any){return a.sectionIndex===b.sectionIndex&&a.parentParaIndex===b.parentParaIndex&&a.controlIndex===b.controlIndex&&a.cellIndex===b.cellIndex&&JSON.stringify(a.cellPath?.map((p:any)=>[p.controlIndex,p.cellIndex]))===JSON.stringify(b.cellPath?.map((p:any)=>[p.controlIndex,p.cellIndex]));}
 function moveHandle(e:PointerEvent) {
   if(!drag||e.pointerId!==drag.id)return;e.preventDefault();const ih=input();if(!ih||!studioIsEditing())return;
   const box=ih.container.getBoundingClientRect();
   if(e.clientY<box.top+35)ih.container.scrollTop=Math.max(0,ih.container.scrollTop-22);
   else if(e.clientY>box.bottom-35)ih.container.scrollTop+=22;
   const p=hit(e.clientX,Math.max(box.top+2,Math.min(box.bottom-2,e.clientY-12)),ih);
   if(!p||!sameContainer(drag.anchor,p))return;
   ih.cursor.clearSelection();ih.cursor.moveTo(drag.anchor);ih.cursor.setAnchor();ih.cursor.moveToHit(p);ih.active=true;ih.updateCaret(true);refresh();
 }
 handles.forEach((b,j)=>{
   b.addEventListener('pointerdown',e=>{const ih=input(),s=ih?.getSelection();if(!s||!studioIsEditing())return;
     e.preventDefault();e.stopPropagation();drag={id:e.pointerId,anchor:{...(j===0?s.end:s.start)}};b.setPointerCapture(e.pointerId);menu.style.display='none';
   });
   b.addEventListener('pointermove',moveHandle);
   for(const name of ['pointerup','pointercancel','lostpointercapture'])b.addEventListener(name,()=>{drag=null;refresh();});
 });
 function mouse(type:string,x:number,y:number,target:EventTarget|null) {
   (target||document).dispatchEvent(new MouseEvent(type,{bubbles:true,cancelable:true,clientX:x,clientY:y,button:0,buttons:type==='mouseup'?0:1}));
 }
 function cancelPress(){clearTimeout(pressTimer);pressTimer=0;press=null;}
 document.addEventListener('touchstart',e=>{
   const ih=input(),target=e.target as Element;
   if(e.touches.length!==1||!ih||!studioIsEditing()||!target.closest('#scroll-container')||target.closest('#studio-touch-layer')){cancelPress();return;}
   const t=e.touches[0];press={x:t.clientX,y:t.clientY,target,long:false};
   pressTimer=setTimeout(()=>{
     if(!press||!studioIsEditing())return;press.long=true;suppressUntil=Date.now()+900;
     mouse('mousedown',press.x,press.y,press.target);mouse('mouseup',press.x,press.y,document);
     if(!ih.isInPictureObjectSelection()&&!ih.isInTableObjectSelection())mouse('dblclick',press.x,press.y,press.target);
     (window.parent as any).documentStudioHost?.selectionContext?.(ih.isInPictureObjectSelection()?'그림':ih.isInTable()?'표':'글자');
     refresh();
   },450);
 },{passive:true,capture:true});
 document.addEventListener('touchmove',e=>{
   if(!press)return;const t=e.touches[0];
   if(e.touches.length!==1||Math.hypot(t.clientX-press.x,t.clientY-press.y)>12){if(!press.long)cancelPress();}
   if(press?.long)e.preventDefault();
 },{passive:false,capture:true});
 document.addEventListener('touchend',e=>{if(press?.long){e.preventDefault();suppressUntil=Date.now()+700;}cancelPress();refresh();},{passive:false,capture:true});
 document.addEventListener('touchcancel',cancelPress,{passive:true});
 document.addEventListener('mousedown',e=>{if(Date.now()<suppressUntil&&e.isTrusted){e.preventDefault();e.stopImmediatePropagation();}},true);
 document.addEventListener('contextmenu',e=>{if(press?.long){e.preventDefault();e.stopImmediatePropagation();}},true);
 for(const name of ['cursor-rect-updated','cursor-format-changed','document-changed','cell-selection-phase-changed','zoom-changed'])eventBus.on(name,refresh);
 document.addEventListener('scroll',refresh,true);window.addEventListener('resize',refresh);
 return {refresh,hide};
}
