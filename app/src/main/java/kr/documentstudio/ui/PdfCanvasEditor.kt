package kr.documentstudio.ui

import android.content.Context
import android.graphics.*
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.*
import kr.documentstudio.DocumentActions
import kr.documentstudio.core.DocumentChange
import kr.documentstudio.data.*
import java.io.File

/** Interactive PDF scene: hit regions reference exact content-stream strings and image occurrences. */
class PdfObjectCanvas(context: Context): FrameLayout(context) {
    var bitmap: Bitmap?=null
    var textRuns: List<PdfBodyRun> = emptyList()
    var images: List<PdfImagePlacement> = emptyList()
    var editable=false
        set(value) {
            if(field==value) return
            field=value
            if(!value) {
                input?.clearFocus()
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken,0)
                clearSelection(false)
            }
            input?.isEnabled=value
        }
    var selection: Any?=null;private set
    var onSelection: (Any?)->Unit={}
    var onPreview: (DocumentChange)->Unit={}
    var onCommit: (DocumentChange)->Unit={}
    var onCancelPreview: ()->Unit={}
    var page=0
    private var input: EditText?=null
    private var dirtyText: String?=null
    private var imageFrame: RectF?=null
    private var down=PointF()
    private var resizing=false
    private var moved=false
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    init {setWillNotDraw(false);contentDescription="PDF 원본 페이지 직접 편집";clipChildren=false}
    private fun rectangle(x: Float,y: Float,w: Float,h: Float)=RectF(x*width/100,y*height/100,(x+w)*width/100,(y+h)*height/100)
    private fun imageChange(image: PdfImagePlacement,rect: RectF)=DocumentChange("pdf.transformImage",page.toString(),mapOf("id" to image.id.toString(),"expected" to image.token,"x" to (rect.left*100/width).toString(),"y" to (rect.top*100/height).toString(),"width" to (rect.width()*100/width).toString(),"height" to (rect.height()*100/height).toString()))
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas);bitmap?.takeUnless {it.isRecycled}?.let {canvas.drawBitmap(it,null,RectF(0f,0f,width.toFloat(),height.toFloat()),paint)}
        val rect=when(val item=selection) {
            is PdfImagePlacement->imageFrame ?: rectangle(item.x,item.y,item.width,item.height)
            is PdfBodyRun->item.bounds?.let {rectangle(it.x,it.y,it.width,it.height)}
            else->null
        }
        rect?.let {paint.color=Color.rgb(40,88,165);paint.style=Paint.Style.STROKE;paint.strokeWidth=2f*resources.displayMetrics.density;canvas.drawRect(it,paint)
            if(selection is PdfImagePlacement) {paint.style=Paint.Style.FILL;canvas.drawCircle(it.right,it.bottom,6f*resources.displayMetrics.density,paint)}
            paint.style=Paint.Style.FILL
        }
    }
    fun clearSelection(commit: Boolean=true) {
        val item=selection
        if(commit && editable && item is PdfBodyRun && dirtyText!=null && dirtyText!=item.text) onCommit(DocumentChange("pdf.replaceText",page.toString(),mapOf("id" to item.id.toString(),"expected" to item.text,"text" to dirtyText!!)))
        input?.let {removeView(it)};input=null;dirtyText=null;selection=null;imageFrame=null;onSelection(null);invalidate()
    }
    fun syncInput(value: String) {
        val editor=input ?: return
        if(editor.text.toString()==value) return
        val start=editor.selectionStart.coerceIn(0,value.length);val end=editor.selectionEnd.coerceIn(0,value.length)
        editor.tag=true;editor.setText(value);editor.setSelection(start,end);editor.tag=false;dirtyText=value
    }
    private fun selectText(run: PdfBodyRun,x: Float) {
        selection=run;dirtyText=null;onSelection(run)
        val bounds=run.bounds ?: return;val rect=rectangle(bounds.x,bounds.y,bounds.width,bounds.height)
        val editor=EditText(context).apply {
            setSingleLine(true);setPadding(0,0,0,0);setBackgroundColor(Color.TRANSPARENT)
            // The PDF renderer paints the exact original-font glyphs; this transparent
            // input supplies selection, IME and cursor only, in the same page rectangle.
            setTextColor(Color.TRANSPARENT);highlightColor=0x44326ac0;gravity=Gravity.TOP
            textSize=(rect.height()/resources.displayMetrics.scaledDensity/1.1f).coerceAtLeast(6f)
            typeface=Typeface.create(run.font.substringAfter('+'),Typeface.NORMAL)
            contentDescription="선택한 PDF 본문";imeOptions=EditorInfo.IME_ACTION_DONE
            setText(run.text);setSelection(((x-rect.left)/rect.width()*run.text.length).toInt().coerceIn(0,run.text.length))
            addTextChangedListener(object: TextWatcher {
                override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) {}
                override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) {
                    if(tag==true || !editable) return
                    dirtyText=s.toString();onPreview(DocumentChange("pdf.replaceText",page.toString(),mapOf("id" to run.id.toString(),"expected" to run.text,"text" to s.toString())))
                }
                override fun afterTextChanged(s: Editable?) {}
            })
            setOnEditorActionListener {_,action,_->if(action==EditorInfo.IME_ACTION_DONE) {clearSelection();true} else false}
        }
        input=editor;addView(editor,LayoutParams((width-rect.left).toInt().coerceAtLeast(1),rect.height().toInt().coerceAtLeast(32)).apply {leftMargin=rect.left.toInt();topMargin=rect.top.toInt()})
        editor.requestFocus();(context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(editor,InputMethodManager.SHOW_IMPLICIT)
        invalidate()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(!editable || width==0 || height==0) return false
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN->{
                val current=selection as? PdfImagePlacement
                val currentRect=current?.let {rectangle(it.x,it.y,it.width,it.height)}
                val handle=18f*resources.displayMetrics.density
                resizing=currentRect!=null && kotlin.math.hypot(event.x-currentRect.right,event.y-currentRect.bottom)<=handle
                val image=if(resizing) current else images.lastOrNull {rectangle(it.x,it.y,it.width,it.height).contains(event.x,event.y)}
                if(image!=null) {
                    clearSelection();selection=image;onSelection(image);imageFrame=rectangle(image.x,image.y,image.width,image.height)
                    down=PointF(event.x,event.y);moved=false;parent.requestDisallowInterceptTouchEvent(true);invalidate();return true
                }
                val text=textRuns.lastOrNull {it.bounds?.let {b->rectangle(b.x,b.y,b.width,b.height).apply {inset(-4f,-4f)}.contains(event.x,event.y)}==true}
                clearSelection();if(text!=null) {selectText(text,event.x);return true};return false
            }
            MotionEvent.ACTION_MOVE->{val image=selection as? PdfImagePlacement ?: return false
                val original=rectangle(image.x,image.y,image.width,image.height)
                val dx=event.x-down.x;val dy=event.y-down.y
                if(kotlin.math.hypot(dx,dy)<4f && !moved) return true
                moved=true
                val frame=if(resizing) RectF(original.left,original.top,(original.right+dx).coerceIn(original.left+8f,width.toFloat()),(original.bottom+dy).coerceIn(original.top+8f,height.toFloat()))
                    else RectF(original).apply {offset(dx.coerceIn(-original.left,width-original.right),dy.coerceIn(-original.top,height-original.bottom))}
                imageFrame=frame;onPreview(imageChange(image,frame));invalidate();return true
            }
            MotionEvent.ACTION_UP->{val image=selection as? PdfImagePlacement
                if(image!=null && moved) imageFrame?.let {onCommit(imageChange(image,it))}
                parent.requestDisallowInterceptTouchEvent(false);performClick();return image!=null
            }
            MotionEvent.ACTION_CANCEL->{imageFrame=null;moved=false;onCancelPreview();parent.requestDisallowInterceptTouchEvent(false);invalidate();return true}
        };return false
    }
    override fun performClick(): Boolean {super.performClick();return true}
}

