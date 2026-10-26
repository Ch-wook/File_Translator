const assert=require('node:assert/strict');
const zlib=require('node:zlib');
function pngBytes(){
 const crc=b=>{let c=0xffffffff;for(const v of b){c^=v;for(let j=0;j<8;j++)c=(c>>>1)^((c&1)?0xedb88320:0);}return (c^0xffffffff)>>>0;};
 const chunk=(name,data)=>{const type=Buffer.from(name),size=Buffer.alloc(4),check=Buffer.alloc(4);size.writeUInt32BE(data.length);check.writeUInt32BE(crc(Buffer.concat([type,data])));return Buffer.concat([size,type,data,check]);};
 const head=Buffer.alloc(13);head.writeUInt32BE(8,0);head.writeUInt32BE(8,4);head[8]=8;head[9]=6;
 const pixels=Buffer.alloc(8*(1+8*4));for(let y=0;y<8;y++)for(let x=0;x<8;x++){pixels[y*33+1+x*4]=255;pixels[y*33+4+x*4]=255;}
 return Buffer.concat([Buffer.from('89504e470d0a1a0a','hex'),chunk('IHDR',head),chunk('IDAT',zlib.deflateSync(pixels)),chunk('IEND',Buffer.alloc(0))]);
}
const image=pngBytes();

