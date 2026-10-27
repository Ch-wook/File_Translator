// Compact mobile controls. All changes go through the real engine's history and selection scope.
export function createMobileTools({groups,tools,panel,getEditor,isEditable,message,layoutChanged}) {
 const UI=window.StudioEditorUI,sheet=panel.parentElement;
 let picker=null,fontList=false;
 const sheetHead=UI.sheet(sheet,'글자',()=>closePanel());
 const names=['글자','문단','목록','표','그림','삽입','찾기','스타일','쪽','클립보드'];
 let group='글자',expanded=false,target=null,state={},queue=Promise.resolve(),rendering=false,lastContext='body';
 const fields=new Map(),timers=new Map();let searchInfo={count:0,current:0},recent=[];
 try{recent=JSON.parse(localStorage.getItem('studio-colors')||'[]').filter(v=>/^#[0-9a-f]{6}$/i.test(v)).slice(0,8);}catch{}
 const api=()=>getEditor()?.mobile;
 const remember=()=>{if(api())target=api().capture();};
 function execute(action,{capture=false,focus=false}={}) {
   if(capture)remember();
   queue=queue.then(async()=>{
     if(!api())return;
     try{const answer=await action(api());if(answer?.selection)target=answer.selection;if(focus)api().focus();update();}
     catch(e){message(String(e.message||e),true);}
   });return queue;
 }
 function button(label,action,parent=tools,{command,mutates=true}={}) {
   const b=document.createElement('button');b.type='button';b.textContent=label;b.setAttribute('aria-label',label);
   b.dataset.mobileButton='true';b.dataset.mutates=String(mutates);
   if(command)b.dataset.engineCommand=command;
   b.addEventListener('pointerdown',e=>{e.preventDefault();if(!panel.contains(b))remember();});
   b.onclick=()=>{if(!b.disabled)action();};parent.append(b);return b;
 }
 function command(label,id,parent=tools) {return button(label,()=>execute(m=>m.dispatch(id,undefined,target)),parent,{command:id,mutates:!id.startsWith('view:')&&!['edit:copy','edit:select-all'].includes(id)});}
 function line(title){const row=document.createElement('div');row.className='mobile-field-row';if(title){const strong=document.createElement('strong');strong.textContent=title;row.append(strong);}panel.append(row);return row;}
 function number(key,label,value,min,max,step,apply,parent=line()) {
   const wrap=document.createElement('label');wrap.className='mobile-field';wrap.append(document.createTextNode(label));
   const input=document.createElement('input');input.type='number';input.inputMode='decimal';input.min=String(min);input.max=String(max);input.step=String(step);input.value=String(value);input.setAttribute('aria-label',label);input.dataset.mutates='true';
   input.addEventListener('pointerdown',()=>{if(document.activeElement!==input){lastSubmitted=null;remember();}});
   let lastSubmitted=null;
   const commit=()=>{timers.delete(key);const n=Number(input.value);if(lastSubmitted===input.value)return;if(!Number.isFinite(n)||n<min||n>max){input.setAttribute('aria-invalid','true');return;}input.removeAttribute('aria-invalid');lastSubmitted=input.value;execute(m=>apply(m,n));};
   input.oninput=()=>{clearTimeout(timers.get(key)?.timer);timers.set(key,{timer:setTimeout(commit,180),commit});};
   input.onchange=()=>{clearTimeout(timers.get(key)?.timer);commit();};
   input.onkeydown=e=>{if(e.key==='Enter'){e.preventDefault();clearTimeout(timers.get(key)?.timer);commit();api()?.focus();}};
   wrap.append(input);parent.append(wrap);fields.set(key,{input,value});return input;
 }
 function select(key,label,options,apply,parent=line()) {
   const wrap=document.createElement('label');wrap.className='mobile-field';wrap.append(document.createTextNode(label));
   const input=document.createElement('select');input.setAttribute('aria-label',label);input.dataset.mutates='true';
   for(const [value,text] of options){const o=document.createElement('option');o.value=String(value);o.textContent=text;input.append(o);}
   input.addEventListener('pointerdown',remember);input.onchange=()=>execute(m=>apply(m,input.value));wrap.append(input);parent.append(wrap);fields.set(key,{input});return input;
 }
 function color(key,label,prop,parent=line()) {
   const wrap=document.createElement('label');wrap.className='mobile-field';wrap.append(document.createTextNode(label));
   const input=document.createElement('input');input.type='color';input.value=state.char?.[prop]||'#000000';input.setAttribute('aria-label',label);input.dataset.mutates='true';input.onpointerdown=remember;
   input.oninput=()=>{const value=input.value;recent=[value,...recent.filter(v=>v!==value)].slice(0,8);try{localStorage.setItem('studio-colors',JSON.stringify(recent));}catch{}execute(m=>m.char({[prop]:value},target));};
   wrap.append(input);parent.append(wrap);fields.set(key,{input});
   const palette=document.createElement('div');palette.className='color-swatches';parent.append(palette);
   for(const value of [...new Set(['#000000','#e53935','#1e88e5','#43a047','#ffeb3b','#ffffff',...recent])]){
     const b=button(value,()=>execute(m=>m.char({[prop]:value},target)),palette);b.style.background=value;b.style.color=value==='#000000'?'white':'#15283e';b.setAttribute('aria-label',label+' '+value);b.textContent='●';
   }
 }
 function toggleDetails(label='세부 설정') { /* The dock already opens the complete settings sheet. */ }
 function closePanel(){for(const value of timers.values()){clearTimeout(value.timer);value.commit();}timers.clear();expanded=false;fontList=false;render();}
 function choose(name){remember();group=name;expanded=true;fontList=false;render();}
 function textPanel() {
   line(state.scope||'선택한 글자');
   const family=state.char?.fontFamily||state.char?.fontFamilies?.[0]||'문서 기본 글꼴';
   const fontButton=button(family+(api().fonts().includes(family)?'':' · 대체 표시')+' ▾',()=>{fontList=true;render();},line('글꼴'));fontButton.className='font-current';fontButton.setAttribute('aria-label','글꼴 선택 · '+family);
   const sizes=line('글자 크기');number('fontSize','크기(pt)',(state.char?.fontSize||1000)/100,1,4096,.5,(m,v)=>m.char({fontSize:Math.round(v*100)},target),sizes);
   button('글자 크기 −',()=>execute(m=>m.char({fontSize:Math.max(100,(m.state().char.fontSize||1000)-100)},target)),sizes);
   button('글자 크기 ＋',()=>execute(m=>m.char({fontSize:Math.min(409600,(m.state().char.fontSize||1000)+100)},target)),sizes);
   for(const size of [8,9,10,11,12,14,16,18,20,24,28,32])button(String(size),()=>execute(m=>m.char({fontSize:size*100},target)),sizes);
   const style=line('글자 스타일');for(const [label,id] of [['취소선','strikethrough'],['위 첨자','superscript'],['아래 첨자','subscript']])command(label,'format:'+id,style);
   color('textColor','글자색','textColor');color('shadeColor','형광펜·글자 배경색','shadeColor');
   const dims=line('한글 글자 모양');
   number('spacing','자간(%)',state.char?.spacings?.[0]||0,-50,50,1,(m,v)=>m.char({spacings:Array(7).fill(v)},target),dims);
   number('ratio','장평(%)',state.char?.ratios?.[0]||100,50,200,1,(m,v)=>m.char({ratios:Array(7).fill(v)},target),dims);
   number('offset','글자 위치(%)',state.char?.charOffsets?.[0]||0,-100,100,1,(m,v)=>m.char({charOffsets:Array(7).fill(v)},target),dims);
   const format=line('글자 서식');button('글자 서식 복사',()=>execute(m=>m.copyCharFormat()),format,{mutates:false});button('글자 서식 붙이기',()=>execute(m=>m.pasteCharFormat(target)),format);
   button('문서 기본 글꼴',()=>execute(m=>m.defaultFont(target)),format);
   button('선택 글자 서식 초기화',()=>execute(m=>m.resetChar(target)),format);
   const advanced=command('고급 글자 모양','format:char-shape',format);advanced.dataset.requiresSelection='true';
 }
 function paraPanel(){
   line('현재 문단 또는 선택한 문단에 적용');
   const space=line('줄 간격');
   number('lineSpacing','줄 간격(%)',state.para?.lineSpacingType==='Percent'?state.para.lineSpacing:160,5,500,5,(m,v)=>m.para({lineSpacingType:'Percent',lineSpacing:v},target),space);
   for(const v of [100,130,160,180,200])button(v+'%',()=>execute(m=>m.para({lineSpacingType:'Percent',lineSpacing:v},target)),space);
   const indent=line('들여쓰기 · 간격');const p=state.para||{};
   for(const [key,label,factor,min] of [['marginLeft','왼쪽(pt)',200,0],['marginRight','오른쪽(pt)',200,0],['indent','첫 줄·내어쓰기(pt)',200,-100],['spacingBefore','문단 위(pt)',100,0],['spacingAfter','문단 아래(pt)',100,0]])
     number(key,label,Number(((p[key]||0)*.75).toFixed(1)),min,720,1,(m,v)=>m.para({[key]:Math.round(v*factor)},target),indent);
   button('들여쓰기 증가',()=>execute(m=>m.para({marginLeft:Math.round((m.state().para.marginLeft||0)*150)+1000},target)),indent);
   button('들여쓰기 감소',()=>execute(m=>m.para({marginLeft:Math.max(0,Math.round((m.state().para.marginLeft||0)*150)-1000)},target)),indent);
   button('들여쓰기 초기화',()=>execute(m=>m.para({marginLeft:0,marginRight:0,indent:0},target)),indent);
   button('문단 서식 초기화',()=>execute(m=>m.resetPara(target)));
   command('고급 문단 모양','format:para-shape');
 }
 function tablePanel() {
   if(!state.cell&&!state.tableObject) {
     line('본문 커서 위치에 표 삽입');
     const row=number('rows','행 수',3,1,50,1,()=>null),col=number('cols','열 수',3,1,30,1,()=>null);
     button('지정한 표 삽입',()=>execute(m=>m.createTable(Number(row.value),Number(col.value))));return;
   }
   const cell=state.cell;line(cell?'표 안 글자에도 글자·문단 도구를 사용할 수 있습니다.':'표 전체 선택');
   const choose=line('셀 이동·선택');button('이전 셀',()=>execute(m=>m.moveCell(false)),choose);button('다음 셀',()=>execute(m=>m.moveCell(true)),choose);button('현재 셀 선택',()=>execute(m=>m.selectCells()),choose);button('모든 셀 선택',()=>execute(m=>m.selectCells(undefined,undefined,true)),choose);
   if(cell){const r=line('범위 끝');const row=number('endRow','끝 행',cell.info.row+1,1,cell.dims.rowCount,1,()=>null,r),col=number('endCol','끝 열',cell.info.col+1,1,cell.dims.colCount,1,()=>null,r);
     button('범위 선택',()=>execute(m=>m.selectCells(Number(row.value)-1,Number(col.value)-1)),r);}
   const layout=line('셀·표 서식');command('행 높이 같게','table:cell-height-equal',layout);command('열 너비 같게','table:cell-width-equal',layout);
   command('셀·표 모양','table:cell-props',layout);command('테두리·배경','table:border-each',layout);command('표 삭제','table:delete',layout);
   if(cell?.props){const p=cell.props,dimensions=line('현재 선택 셀');
     number('cellWidth','셀 너비(mm)',Number((p.width*25.4/7200).toFixed(1)),1,508,1,(m,v)=>m.cell({width:Math.round(v*7200/25.4)},target),dimensions);
     number('cellHeight','셀 높이(mm)',Number((p.height*25.4/7200).toFixed(1)),1,508,1,(m,v)=>m.cell({height:Math.round(v*7200/25.4)},target),dimensions);
     select('vertical','세로 정렬',[[0,'위'],[1,'가운데'],[2,'아래']],(m,v)=>m.cell({verticalAlign:Number(v)},target),dimensions);
   }
 }
 function searchPanel(){
   line('내용을 바꾸지 않고 검색합니다. 바꾸기는 편집 모드에서만 가능합니다.');
   const row=line(),q=document.createElement('input');q.type='search';q.placeholder='찾을 내용';q.setAttribute('aria-label','찾을 내용');row.append(q);
   const repl=document.createElement('input');repl.placeholder='바꿀 내용';repl.setAttribute('aria-label','바꿀 내용');row.append(repl);
   const advanced=line();command('고급 찾기·바꾸기','edit:find-replace',advanced);
   const count=document.createElement('output');count.setAttribute('aria-live','polite');count.id='mobile-match-count';panel.append(count);
   const updateCount=()=>{searchInfo={count:api().count(q.value),current:0};count.textContent='검색 결과 '+searchInfo.count+'개';};q.oninput=updateCount;
   const find=dir=>execute(m=>{searchInfo=m.find(q.value,dir);count.textContent=searchInfo.current+' / '+searchInfo.count;return null;});
   const nav=line();button('이전 결과',()=>find(-1),nav,{mutates:false});button('다음 결과',()=>find(1),nav,{mutates:false});
   button('바꾸기',()=>execute(m=>{const r=m.replace(q.value,repl.value);updateCount();return r;}),nav);
   button('모두 바꾸기',()=>execute(m=>{const r=m.replace(q.value,repl.value,true);updateCount();return r;}),nav);
 }
 function render() {
   if(rendering||!api())return;const view=getEditor().captureView();rendering=true;
   for(const value of timers.values()){clearTimeout(value.timer);value.commit();}timers.clear();
   state=api().state();picker?.dispose();picker=null;groups.replaceChildren();tools.replaceChildren();panel.replaceChildren();fields.clear();
   sheetHead.querySelector('strong').textContent=fontList?'글꼴':group;
   const contextName=state.object?'그림':state.cell||state.tableObject?'표':'표';
   for(const [n,icon] of [['글자','char'],['문단','para'],[contextName,contextName==='그림'?'image':'table'],['삽입','insert'],['더보기','more']]){const b=UI.dockButton(n,icon,()=>{if(expanded&&group===n){closePanel();return;}choose(n);},remember);b.setAttribute('aria-selected',String(expanded&&group===n));groups.append(b);}
   if(!expanded){rendering=false;update();layoutChanged?.(view);return;}
   if(fontList){picker=UI.fontPicker(panel,{fonts:api().fonts(),current:state.char?.fontFamily,onPick:async name=>{await execute(m=>m.font(name,target));fontList=false;render();},onDefault:async()=>{await execute(m=>m.defaultFont(target));fontList=false;render();},preview:async name=>{await api().previewFont(name);for(const face of document.getElementById('engine').contentDocument.fonts)if(face.family.replaceAll('"','')===name)document.fonts.add(face);return true;},key:'studio-hangul-fonts',note:'번들 글꼴 · 문서에 없는 글꼴은 엔진 대체 글꼴로 표시됩니다.'});rendering=false;update();layoutChanged?.(view);return;}

   const basic=()=>{command('실행 취소','edit:undo');command('다시 실행','edit:redo');};
   if(group==='더보기'){
     for(const n of names.filter(n=>!['글자','문단','표','삽입'].includes(n)))button(n,()=>choose(n),tools,{mutates:false});
     line('형식에 맞는 도구를 선택하세요. 문서 본문을 누르면 선택 대상을 바꿀 수 있습니다.');
   }else if(group==='글자'){
     command('굵게','format:bold');command('기울임','format:italic');command('밑줄','format:underline');

     toggleDetails('글자 모양');if(expanded)textPanel();
   }else if(group==='문단'){
     for(const [label,id] of [['왼쪽','left'],['가운데','center'],['오른쪽','right'],['양쪽','justify'],['배분','distribute']])command(label,'format:align-'+id);
     command('줄 간격 −','format:line-spacing-decrease');command('줄 간격 ＋','format:line-spacing-increase');
     toggleDetails('문단 모양');if(expanded)paraPanel();
   }else if(group==='목록'){
     for(const [label,kind] of [['●','disc'],['○','circle'],['■','square'],['1.','number'],['가.','korean'],['A.','english'],['Ⅰ.','roman'],['□','check']])button(label,()=>execute(m=>m.list(kind)));
     button('하위 목록',()=>execute(m=>m.list('deeper')));button('상위 목록',()=>execute(m=>m.list('shallower')));button('목록 해제',()=>execute(m=>m.list('none')));
     toggleDetails('시작 번호');if(expanded){const n=number('startNumber','시작 번호',1,1,999,1,()=>null);button('이 번호로 새로 시작',()=>execute(m=>m.list('number',Number(n.value))),panel);}
   }else if(group==='표'){
     for(const [label,id] of [['표 만들기','create'],['위에 행','insert-row-above'],['아래에 행','insert-row-below'],['왼쪽 열','insert-col-left'],['오른쪽 열','insert-col-right'],['행 삭제','delete-row'],['열 삭제','delete-col'],['셀 합치기','cell-merge'],['셀 나누기','cell-split']])command(label,'table:'+id);
     toggleDetails('표 세부 도구');if(expanded)tablePanel();
   }else if(group==='그림'){
     for(const [label,id] of [['그림 삽입','image'],['그림 속성','picture-props'],['시계 방향 회전','rotate-cw'],['반시계 회전','rotate-ccw'],['맨 앞으로','arrange-front'],['맨 뒤로','arrange-back'],['그림 삭제','picture-delete']])command(label,'insert:'+id);
   }else if(group==='삽입'){
     for(const [label,id] of [['특수문자','insert:symbols'],['하이퍼링크','insert:hyperlink'],['링크 수정','hyperlink:edit'],['링크 제거','hyperlink:remove'],['글상자','insert:textbox']])command(label,id);
     button('날짜',()=>execute(m=>m.plainText(new Intl.DateTimeFormat('ko-KR',{dateStyle:'medium'}).format(new Date()))));
     button('시간',()=>execute(m=>m.plainText(new Intl.DateTimeFormat('ko-KR',{timeStyle:'short'}).format(new Date()))));
     toggleDetails('자주 쓰는 기호');if(expanded)for(const v of ['①','②','③','✓','□','☑','→','←','↑','↓','±','×','÷','≤','≥','∞'])button(v,()=>execute(m=>m.plainText(v)),panel);
   }else if(group==='찾기'){button('찾기·바꾸기',()=>{remember();expanded=!expanded;render();},tools,{mutates:false});if(expanded)searchPanel();}
   else if(group==='스타일'){
     command('스타일 관리','format:style-dialog');toggleDetails('문서 스타일');
     if(expanded){line('문서에 정의된 실제 스타일을 현재/선택 문단에 적용합니다.');select('style','문서 스타일',api().styles().map(s=>[s.id,s.name]),(m,v)=>m.dispatch('format:apply-style',{styleId:Number(v)},target));}
   }else if(group==='쪽'){
     for(const [label,id] of [['편집 용지','page:setup'],['쪽 나누기','page:break'],['다단 설정','page:col-settings'],['너비 맞춤','view:zoom-fit-width']])command(label,id);
     button('문서 맨 위',()=>execute(m=>m.navigate(false)),tools,{mutates:false});button('문서 맨 아래',()=>execute(m=>m.navigate(true)),tools,{mutates:false});
   }else if(group==='클립보드'){
     button('복사',()=>execute(m=>m.copy()),tools,{mutates:false});button('잘라내기',()=>execute(m=>m.copy(true)));
     button('서식 유지 붙여넣기',()=>execute(m=>m.paste(false)));button('서식 없이 붙여넣기',()=>execute(m=>m.paste(true)));
     command('전체 선택','edit:select-all');command('선택 삭제','edit:delete');command('문단·글자 서식 복사','edit:format-copy');command('문단·글자 서식 붙이기','edit:format-paste');
   }
   if(expanded)button('설정 완료',()=>{closePanel();api()?.focus();},panel,{mutates:false});
   panel.hidden=!expanded;rendering=false;update();layoutChanged?.(view);
 }
 function update() {
   if(!api())return;state=api().state();sheet.hidden=!expanded||!state.editing;panel.hidden=!expanded;tools.hidden=!expanded||fontList;const available=isEditable()&&!state.composing;
   for(const root of [groups,tools,panel])for(const b of root.querySelectorAll('[data-mobile-button]')){
     const cmd=b.dataset.engineCommand;b.disabled=(b.dataset.mutates==='true'&&!available)||Boolean(cmd&&!getEditor().enabled(cmd))||(b.dataset.requiresSelection==='true'&&!state.selection);
     if(cmd?.startsWith('format:')&&['bold','italic','underline','strikethrough','superscript','subscript'].includes(cmd.slice(7)))b.setAttribute('aria-pressed',String(Boolean(state.char?.[cmd.slice(7)])));
   }
   for(const [key,{input}] of fields){
     input.disabled=!available;
     if(document.activeElement===input||timers.has(key))continue;
     let value;
     if(key==='font')value=state.char?.fontFamily;
     else if(key==='fontSize')value=(state.char?.fontSize||1000)/100;
     else if(key==='spacing')value=state.char?.spacings?.[0]||0;
     else if(key==='ratio')value=state.char?.ratios?.[0]||100;
     else if(key==='offset')value=state.char?.charOffsets?.[0]||0;
     else if(['textColor','shadeColor'].includes(key))value=state.char?.[key];
     else if(key==='lineSpacing'&&state.para?.lineSpacingType==='Percent')value=state.para.lineSpacing;
     else if(['marginLeft','marginRight','indent','spacingBefore','spacingAfter'].includes(key))value=Number(((state.para?.[key]||0)*.75).toFixed(1));
     else if(key==='style')value=state.style;
     else if(key==='vertical')value=state.cell?.props?.verticalAlign;
     if(value!==undefined&&String(value)!==input.value)input.value=String(value);
   }
   const context=state.object?'image':state.cell||state.tableObject?'table':'body';
   if(context!==lastContext){lastContext=context;if(context==='image')group='그림';else if(context==='table')group='표';else if(['그림','표'].includes(group))group='글자';expanded=false;render();}
 }
 function action(name){
   if(name==='copy')return execute(m=>m.copy());
   if(name==='cut')return execute(m=>m.copy(true));
   if(name==='paste')return execute(m=>m.paste(false));
   if(['bold','underline'].includes(name)){remember();return execute(m=>m.dispatch('format:'+name,undefined,target));}
   if(name==='colors'||name==='character'){remember();group='글자';expanded=true;render();}
 }
 async function flush(){for(const value of timers.values()){clearTimeout(value.timer);value.commit();}timers.clear();await queue;if(panel.querySelector('[aria-invalid="true"]'))throw new Error('입력한 서식 값의 범위를 확인하세요.');}
 return {render,update,action,flush,open:choose,context(name){if(state.object||name==='그림'){group='그림';expanded=false;render();}else update();},
   showSearch(){choose('찾기');},closePanel};
}
