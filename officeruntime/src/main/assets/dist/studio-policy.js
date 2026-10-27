/* Document Studio: offline native Office workspace, pinned to the bundled engine. */
(() => {
  if (window.studioOffice) return;
  const policy = window.studioOffice = { ready: false, authorized: false, wasAuthorized: false, modeSupported: false, changed: false, initialIntentHandled: false };
  const bridge = () => window.COOLMessageHandler;
  window.enableMacrosExecution = false;
  const style = document.createElement('style');
  style.textContent = `
    #AIAssistant-tab-label,#AIAssistant-tab,[id*="aiassistant"],[id*="ai-assistant"]{display:none!important}
    html[data-studio-office-host] #toolbar-wrapper{display:flex!important;flex-direction:column;position:relative;z-index:30!important;flex:none;min-height:48px}
    html[data-studio-office-host] #toolbar-row,html[data-studio-office-host] #document-titlebar,html[data-studio-office-host] #main-menu,html[data-studio-office-host] #toolbar-down,html[data-studio-office-host] #mobile-edit-button{display:none!important}
    html[data-studio-office-host] #document-container.readonly.mobile{position:relative!important;top:auto!important;min-height:0}
    #studio-office-tools{display:contents;--studio-accent:#2858a5;--studio-line:#dfe5ed;--studio-ink:#26364a;--studio-paper:#fff}
    #studio-office-header{width:100%;color:#26364a;background:#fff}
    #studio-office-bottom{display:flex;flex-direction:column;flex:none;z-index:30;min-height:0;background:#fff;padding-bottom:env(safe-area-inset-bottom)}
    #studio-office-actions .studio-ui-fields{padding:4px 8px}
    #studio-office-actions .studio-office-action{min-height:44px;min-width:44px;padding:5px 9px;margin:2px;border:0;border-radius:7px;background:#edf2f9;color:#26364a;font:12px system-ui}
    #studio-office-actions .studio-office-action:disabled{opacity:.35}
    #studio-office-actions .studio-office-action[aria-pressed=true]{background:#dbe7fc;color:#2858a5}
    #studio-office-mode-label,#studio-office-hint{display:none!important}
    #studio-office-sheet{max-height:min(38dvh,300px)}
    html[data-studio-office-host] #toolbar-search{z-index:40}
    @media(max-height:520px){#studio-office-sheet{max-height:min(32dvh,180px)}}
  `;
  document.head.appendChild(style);
  let attempts = 0;
  const UI=window.StudioEditorUI;
  let bar,actions,hint,tabs,modeButton,modeLabel,saveButton,header,bottom,sheet,undoButton,redoButton,picker,groupOpen=false,fontList=false,contextName='',previousContext='';
  let transitionViewport, persistTimer, lastViewport='';
  let activeTab = '글자';
  let modeTransition=false, transitionGeneration=0, ownedNativeRestore=null;
  const restoreTrace=[];
  let heldViewport=null, initialViewportRestored=false;
  const pinnedLayouts=new WeakSet();
  const controls = [];
  const map = () => window.app && app.map;
  const objectSelected = () => typeof GraphicSelection !== 'undefined' && GraphicSelection.hasActiveSelection();
  const editable = () => Boolean(policy.authorized && map() && map().isEditMode());
  const searchService=()=>typeof window.app?.searchService?.search==='function'?app.searchService:typeof map()?.search==='function'?map():null;
  function refreshLayout() {
    // The pinned Writer/Impress map no longer exposes Leaflet invalidateSize.
    const layout=window.app?.activeDocument?.activeLayout;
    if(typeof layout?.onResize==='function') layout.onResize();
    else window.dispatchEvent(new Event('resize'));
  }
  function snapshot() {
    const layout = window.app?.activeDocument?.activeLayout;
    const rect = layout?.viewedRectangle;
    if (!rect || !map()?.getZoom) return null;
    return { x:Number(rect.x1), y:Number(rect.y1), zoom:Number(map().getZoom()), part:map().getDocType()==='text' ? 0 : Number(map().getCurrentPartNumber?.() || 0) };
  }
  function traceRestore(stage, desired, error) {
    try {
      const layout=window.app?.activeDocument?.activeLayout, layer=map()?._docLayer;
      const actual=snapshot();
      restoreTrace.push({
        stage,generation:transitionGeneration,targetY:desired?.y,y:actual?.y,zoom:actual?.zoom,
        layout:layout?.type,scrollY:layout?.scrollProperties?.viewY,
        viewH:layout?.viewSize?.pY,frameH:layout?.frameSize?.pY,
        scale:Number(window.app?.twipsToPixels),scaleType:typeof window.app?.twipsToPixels,
        pending:Boolean(layer?._restoringViewScroll),
        nativeHelper:typeof layer?._viewState?.onViewPositionMsg==='function',
        error:error ? String(error.message || error).slice(0,100) : undefined
      });
      if(restoreTrace.length>14) restoreTrace.shift();
    } catch(ignore) { }
  }
  function validViewport(value) {
    return value && ['x','y','zoom','part'].every(key => Number.isFinite(value[key]))
      && Math.abs(value.x)<=1e12 && Math.abs(value.y)<=1e12 && value.zoom>=0 && value.zoom<=30
      && Number.isInteger(value.part) && value.part>=0 && value.part<=1000000;
  }
  function restore(value, done, generation) {
    const current=()=>generation===undefined || generation===transitionGeneration;
    if(!current()) return;
    if (!validViewport(value)) {done?.();return;}
    traceRestore('restore-request',value);
    const drain = task => window.app?.layoutingService?.onDrain ? app.layoutingService.onDrain(task) : setTimeout(task,0);
    drain(() => {
      if (!current() || !map()) return;
      const layout = window.app?.activeDocument?.activeLayout;
      if (!layout) {done?.();return;}
      installViewportPin(layout);
      if (map().getDocType()!=='text' && map().getCurrentPartNumber?.() !== value.part) map().setPart?.(value.part);
      traceRestore('before-zoom',value);
      try {layout.applyZoom?.(value.zoom);}
      catch(error) {traceRestore('zoom-error',value,error);persistViewport();throw error;}
      traceRestore('after-zoom',value);
      drain(() => {
        if(!current()) return;
        const active=window.app?.activeDocument?.activeLayout, ratio=window.app.twipsToPixels;
        traceRestore('before-scroll',value);
        try {
        if (Number.isFinite(ratio)) {
          const layer=map()?._docLayer, nativeViewState=layer?._viewState;
          if(map().getDocType()==='text' && active?.type!=='ViewLayoutMultiPage' && typeof nativeViewState?.onViewPositionMsg==='function') {
            // The pinned native ViewState also protects the restored offset from
            // the next asynchronous cursor update; a direct layout scroll does not.
            nativeViewState.onViewPositionMsg('viewposition: x='+Math.round(value.x)+' y='+Math.round(value.y));
            ownedNativeRestore=layer._savedScrollPos || null;
          } else active?.scrollTo?.(value.x*ratio,value.y*ratio);
        }
        } catch(error) {traceRestore('scroll-error',value,error);persistViewport();throw error;}
        traceRestore('after-scroll',value);
        drain(()=>{if(current()) done?.();});
      });
    });
  }
  function installViewportPin(layout) {
    if(!layout || layout.type!=='ViewLayoutWriter' || pinnedLayouts.has(layout)) return;
    const refresh=layout.refreshVisibleAreaRectangle;
    if(typeof refresh!=='function') return;
    layout.refreshVisibleAreaRectangle=function(...args) {
      const pin=heldViewport, scale=window.app?.twipsToPixels, properties=this.scrollProperties;
      if(pin?.layoutType===this.type && properties && Number.isFinite(scale)
          && Number.isFinite(pin.offsetX) && Number.isFinite(pin.offsetY)) {
        const x=pin.offsetX*scale, y=pin.offsetY*scale;
        if(Math.abs(properties.viewX-x)>0.01 || Math.abs(properties.viewY-y)>0.01)
          traceRestore('pin-refresh',pin);
        properties.viewX=x;properties.viewY=y;
      }
      return refresh.apply(this,args);
    };
    pinnedLayouts.add(layout);
  }
  function holdViewport(value) {
    const layout=window.app?.activeDocument?.activeLayout, scale=window.app?.twipsToPixels;
    const properties=layout?.scrollProperties, rect=layout?.viewedRectangle;
    if(!validViewport(value) || !properties || !rect || !Number.isFinite(scale) || scale<=0) {
      heldViewport=null;return;
    }
    heldViewport={...value,layoutType:layout.type,
      offsetX:properties.viewX/scale+value.x-rect.x1,
      offsetY:properties.viewY/scale+value.y-rect.y1};
    installViewportPin(layout);
  }
  function beginModeTransition(value) {
    if(!modeTransition || !heldViewport) holdViewport(value);
    transitionViewport=value;modeTransition=true;
    return ++transitionGeneration;
  }
  function settleModeTransition(value, generation) {
    // Native cursor activation can arrive after the synchronous permission event.
    // Keep tools gated through the layout drain and that short Android UI transition.
    restore(value,()=>setTimeout(()=>restore(value,()=>{
      if(generation!==transitionGeneration) return;
      modeTransition=false;update();traceRestore('settled',value);persistViewport();
    },generation),500),generation);
  }
  function releaseViewportPin() {
    heldViewport=null;
    const layer=map()?._docLayer;
    if(ownedNativeRestore && layer?._savedScrollPos===ownedNativeRestore) {
      layer._savedScrollPos=null;layer._restoringViewScroll=false;ownedNativeRestore=null;
    }
  }
  function cancelModeRestore(event) {
    if(!event.isTrusted || bar?.contains(event.target) || header?.contains(event.target) || bottom?.contains(event.target)) return;
    releaseViewportPin();
    if(modeTransition) {
      ++transitionGeneration;modeTransition=false;update();
    }
  }
  for(const name of ['pointerdown','touchstart','wheel','keydown','beforeinput','compositionstart'])
    document.addEventListener(name,cancelModeRestore,true);
  function persistViewport() {
    if(!initialViewportRestored) return;
    let value;
    try { value=(modeTransition && validViewport(transitionViewport) ? transitionViewport : snapshot()) || {error:'layout-unavailable',family:map()?.getDocType?.()}; }
    catch(error) { value={error:String(error.message || error),family:map()?.getDocType?.()}; }
    // The native validator stores only valid coordinates. Invalid values are
    // retained separately as bounded view diagnostics, never as document data.
    if(validViewport(value) && restoreTrace.length) {
      value.restoreTrace=restoreTrace.slice(-14);
      while(JSON.stringify(value).length>3500 && value.restoreTrace.length) value.restoreTrace.shift();
    }
    const text=JSON.stringify(value);
    if (text!==lastViewport) {bridge()?.saveStudioViewState?.(text);lastViewport=text;}
  }
  function rememberViewport() {
    clearTimeout(persistTimer); persistTimer=setTimeout(persistViewport,250);
  }
  function installModeGate() {
    const m=map(), enter=m._switchToEditMode;
    if (typeof enter!=='function' || typeof m.setPermission!=='function') return false;
    m._switchToEditMode=function(...args) {
      if(modeTransition || !initialViewportRestored) return false;
      const value=snapshot();
      if (new URLSearchParams(location.search).get('permission')!=='edit' || !bridge()?.requestStudioEdit?.()) {
        hint.textContent='이 문서는 편집할 수 없습니다.';return false;
      }
      const generation=beginModeTransition(value);traceRestore('begin-edit',value);
      policy.authorized=true;policy.wasAuthorized=true;
      const result=enter.apply(this,args);traceRestore('edit-returned',value);
      update();refreshLayout();settleModeTransition(value,generation);return result;
    };
    const permission=m.setPermission;
    m.setPermission=function(value,...args) {
      if((value==='readonly'||value==='view')&&actions){try{flushSettings();}catch(error){UI.toast(error.message,true);return false;}}
      const viewport=modeTransition && validViewport(transitionViewport) ? transitionViewport : snapshot();
      const generation=beginModeTransition(viewport);traceRestore('begin-'+String(value).slice(0,20),viewport);
      const result=permission.call(this,value,...args);
      // Upstream commits a pending Calc cell before entering read-only mode.
      // Do not revoke the gate before that commit has reached the native core.
      if (value==='readonly' || value==='view') policy.authorized=false;
      update();refreshLayout();settleModeTransition(viewport,generation);return result;
    };
    return true;
  }
  function uno(name, args) {
    if (!editable()) return;
    if(!['Copy','SelectAll'].includes(name))policy.lastEditCommand=Date.now();
    // Android's clipboard helper consumes EVERY command and drops its args.
    // It is only valid for argument-free clipboard operations.
    const clipboard=['Copy','Cut','Paste','PasteSpecial','PasteUnformatted'].includes(name);
    if(clipboard&&!args&&map()._clip?.filterExecCopyPaste('.uno:'+name)) {map().focus(false);return;}
    if(name==='FontHeight'&&args?.['FontHeight.Height']&&typeof map().applyFontSize==='function')
      map().applyFontSize(String(args['FontHeight.Height'].value));
    else if(!args&&window.app?.socket?.sendMessage) app.socket.sendMessage('uno .uno:'+name);
    else map().sendUnoCommand('.uno:'+name,args);
    map().focus(false);
  }
  function command(label, name, needsObject = false) {
    return { label, name, needsObject, run: () => uno(name) };
  }
  const bundledFonts={
    'Noto Sans KR':'NotoSansKR.ttf','Noto Sans KR Thin':'NotoSansKR.ttf','Caladea':'Caladea-Regular.ttf','Carlito':'Carlito-Regular.ttf',
    'Gentium Basic':'GenBasR.ttf','Gentium Book Basic':'GenBkBasR.ttf',
    'Liberation Mono':'LiberationMono-Regular.ttf','Liberation Sans':'LiberationSans-Regular.ttf',
    'Liberation Sans Narrow':'LiberationSansNarrow-Regular.ttf','Liberation Serif':'LiberationSerif-Regular.ttf'
  };
  const fontFaces=new Map();
  const state=name=>map()?.stateChangeHandler?.getItemValue('.uno:'+name);
  const fonts=()=>Object.keys(map()?.getToolbarCommandValues?.('.uno:CharFontName')||{}).filter(name=>name.trim()).sort();
  function button(label,run,parent=actions){
    const b=document.createElement('button');b.type='button';b.className='studio-office-action';b.textContent=label;b.setAttribute('aria-label',label);UI.retain(b);b.onclick=()=>{if(!b.disabled){releaseViewportPin();run();update();}};parent.append(b);return b;
  }
  function add(item,parent=actions){const b=button(item.label,item.run,parent);if(item.name)b.dataset.command=item.name;controls.push({b,item});return b;}
  function flushSettings(){return UI.flushFields(actions);}
  function closeSheet(){try{flushSettings();}catch(error){UI.toast(error.message,true);return;}picker?.dispose();picker=null;groupOpen=false;fontList=false;sheet.hidden=true;renderDock();refreshLayout();}
  function openSheet(name){try{flushSettings();}catch(error){UI.toast(error.message,true);return;}activeTab=name;groupOpen=true;fontList=false;render();}
  function currentContext(){if(objectSelected())return '그림';if(map()?.getDocType()==='spreadsheet')return '셀';if(/table|cell/i.test(contextName))return '표';if(map()?.getDocType()==='presentation')return '슬라이드';return '표';}
  function renderDock(){
    tabs.replaceChildren();const context=currentContext();
    for(const [label,icon] of [['글자','char'],['문단','para'],[context,context==='그림'?'image':'table'],['삽입','insert'],['더보기','more']]){
      const b=UI.dockButton(label,icon,()=>{if(groupOpen&&activeTab===label)closeSheet();else openSheet(label);});b.setAttribute('aria-selected',String(groupOpen&&activeTab===label));tabs.append(b);
    }
  }
  function update(){
    if(!bar)return;const editing=editable(),object=objectSelected();
    bar.dataset.editing=String(editing);const searching=groupOpen&&activeTab==='찾기';bottom.hidden=!editing&&!searching;sheet.hidden=!groupOpen||(!editing&&!searching);tabs.hidden=!editing;
    modeLabel.textContent=editing?'편집 중':'원본 보기';
    modeButton.textContent='편집하기';modeButton.setAttribute('aria-label','편집하기');
    modeButton.hidden=editing||!policy.modeSupported||new URLSearchParams(location.search).get('permission')!=='edit';
    modeButton.disabled=modeTransition||!initialViewportRestored;
    saveButton.hidden=!editing&&!policy.changed;saveButton.disabled=modeTransition||!policy.changed;
    undoButton.hidden=redoButton.hidden=!editing;
    for(const {b,item}of controls){const value=item.name&&state(item.name);b.disabled=!editing||modeTransition||(item.needsObject&&!object)||value==='disabled';if(value==='true'||value==='false')b.setAttribute('aria-pressed',value);else b.removeAttribute('aria-pressed');}
    undoButton.disabled=!editing||modeTransition||state('Undo')==='disabled';redoButton.disabled=!editing||modeTransition||state('Redo')==='disabled';
    const back=header.querySelector('#studio-office-back');back.setAttribute('aria-label',editing?'보기로 전환':'돌아가기');back.disabled=modeTransition||!initialViewportRestored;
    const input=actions.querySelector('[aria-label="크기(pt)"]');if(input&&document.activeElement!==input){const value=state('FontHeight');if(value&&Number.isFinite(Number(value)))input.value=value;}
    const font=actions.querySelector('[data-current-font]');if(font){const name=state('CharFontName')||'문서 글꼴';font.textContent=name+(fonts().includes(name)?'':' · 대체 표시')+' ▾';font.setAttribute('aria-label','글꼴 선택 · '+name);}
    hint.textContent=editing?'편집 중':'원본 보기';
    const context=currentContext();if(context!==previousContext){previousContext=context;if(groupOpen&&['그림','표','셀','슬라이드'].includes(activeTab))closeSheet();renderDock();}
  }
  function row(){const r=document.createElement('div');r.className='studio-ui-fields';actions.append(r);return r;}
  function numeric(label,value,apply,options={}){const input=UI.field(row(),label,value,v=>{if(editable()&&!modeTransition){releaseViewportPin();apply(v);}},{min:1,max:4096,...options});input.disabled=!editable();return input;}
  function palette(label,name,property=name){
    const r=row(),text=document.createElement('span');text.textContent=label;r.append(text);
    const apply=value=>uno(name,{[property]:{type:'long',value:parseInt(value.slice(1),16)}});
    UI.field(r,label,'#000000',apply,{type:'color'});
    for(const value of ['#000000','#e53935','#1e88e5','#43a047','#ffeb3b','#ffffff']){const b=button(label+' '+value,()=>apply(value),r);b.textContent='●';b.style.background=value;b.style.border='1px solid #ccd6e2';b.style.color=value==='#000000'?'white':'#26364a';}
  }
  async function previewFont(name){
    if(!bundledFonts[name]||typeof FontFace==='undefined')return false;
    if(!fontFaces.has(name)){const face=new FontFace(name,'url("'+new URL('../unpack/user/fonts/'+bundledFonts[name],location.href).href+'")');fontFaces.set(name,face.load().then(loaded=>{document.fonts.add(loaded);return true;}));}
    return fontFaces.get(name);
  }
  function render(){
    if(!actions)return;const view=snapshot();picker?.dispose();picker=null;actions.replaceChildren();controls.length=0;renderDock();
    sheet.querySelector('.studio-ui-sheet-head strong').textContent=fontList?'글꼴':activeTab;sheet.hidden=!groupOpen;
    const family=map().getDocType(),items=[];
    if(!groupOpen){update();refreshLayout();return;}
    if(fontList){
      const available=fonts();
      picker=UI.fontPicker(actions,{fonts:available,current:state('CharFontName'),onPick:name=>{if(!editable()||modeTransition)return;uno('CharFontName',{'CharFontName.FamilyName':{type:'string',value:name}});fontList=false;render();},preview:previewFont,key:'studio-office-fonts',note:'엔진이 실제 제공하는 글꼴 · 번들 파일이 없는 글꼴은 미리보기 제한을 표시합니다. 선택 영역 또는 이후 입력에 적용됩니다.'});
      update();refreshLayout();return;
    }
    if(activeTab==='찾기'){
      const r=row(),q=document.createElement('input');q.type='search';q.setAttribute('aria-label','찾을 내용');q.placeholder='찾을 내용';r.append(q);
      const find=backwards=>{if(q.value&&searchService()){releaseViewportPin();searchService().search(q.value,backwards);}};
      button('이전 결과',()=>find(true));button('다음 결과',()=>find(false));
      q.onkeydown=e=>{if(e.key==='Enter'){e.preventDefault();find(false);}};
      update();refreshLayout();return;
    }else if(activeTab==='글자'){
      const font=button((state('CharFontName')||'문서 글꼴')+' ▾',()=>{fontList=true;render();});font.dataset.currentFont='true';font.setAttribute('aria-label','글꼴 선택 · '+(state('CharFontName')||'문서 글꼴'));
      numeric('크기(pt)',Number(state('FontHeight'))||12,v=>uno('FontHeight',{'FontHeight.Height':{type:'float',value:v}}),{step:.5});
      const sizes=row();for(const size of [8,9,10,11,12,14,16,18,20,24,28,32])button(String(size),()=>uno('FontHeight',{'FontHeight.Height':{type:'float',value:size}}),sizes);
      for(const [label,name]of [['굵게','Bold'],['기울임','Italic'],['밑줄','Underline'],['취소선','Strikeout'],['위 첨자','SuperScript'],['아래 첨자','SubScript']])items.push(command(label,name));
      items.forEach(item=>add(item));palette('글자색',family==='text'?'Color':'FontColor');
      if(family==='text')palette('형광펜·글자 배경색','CharBackColor');
      add(command('자간·장평·글자 위치','FontDialog'));add(command('선택 글자 서식 초기화','ResetAttributes'));
    }else if(activeTab==='문단'){
      if(family==='spreadsheet'){add(command('셀 정렬·줄바꿈','FormatCellDialog'));update();refreshLayout();return;}
      for(const [label,name]of [['왼쪽 정렬','LeftPara'],['가운데 정렬','CenterPara'],['오른쪽 정렬','RightPara'],['양쪽 정렬','JustifyPara'],['글머리표','DefaultBullet'],['번호 매기기','DefaultNumbering'],['들여쓰기 증가','IncrementIndent'],['들여쓰기 감소','DecrementIndent'],['줄 간격·문단 간격·테두리','ParagraphDialog']])add(command(label,name));
      if(family==='text'){add(command('쪽 설정','PageDialog'));add(command('쪽 나누기','InsertPagebreak'));}
    }else if(activeTab==='표'){
      for(const [label,name]of [['표 삽입','InsertTable'],['위에 행','InsertRowsBefore'],['아래에 행','InsertRowsAfter'],['왼쪽 열','InsertColumnsBefore'],['오른쪽 열','InsertColumnsAfter'],['행 삭제','DeleteRows'],['열 삭제','DeleteColumns'],['셀 병합','MergeCells'],['셀 나누기','SplitCell'],['표·셀 서식','TableDialog'],['행 높이','SetRowHeight'],['열 너비','SetColumnWidth'],['표 삭제','DeleteTable']])add(command(label,name));
    }else if(activeTab==='셀'){
      for(const [label,name]of [['행 삽입','InsertRowsBefore'],['열 삽입','InsertColumnsBefore'],['행 삭제','DeleteRows'],['열 삭제','DeleteColumns'],['셀 병합','MergeCells'],['행 높이','RowHeight'],['열 너비','ColumnWidth'],['셀 서식','FormatCellDialog'],['함수 마법사','FunctionDialog'],['오름차순','SortAscending'],['내림차순','SortDescending']])add(command(label,name));
      palette('셀 배경색','BackgroundColor');
    }else if(activeTab==='그림'||activeTab==='개체'){
      add({label:'선택·이동',run:()=>{map()._docLayer.postKeyboardEvent('input',0,1280);map().focus(false);}});
      if(family==='text')add({label:'자유 배치',needsObject:true,run:()=>{uno('SetAnchorToPara');uno('WrapThrough');}});
      for(const [label,name]of [['위치·크기','TransformDialog'],['맨 앞으로','BringToFront'],['맨 뒤로','SendToBack'],['왼쪽 맞춤','ObjectAlignLeft'],['가운데 맞춤','AlignCenter'],['오른쪽 맞춤','ObjectAlignRight'],['좌우 뒤집기','FlipHorizontal'],['상하 뒤집기','FlipVertical']])add(command(label,name,true));
    }else if(activeTab==='삽입'){
      add({label:'그림 삽입',run:()=>document.getElementById('insertgraphic')?.click()});
      for(const [label,name]of [['텍스트 상자','Text'],['사각형','BasicShapes.rectangle'],['타원','BasicShapes.ellipse'],['하이퍼링크','HyperlinkDialog'],['특수문자','InsertSymbol']])add(command(label,name));
      if(family==='text')add(command('표 삽입','InsertTable'));
      if(family==='presentation')add(command('새 슬라이드','InsertPage'));
    }else if(activeTab==='슬라이드'){
      for(const [label,name]of [['새 슬라이드','InsertPage'],['슬라이드 복제','DuplicatePage'],['슬라이드 삭제','DeletePage']])add(command(label,name));
    }else{
      for(const [label,name]of [['잘라내기','Cut'],['복사','Copy'],['붙여넣기','Paste'],['서식 없이 붙여넣기','PasteUnformatted'],['전체 선택','SelectAll'],['찾기·바꾸기','SearchDialog'],['글자 모양','FontDialog']])add(command(label,name));
      button(family==='presentation'?'슬라이드 도구':family==='spreadsheet'?'셀 도구':'표 도구',()=>openSheet(family==='presentation'?'슬라이드':family==='spreadsheet'?'셀':'표'));
      button('개체',()=>openSheet('개체'));button('읽기 모드로 전환',()=>map().setPermission('readonly'));
    }
    update();refreshLayout();
    // Resizing the chrome keeps the current native document camera.
    if(validViewport(view)&&!modeTransition)restore(view);
  }
  async function saveAndClose(saveOriginal=true){
    if(modeTransition)return;
    try{
      const changed=flushSettings();
      if(!policy.changed&&(changed||Date.now()-(policy.lastEditCommand||0)<3000)){const end=Date.now()+3000;while(!policy.changed&&Date.now()<end)await new Promise(resolve=>setTimeout(resolve,40));}
      persistViewport();window.postMobileMessage(saveOriginal?'DOCUMENT_STUDIO_SAVE_AND_CLOSE':'BYE');
    }catch(error){UI.toast(String(error.message||error),true);}
  }
  function mount(){
    const wrapper=document.getElementById('toolbar-wrapper');if(!wrapper||!UI)return false;
    document.documentElement.dataset.studioOfficeHost='true';wrapper.classList.add('studio-office-host');
    bar=document.createElement('section');bar.id='studio-office-tools';bar.dataset.family=map().getDocType();wrapper.append(bar);
    header=document.createElement('header');header.className='studio-ui-header';header.id='studio-office-header';header.setAttribute('aria-label','문서');
    const back=UI.iconButton('studio-office-back','돌아가기','back',()=>{if(editable())map().setPermission('readonly');else{persistViewport();window.postMobileMessage('BYE');}});
    const title=document.createElement('strong');title.textContent=bridge()?.getStudioDocumentName?.()||'Office 문서';
    undoButton=UI.iconButton('studio-office-undo','실행 취소','undo',()=>{releaseViewportPin();uno('Undo');});
    redoButton=UI.iconButton('studio-office-redo','다시 실행','redo',()=>{releaseViewportPin();uno('Redo');});
    modeButton=document.createElement('button');modeButton.id='studio-office-edit';modeButton.className='studio-office-action';modeButton.textContent='편집하기';modeButton.onclick=()=>{map()._switchToEditMode();update();};UI.retain(modeButton);
    saveButton=UI.iconButton('studio-office-save','저장 후 닫기','done',()=>{void saveAndClose();});
    const more=UI.iconButton('studio-office-more','문서 메뉴','more');header.append(back,title,undoButton,redoButton,modeButton,saveButton,more);bar.append(header);
    modeLabel=document.createElement('span');modeLabel.id='studio-office-mode-label';hint=document.createElement('div');hint.id='studio-office-hint';hint.setAttribute('aria-live','polite');bar.append(modeLabel,hint);
    bottom=document.createElement('section');bottom.id='studio-office-bottom';bottom.setAttribute('aria-label','모바일 편집 도구');
    sheet=document.createElement('section');sheet.id='studio-office-sheet';sheet.hidden=true;UI.sheet(sheet,'글자',closeSheet);
    actions=document.createElement('div');actions.id='studio-office-actions';actions.setAttribute('role','toolbar');sheet.append(actions);
    tabs=document.createElement('div');tabs.id='studio-office-tabs';tabs.className='studio-ui-dock';tabs.setAttribute('role','toolbar');tabs.setAttribute('aria-label','편집 도구');bottom.append(sheet,tabs);
    const lower=document.getElementById('toolbar-down');if(lower)lower.before(bottom);else document.body.append(bottom);
    UI.popup(more,()=>[
      {label:editable()?'읽기 모드로 전환':'편집하기',hidden:!policy.modeSupported||new URLSearchParams(location.search).get('permission')!=='edit',disabled:modeTransition||!initialViewportRestored,action:()=>editable()?map().setPermission('readonly'):map()._switchToEditMode()},
      {label:editable()?'찾기·바꾸기':'찾기',disabled:!editable()&&!searchService(),action:()=>editable()?uno('SearchDialog'):openSheet('찾기')},
      {label:'확대 '+Math.round((map().getZoomScale?.(map().getZoom(),10)||1)*100)+'%',action:()=>{releaseViewportPin();window.app.activeDocument.activeLayout.applyZoom(map().getZoom()+1);}},
      {label:'축소',action:()=>{releaseViewportPin();window.app.activeDocument.activeLayout.applyZoom(Math.max(0,map().getZoom()-1));}},
      {label:map().uiManager?.isRulerVisible?.()?'눈금자 숨기기':'눈금자 표시',hidden:family!=='text',action:()=>{map().uiManager.toggleRuler();refreshLayout();}},
      {label:groupOpen?'도구 접기':'도구 펼치기',hidden:!editable(),action:()=>groupOpen?closeSheet():openSheet(activeTab)},
      {label:policy.changed?'수정됨 · 작업 복사본 보호 중':'수정 없이 원본 보기',disabled:true},
      {label:'작업 화면으로 돌아가기',action:()=>{void saveAndClose(false);}}
    ]);
    const family=map().getDocType();
    if(family==='text'&&map().uiManager?.isRulerVisible?.())map().uiManager.hideRuler();
    for(const element of [bar,bottom]){
      if(window.L?.DomEvent?.disableClickPropagation)L.DomEvent.disableClickPropagation(element);
      else for(const name of ['mousedown','touchstart','pointerdown','dblclick','contextmenu'])element.addEventListener(name,event=>event.stopPropagation());
    }
    policy.modeSupported=installModeGate();
    map().on('commandstatechanged',event=>{if(event.commandName==='.uno:ModifiedStatus'&&String(event.state)==='true'&&policy.wasAuthorized)policy.changed=true;update();});
    app.events.on('contextchange',event=>{contextName=String(event.detail?.context||event.context||'');update();});
    app.events.on('updatepermission',()=>{update();refreshLayout();if(modeTransition)restore(transitionViewport,undefined,transitionGeneration);});
    map().on('moveend zoomend updateparts',rememberViewport);
    try{const saved=JSON.parse(bridge()?.getStudioViewState?.()||'{}');restore(saved,()=>{if(validViewport(saved))holdViewport(snapshot());initialViewportRestored=true;update();persistViewport();});}catch(ignore){initialViewportRestored=true;}
    render();policy.ready=true;return true;
  }
  const timer=setInterval(() => {
    try {
    if(window.app && app.LOUtil) app.LOUtil.isAIAssistantAvailable=()=>false;
    if(map() && map().wopi) {map().wopi.DisableAISettings=true;map().isAIConfigured=false;}
    if(map() && map()._docLoaded) {
      if(!bar) mount(); else update();
      // Only an explicit host EDIT launch may enter editing without a toolbar tap.
      if(bar && initialViewportRestored && !policy.initialIntentHandled) {
        policy.initialIntentHandled=true;
        if(policy.modeSupported && new URLSearchParams(location.search).get('studioopen')==='EDIT') map()._switchToEditMode();
      }
    }
    if(policy.ready && attempts%5===0) persistViewport();
    } catch(error) {
      // Keep bounded local diagnostics when an upstream UI API prevents mounting.
      // No document text is read, serialized or sent to a logging service.
      bridge()?.saveStudioViewState?.(JSON.stringify({
        error:'host-policy-update',name:String(error.name),message:String(error.message).slice(0,300),
        ready:policy.ready,family:map()?.getDocType?.()
      }));
    }
    if(++attempts>=300 && !policy.ready) clearInterval(timer);
  },200);
  window.addEventListener('pagehide',()=>{persistViewport();clearInterval(timer);clearTimeout(persistTimer);},{once:true});
})();
