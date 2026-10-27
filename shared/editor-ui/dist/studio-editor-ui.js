/* Shared, offline UI used by the existing HWP and Office engine adapters. */
(function(global){
 'use strict';
 if(global.StudioEditorUI)return;
 const script=document.currentScript?.src||location.href;
 const css=document.createElement('link');css.rel='stylesheet';css.href=new URL('studio-editor-ui.css',script).href;document.head.append(css);
 document.documentElement.dataset.studioUi='true';
 const paths={
 back:'m14 5-7 7 7 7',undo:'M9 7H4v-5 M4 7a9 9 0 1 1 0 10',redo:'M15 7h5v-5 M20 7a9 9 0 1 0 0 10',
 done:'m5 12 4 4L19 6',more:'M5 12h.01M12 12h.01M19 12h.01',close:'m6 6 12 12M6 18 18 6',
 char:'M4 5h16M12 5v15M8 20h8',para:'M4 5h16M4 10h12M4 15h16M4 20h12',
 table:'M3 3h18v18H3zM3 9h18M9 3v18',insert:'M12 5v14M5 12h14',image:'M3 3h18v18H3zM3 17l5-5 4 4 4-6 5 7M7 7h.01',
 search:'M10 3a7 7 0 1 0 0 14 7 7 0 0 0 0-14M15 15l6 6',edit:'m4 16 11-11 4 4L8 20H4z',
 check:'m5 12 4 4L19 6',down:'m6 9 6 6 6-6',font:'M4 19 10 4h4l6 15M7 13h10',zoom:'M10 3a7 7 0 1 0 0 14 7 7 0 0 0 0-14M15 15l6 6M6 10h8M10 6v8'
 };
 function svg(name){const s=document.createElementNS('http://www.w3.org/2000/svg','svg');s.setAttribute('viewBox','0 0 24 24');s.setAttribute('width','22');s.setAttribute('height','22');s.setAttribute('fill','none');s.setAttribute('stroke','currentColor');s.setAttribute('stroke-width','1.8');s.setAttribute('stroke-linecap','round');s.setAttribute('stroke-linejoin','round');s.setAttribute('aria-hidden','true');const p=document.createElementNS(s.namespaceURI,'path');p.setAttribute('d',paths[name]||paths.more);s.append(p);return s;}
 function retain(button,capture){button.addEventListener('pointerdown',function(e){if(e.isPrimary!==false){capture?.();e.preventDefault();}});return button;}
 function iconButton(button,label,icon,action,capture){if(typeof button==='string'){const n=document.createElement('button');n.id=button;button=n;}button.type='button';button.classList.add('studio-ui-icon');button.setAttribute('aria-label',label);button.title=label;button.replaceChildren(svg(icon));if(action)button.onclick=action;retain(button,capture);return button;}
 function dockButton(label,icon,action,capture){const b=document.createElement('button');b.type='button';b.setAttribute('aria-label',label);b.append(svg(icon),document.createTextNode(label));b.onclick=action;return retain(b,capture);}
 function sheet(panel,title,close){panel.classList.add('studio-ui-sheet');panel.setAttribute('role','region');panel.setAttribute('aria-label',title+' 설정');const head=document.createElement('div');head.className='studio-ui-sheet-head';const text=document.createElement('strong');text.textContent=title;head.append(text,iconButton('','설정 닫기','down',close));panel.prepend(head);let start=null;head.onpointerdown=e=>{if(e.target.closest('button'))return;start=e.clientY;head.setPointerCapture(e.pointerId);};head.onpointerup=e=>{if(start!==null&&e.clientY-start>45)close();start=null;};head.onpointercancel=()=>start=null;return head;}
 function popup(trigger,items){let panel;function close(){panel?.remove();panel=null;trigger.setAttribute('aria-expanded','false');document.removeEventListener('pointerdown',outside,true);}
 function outside(e){if(panel&&!panel.contains(e.target)&&!trigger.contains(e.target))close();}
 trigger.setAttribute('aria-haspopup','menu');trigger.setAttribute('aria-expanded','false');
 trigger.onclick=()=>{if(panel){close();return;}panel=document.createElement('div');panel.className='studio-ui-popup';panel.setAttribute('role','menu');for(const item of (typeof items==='function'?items():items)){if(item.hidden)continue;if(item.separator){panel.append(document.createElement('hr'));continue;}let b;if(item.element){b=item.element;b.hidden=false;}else{b=document.createElement('button');b.type='button';b.textContent=item.label;b.disabled=Boolean(item.disabled);b.onclick=()=>{close();item.action?.();};}b.setAttribute('role','menuitem');retain(b);panel.append(b);}document.body.append(panel);trigger.setAttribute('aria-expanded','true');document.addEventListener('pointerdown',outside,true);};
 return {close};}
 function fontPicker(panel,{fonts,current,onPick,onDefault,preview,key='studio-fonts',note='실제 제공되는 글꼴만 표시합니다.'}){
 const wrap=document.createElement('div');wrap.className='studio-ui-fonts';const search=document.createElement('input');search.type='search';search.placeholder='글꼴 검색';search.setAttribute('aria-label','글꼴 검색');search.className='studio-ui-font-search';
 const list=document.createElement('div');list.className='studio-ui-font-list';list.setAttribute('role','listbox');list.setAttribute('aria-label','사용 가능한 글꼴');let recent=[];try{recent=JSON.parse(localStorage.getItem(key)||'[]');if(!Array.isArray(recent))recent=[];}catch{}
 let disposed=false;
 const available=[...new Set(fonts.filter(n=>typeof n==='string'&&n.trim()))];recent=recent.filter(n=>available.includes(n)).slice(0,5);
 const info=document.createElement('div');info.className='studio-ui-font-note';info.textContent=note;wrap.append(search,info,list);panel.append(wrap);let observer;
 function draw(){if(disposed)return;observer?.disconnect();list.replaceChildren();const q=search.value.trim().toLocaleLowerCase();const filtered=available.filter(n=>n.toLocaleLowerCase().includes(q));const ordered=[...recent.filter(n=>filtered.includes(n)),...filtered.filter(n=>!recent.includes(n))];
 if(onDefault&&!q){const reset=document.createElement('button');reset.className='studio-ui-font-option';reset.type='button';reset.textContent='문서 기본 글꼴 복원';retain(reset);reset.onclick=onDefault;list.append(reset);}
 observer=typeof IntersectionObserver!=='undefined'?new IntersectionObserver(entries=>{for(const entry of entries)if(entry.isIntersecting){const b=entry.target;observer.unobserve(b);Promise.resolve(preview?.(b.dataset.family)).then(ok=>{if(ok!==false)b.querySelector('.studio-ui-font-preview').style.fontFamily='"'+b.dataset.family.replaceAll('"','')+'"';}).catch(()=>{});}},{root:panel,rootMargin:'40px'}):null;
 let heading=false;for(const name of ordered){if(!heading||name===filtered.find(n=>!recent.includes(n))){const h=document.createElement('div');h.className='studio-ui-font-heading';h.textContent=recent.includes(name)?'최근 사용':'사용 가능한 글꼴';list.append(h);heading=true;}
 const b=document.createElement('button');b.className='studio-ui-font-option';b.type='button';b.dataset.family=name;b.setAttribute('role','option');b.setAttribute('aria-label',name);b.setAttribute('aria-selected',String(name===current));
 const copy=document.createElement('span');copy.className='studio-ui-font-copy';const label=document.createElement('span');label.textContent=name;const sample=document.createElement('span');sample.className='studio-ui-font-preview';sample.textContent='가나다 Aa 123';copy.append(label,sample);b.append(copy);if(name===current)b.append(svg('check'));retain(b);
 b.onclick=async()=>{if(b.disabled)return;b.disabled=true;try{await onPick(name);recent=[name,...recent.filter(n=>n!==name)].slice(0,5);localStorage.setItem(key,JSON.stringify(recent));current=name;draw();}catch(e){toast(String(e.message||e),true);}finally{b.disabled=false;}};list.append(b);observer?.observe(b);}
 if(!ordered.length){const empty=document.createElement('p');empty.textContent='일치하는 글꼴이 없습니다.';list.append(empty);}
 }search.oninput=draw;draw();return {search,dispose:()=>{disposed=true;observer?.disconnect();}};}
 let toastNode,timer;
 function toast(text,error=false){if(!toastNode){toastNode=document.createElement('div');toastNode.className='studio-ui-toast';toastNode.setAttribute('role','status');toastNode.setAttribute('aria-live','polite');document.body.append(toastNode);}clearTimeout(timer);toastNode.textContent=text;toastNode.dataset.error=String(error);toastNode.hidden=false;if(!error)timer=setTimeout(()=>toastNode.hidden=true,2600);}
 function field(parent,label,value,apply,{type='number',min,max,step='1'}={}){const wrap=document.createElement('label');wrap.textContent=label;const input=document.createElement('input');input.type=type;input.value=value??'';input.setAttribute('aria-label',label);if(min!==undefined)input.min=min;if(max!==undefined)input.max=max;input.step=step;let pending;
 const commit=()=>{clearTimeout(pending);if(!input.checkValidity())return;const v=type==='number'?Number(input.value):input.value;if(type==='number'&&!Number.isFinite(v))return;apply(v);};input.oninput=()=>{clearTimeout(pending);pending=setTimeout(commit,180);};input.onchange=commit;input.onkeydown=e=>{if(e.key==='Enter'){e.preventDefault();commit();}};wrap.append(input);parent.append(wrap);return input;}
 global.StudioEditorUI={svg,retain,iconButton,dockButton,sheet,popup,fontPicker,toast,field};
})(window);