// Runs inside the production phone host. Only setup/navigation use the controller; gestures use actual touch events.
module.exports=async function mobileStructure({page,frame,call,context}){
 const report={};const near=(a,b)=>assert.ok(Math.abs(a-b)<.08,JSON.stringify({actual:a,expected:b}));
 await call('navigate',true);await call('plainText','\nMOBILE_BEFORE\nMOBILE_PARA\nMOBILE_AFTER\nTABLE_ANCHOR');
 await call('find','MOBILE_BEFORE');const before=(await call('state')).para;
 await call('find','MOBILE_AFTER');const after=(await call('state')).para;
 await call('find','MOBILE_PARA');
 await call('para',{alignment:'center',lineSpacingType:'Percent',lineSpacing:200,marginLeft:400,spacingBefore:300});
 const paragraph=(await call('state')).para;assert.equal(paragraph.alignment,'center');assert.equal(paragraph.lineSpacing,200);
 near(paragraph.marginLeft,2*96/72);near(paragraph.spacingBefore,3*96/72);
 await call('find','나다');await call('copyCharFormat');await call('find','MOBILE_PARA');await call('pasteCharFormat');
 assert.equal((await call('state')).char.fontSize,1800);assert.equal((await call('state')).para.alignment,'center','Copying only character formatting preserves paragraph alignment');
 await call('dispatch','edit:undo');
 for(const [text,expected] of [['MOBILE_BEFORE',before],['MOBILE_AFTER',after]]){
  await call('find',text);for(const k of ['alignment','lineSpacing','marginLeft','spacingBefore'])assert.deepEqual((await call('state')).para[k],expected[k],'Unselected paragraph '+k+' stays unchanged');
 }
 // A real multilevel native list, confined to these paragraphs.
 await call('find','MOBILE_BEFORE');const from=await call('capture');
 await call('find','MOBILE_AFTER');const to=await call('capture');
 await call('restore',{...to,range:{anchor:from.range.anchor,focus:to.range.focus}});
 await call('list','number',4);
 let numbering;
 for(const text of ['MOBILE_BEFORE','MOBILE_PARA','MOBILE_AFTER']){
  await call('find',text);const p=(await call('state')).para;assert.equal(p.headType,'Number');numbering??=p.numberingId;assert.equal(p.numberingId,numbering);
 }
 await call('find','MOBILE_PARA');await call('list','deeper');assert.equal((await call('state')).para.paraLevel,1);
 await call('dispatch','edit:undo');await call('find','MOBILE_PARA');assert.equal((await call('state')).para.paraLevel,0);
 await call('find','TABLE_ANCHOR');assert.notEqual((await call('state')).para.headType,'Number');
 report.paragraphScope=true;report.characterOnlyFormatCopy=true;report.nativeMultiParagraphNumbering=true;

 // Pending character properties apply to future input without changing existing text.
 await call('navigate',true);await call('plainText','\n');await call('char',{fontSize:1500,textColor:'#1e88e5',bold:false});
 await call('dispatch','format:bold');assert.equal((await call('state')).char.bold,true);
 await call('dispatch','format:bold');assert.equal((await call('state')).char.bold,false);
 await call('focus');await page.keyboard.insertText('MOBILE_CARET');
 await call('find','MOBILE_CARET');assert.equal((await call('state')).char.fontSize,1500);assert.equal((await call('state')).char.textColor.toLowerCase(),'#1e88e5');
 report.futureTypingFormat=true;
 // The document default font changes only font, retaining the selected character size/color.
 const previous=(await call('state')).char;
 await call('defaultFont');assert.equal((await call('state')).char.fontSize,previous.fontSize);assert.equal((await call('state')).char.textColor,previous.textColor);
 await call('dispatch','edit:undo');
 await call('navigate',true);await call('plainText','\n');await call('createTable',2,2);
 for(const [index,text] of ['MOBILE_CELL_A','MOBILE_CELL_B','MOBILE_CELL_C','MOBILE_CELL_D'].entries()){
  if(index)await call('moveCell',true);await call('plainText',text);
 }
 for(let iteration=0;iteration<3;iteration++){
  await call('find','MOBILE_CELL_A');await call('selectCells',0,1);await call('dispatch','table:cell-merge');
  for(const text of ['MOBILE_CELL_A','MOBILE_CELL_B','MOBILE_CELL_C','MOBILE_CELL_D'])assert.equal(await call('count',text),1,'Merge retains '+text);
  await call('find','MOBILE_CELL_A');await call('dispatch','table:cell-split');
  await frame.locator('#csd-col-check').waitFor();
  await frame.locator('.modal-overlay .dialog-btn-primary').click();
  await frame.locator('#csd-col-check').waitFor({state:'detached'});
  await call('find','MOBILE_CELL_A');const cell=(await call('state')).cell;
  assert.equal(cell.dims.rowCount,2);assert.equal(cell.dims.colCount,2);
  for(const text of ['MOBILE_CELL_A','MOBILE_CELL_B','MOBILE_CELL_C','MOBILE_CELL_D'])assert.equal(await call('count',text),1,'Split retains '+text);
 }
 await call('find','MOBILE_CELL_C');await call('dispatch','table:insert-row-below');assert.equal((await call('state')).cell.dims.rowCount,3);
 await call('dispatch','edit:undo');await call('find','MOBILE_CELL_C');assert.equal((await call('state')).cell.dims.rowCount,2);
 await call('dispatch','table:insert-col-right');assert.equal((await call('state')).cell.dims.colCount,3);
 await call('dispatch','edit:undo');await call('find','MOBILE_CELL_C');assert.equal((await call('state')).cell.dims.colCount,2);
 await call('find','CELL_C');await call('char',{bold:true,shadeColor:'#ffeb3b'});
 assert.equal((await call('state')).char.bold,true);assert.equal((await call('state')).char.shadeColor.toLowerCase(),'#ffeb3b');
 await call('find','MOBILE_CELL_D');assert.notEqual((await call('state')).char.shadeColor?.toLowerCase(),'#ffeb3b');
 report.tableRowsColumns=true;report.mergeSplitCycles=3;report.cellScopedTextFormat=true;

 const cdp=await context.newCDPSession(page);
 const touch=async(type,x,y)=>cdp.send('Input.dispatchTouchEvent',{type,touchPoints:type==='touchEnd'?[]:[{x,y,id:1,radiusX:2,radiusY:2,force:1}]});
 const drag=async(a,b)=>{
  await touch('touchStart',a.x,a.y);for(let step=1;step<=8;step++){await touch('touchMove',a.x+(b.x-a.x)*step/8,a.y+(b.y-a.y)*step/8);await page.waitForTimeout(20);}await touch('touchEnd');
 };
 // Insert a real image through the existing file picker and touch placement.
 await call('navigate',true);await call('plainText','\nMOBILE_IMAGE_ANCHOR\n');
 await page.locator('#groups').getByRole('tab',{name:'그림',exact:true}).click();
 const chooser=page.waitForEvent('filechooser');
 await page.locator('#tools').getByRole('button',{name:'그림 삽입',exact:true}).click();
 await(await chooser).setFiles({name:'mobile-image.png',mimeType:'image/png',buffer:image});
 await page.waitForTimeout(300);
 const canvas=await frame.locator('.page-canvas').last().boundingBox();assert.ok(canvas);
 const bounds=await page.locator('#engine').boundingBox();assert.ok(bounds);
 const start={x:Math.max(bounds.x+40,Math.min(bounds.x+bounds.width-130,canvas.x+canvas.width*.35)),y:Math.max(bounds.y+100,Math.min(bounds.y+bounds.height-140,canvas.y+160))};
 await drag(start,{x:start.x+70,y:start.y+55});
 await frame.waitForFunction(()=>window.rhwpStudio.documentStudio.mobile.state().object);
 assert.equal(await call('count','가나다라'),1,'Image insertion preserves existing text');
 const image=(await call('state')).objectDetails;assert.equal(image.ref.type,'image');
 const resize=frame.getByRole('button',{name:'개체 크기 se',exact:true});await resize.waitFor({state:'visible'});
 const grip=await resize.boundingBox();await drag({x:grip.x+22,y:grip.y+22},{x:grip.x+47,y:grip.y+42});
 const resized=(await call('state')).objectDetails;
 assert.ok(resized.props.width!==image.props.width||resized.props.height!==image.props.height,'Touch resize changes real image properties');
 await call('dispatch','edit:undo');
 report.imageFilePicker=true;report.imageTouchPlacement=true;report.imageTouchResize=true;report.imageUndo=true;

 // Long-press is real word selection; the end handle grows that selection without editing text.
 await call('find','MOBILE_BEFORE');await call('focus');await page.keyboard.press('ArrowLeft');await call('reveal');
 const highlights=frame.locator('.caret').filter({visible:true});const caret=await highlights.first().boundingBox();assert.ok(caret);
 await touch('touchStart',caret.x+12,caret.y+caret.height/2);await page.waitForTimeout(550);await touch('touchEnd');
 await frame.getByRole('button',{name:'선택 끝 핸들',exact:true}).waitFor({state:'visible'});
 const selected=await call('capture');assert.ok(selected.range);
 const handle=await frame.getByRole('button',{name:'선택 끝 핸들',exact:true}).boundingBox();
 await drag({x:handle.x+22,y:handle.y+22},{x:handle.x+45,y:handle.y+22});
 assert.ok((await call('capture')).range);assert.equal(await call('count','MOBILE_BEFORE'),1);
 report.longPressSelection=true;report.touchSelectionHandles=true;
 await cdp.detach();return report;
};

module.exports.image=image;