@Composable
fun PdfCanvasEditor(s: Session,page: Int,vm: DocumentActions,busy: Boolean,readOnly: Boolean=true) {
    val scope=rememberCoroutineScope()
    val verticalPosition=rememberScrollState()
    val horizontalPosition=rememberScrollState()
    var canvas by remember(s.id,page) {mutableStateOf<PdfObjectCanvas?>(null)}
    var selected by remember(s.base,page) {mutableStateOf<Any?>(null)}
    var bitmap by remember(s.base,page) {mutableStateOf<Bitmap?>(null)}
    var runs by remember(s.base,page) {mutableStateOf(emptyList<PdfBodyRun>())}
    var images by remember(s.base,page) {mutableStateOf(emptyList<PdfImagePlacement>())}
    var failure by remember(s.base,page) {mutableStateOf<String?>(null)}
    var previewJob by remember {mutableStateOf<Job?>(null)}
    var previewRevision by remember {mutableIntStateOf(0)}
    var zoom by rememberSaveable {mutableFloatStateOf(1f)}
    val owned=remember(s.base,page) {mutableSetOf<Bitmap>()}
    fun display(image: Bitmap) {val old=bitmap;bitmap=image;canvas?.bitmap=image;canvas?.invalidate();owned+=image;if(old!==image) {owned.remove(old);canvas?.post {old?.recycle()} ?: old?.recycle()}}
    suspend fun render(values: Map<String,String>,change: DocumentChange?=null): Triple<Bitmap,List<PdfBodyRun>,List<PdfImagePlacement>> {
        var produced: Bitmap?=null
        try {return withContext(Dispatchers.IO) {
            if(change==null && values.isEmpty() && s.edits.strokes.isEmpty()) {
                val text=PdfBodyText.runs(s.base,page).filter {it.bounds!=null}
                val graphics=PdfImages.list(s.base,page)
                val image=PdfEngine.render(s.base,page);produced=image
                return@withContext Triple(image,text,graphics)
            }
            val file=File.createTempFile("pdf-scene-",".pdf",s.base.parentFile)
            val changed=File.createTempFile("pdf-scene-change-",".pdf",s.base.parentFile)
            try {
                PdfEngine.write(s.base,file,s.edits.strokes,false,values,s.edits.layers)
                val source=if(change!=null) {PdfEngine.change(file,changed,change);changed} else file
                val text=PdfBodyText.runs(source,page).filter {it.bounds!=null};val graphics=PdfImages.list(source,page)
                val image=PdfEngine.render(source,page);produced=image
                Triple(image,text,graphics)
            } finally {file.delete();changed.delete()}
        }} catch(e: CancellationException) {produced?.recycle();throw e}
    }
    fun show(scene: Triple<Bitmap,List<PdfBodyRun>,List<PdfImagePlacement>>) {display(scene.first);runs=scene.second;images=scene.third}
    LaunchedEffect(s.base,page,s.edits.values,s.edits.strokes) {
        delay(120)
        try {show(render(s.edits.values));failure=null}
        catch(e: CancellationException) {throw e}
        catch(e: Exception) {failure=e.message ?: "페이지 편집 결과를 표시하지 못했습니다."}
    }
    DisposableEffect(s.base,page) {onDispose {previewJob?.cancel();canvas?.bitmap=null;owned.forEach {it.recycle()};owned.clear()}}
    fun resetPreview() {
        previewJob?.cancel();previewRevision++
        previewJob=scope.launch {try {show(render(s.edits.values));failure=null} catch(e: CancellationException) {throw e} catch(e: Exception) {failure=e.message}}
    }
    fun preview(change: DocumentChange) {
        if(readOnly || busy) return
        if(change.type=="pdf.replaceText") {
            val key=PdfTextEdits.key(page,change.arguments.getValue("id").toInt())
            vm.field(key,PdfTextEdits.value(s.edits.values[key],change.arguments.getValue("expected"),change.arguments.getValue("text")))
            return
        }
        previewJob?.cancel();val revision=++previewRevision
        previewJob=scope.launch {
            delay(120)
            try {
                val scene=render(s.edits.values,change)
                if(revision==previewRevision) {display(scene.first);failure=null} else scene.first.recycle()
            } catch(e: CancellationException) {throw e}
            catch(e: Exception) {failure=e.message ?: "이미지 편집 결과를 표시하지 못했습니다."}
        }
    }
    LaunchedEffect(readOnly) {if(readOnly) {canvas?.clearSelection(false);resetPreview()}}
    Column(Modifier.fillMaxSize().testTag("pdf-direct-editor")) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(if(readOnly) "원본 페이지 보기" else if(selected is PdfBodyRun) "본문 선택" else if(selected is PdfImagePlacement) "이미지 선택" else "글자·이미지를 클릭하세요",style=MaterialTheme.typography.labelMedium)
            if(!readOnly) TextButton({canvas?.clearSelection()},enabled=!busy && selected!=null) {Text("적용")}
            if(!readOnly) TextButton({
                val text=selected as? PdfBodyRun
                if(text!=null) {val key=PdfTextEdits.key(page,text.id);vm.field(key,PdfTextEdits.value(s.edits.values[key],text.text,text.text))}
                canvas?.clearSelection(false);resetPreview()
            },enabled=!busy && selected!=null) {Text("취소")}
            if(!readOnly && selected is PdfImagePlacement) TextButton({val image=selected as PdfImagePlacement;canvas?.clearSelection(false);vm.change(DocumentChange("pdf.deleteImage",page.toString(),mapOf("id" to image.id.toString(),"expected" to image.token)))},enabled=!busy) {Text("이미지 삭제")}
            TextButton({zoom=(zoom-0.25f).coerceAtLeast(1f)}) {Text("−")};Text("${(zoom*100).toInt()}%")
            TextButton({zoom=(zoom+0.25f).coerceAtMost(3f)}) {Text("+")}
        }
        failure?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(8.dp))}
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val pageWidth=(maxWidth-24.dp).coerceAtLeast(1.dp)
            val image=bitmap
            if(image==null) Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {if(failure==null) CircularProgressIndicator() else Text("페이지를 편집 가능한 상태로 표시하지 못했습니다.")}
            else Box(Modifier.fillMaxSize().verticalScroll(verticalPosition).horizontalScroll(horizontalPosition).padding(12.dp)) {
                AndroidView(factory={context->PdfObjectCanvas(context).also {canvas=it}},update={view->
                    view.bitmap=image;view.page=page;view.editable=!busy && !readOnly;view.textRuns=runs;view.images=images;view.onSelection={selected=it}
                    (view.selection as? PdfBodyRun)?.let {run->
                        val pending=s.edits.values[PdfTextEdits.key(page,run.id)]
                        val value=pending?.let {org.json.JSONObject(it).getString("text")} ?: runs.firstOrNull {it.id==run.id}?.text
                        if(value!=null) view.syncInput(value)
                    }
                    view.onPreview={preview(it)};view.onCancelPreview={resetPreview()};view.onCommit={if(!readOnly && !busy && it.type!="pdf.replaceText") {previewJob?.cancel();previewRevision++;vm.change(it)}};view.invalidate()
                },modifier=Modifier.width(pageWidth*zoom).aspectRatio(image.width.toFloat()/image.height))
            }
        }
        Text(if(readOnly) "원본 페이지 · 편집하기를 선택하면 지원되는 글자와 이미지를 수정할 수 있습니다." else "원본 폰트의 가로 본문·페이지 직접 이미지를 편집합니다. 스캔·중첩/태그/회전 본문은 보호합니다.",style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(8.dp))
    }
}
