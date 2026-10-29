package kr.documentstudio

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import kr.documentstudio.data.DocumentRepository
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real isolated native engines, actual assets and Android touch events. No physical device claims. */
@RunWith(AndroidJUnit4::class)
class ViewerNavigationTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val device get()=UiDevice.getInstance(instrumentation)
    private val evidence get()=File(context.getExternalFilesDir(null),"native-viewer").apply {mkdirs()}
    private fun eval(kind: String,script: String): String {
        val uri=Uri.parse("content://"+context.packageName+"."+kind+"probe")
        val raw=context.contentResolver.acquireUnstableContentProviderClient(uri).use {client->
            val reply=checkNotNull(client).call("eval",script,null)!!
            checkNotNull(reply.getString("result")) {"Native "+kind+" probe is not ready (ready="+reply.getBoolean("ready")+")"}
        }
        return JSONTokener(raw).nextValue().toString()
    }
    private fun waitFor(label: String,timeout: Long=30000,condition: ()->Boolean) {
        val end=SystemClock.uptimeMillis()+timeout
        var lastFailure: Throwable?=null
        while(SystemClock.uptimeMillis()<end){
            try{if(condition())return}catch(failure: Throwable){lastFailure=failure}
            SystemClock.sleep(100)
        }
        throw AssertionError(label,lastFailure)
    }
    private fun tap(label: String)=NativeUiActions.tap(device,context.packageName,label,20000)
    private fun documentBounds()=requireNotNull(device.wait(Until.findObject(By.pkg(context.packageName).clazz("android.webkit.WebView")),15000)).visibleBounds
    private fun pinch() {
        val rect=documentBounds();val cx=rect.exactCenterX();val cy=rect.top+rect.height()*.42f
        val down=SystemClock.uptimeMillis()
        fun inject(action: Int,count: Int,spread: Float){
            val properties=Array(count){i->MotionEvent.PointerProperties().apply {id=i;toolType=MotionEvent.TOOL_TYPE_FINGER}}
            val points=Array(count){i->MotionEvent.PointerCoords().apply {x=cx+(if(i==0)-spread else spread);y=cy;pressure=1f;size=.1f}}
            val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,count,properties,points,0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0)
            try{assertTrue(instrumentation.uiAutomation.injectInputEvent(event,true))}finally{event.recycle()};SystemClock.sleep(40)
        }
        inject(MotionEvent.ACTION_DOWN,1,40f);inject(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,40f)
        for(spread in listOf(50f,65f,80f))inject(MotionEvent.ACTION_MOVE,2,spread)
        inject(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,80f);inject(MotionEvent.ACTION_UP,1,80f)
    }
    @Test fun hwpxTwentyPagesReachLastAndPinchWithoutDocumentWrites()=exercise("viewer-20.hwpx","hangul",20)
    @Test fun wordTwelvePagesReachLastAndScrollWithoutDocumentWrites()=exercise("viewer-12.docx","office",12)
    @Test fun presentationThirtySlidesReachLastWithoutDocumentWrites()=exercise("viewer-30.pptx","office",30)
    @Test fun spreadsheetAllThreeSheetsAndDistantCellRemainAccessible()=exercise("viewer-large.xlsx","office",3)
    private fun exercise(name: String,kind: String,count: Int)=runBlocking {
        device.wakeUp();device.executeShellCommand("am force-stop com.android.launcher3")
        val original=File(context.cacheDir,"tests/viewer-"+UUID.randomUUID()+"-"+name).apply {parentFile!!.mkdirs()}
        instrumentation.context.assets.open(name).use {input->original.outputStream().use {input.copyTo(it)}}
        val uri=FileProvider.getUriForFile(context,context.packageName+".testfiles",original)
        val repo=DocumentRepository(context);val session=repo.open(uri);val working=repo.prepareOffice(session)
        val originalHash=DocumentRepository.digest(original);val workingHash=DocumentRepository.digest(working)
        val api=if(kind=="hangul")"document.getElementById('engine')?.contentWindow?.rhwpStudio?.documentStudio" else "window.studioOffice"
        val state=if(kind=="hangul")api+"?.pageState?.()" else api+"?.viewerState?.()"
        val index=if(kind=="hangul")"page" else "index"
        val total=if(kind=="hangul")"pages" else "count"
        val closed=File(working.path+".closed");var completed=false
        val record=JSONObject().put("file",name).put("expectedCount",count)
        val probeClient=checkNotNull(context.contentResolver.acquireUnstableContentProviderClient(Uri.parse("content://"+context.packageName+"."+kind+"probe")))
        try{
            // Provider lifecycle observation must be registered before Activity
            // creation. Resolving the provider after onCreate can miss that event.
            probeClient.call("eval","true",null)
            record.put("probePrimedBeforeActivity",true)
            val intent=if(kind=="hangul")Intent(context,HangulEditorActivity::class.java)
                else Intent().setClassName(context.packageName,"org.libreoffice.androidlib.StudioOfficeActivity")
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("sessionId",session.id)
                .putExtra("workingPath",working.absolutePath).putExtra("displayName",name))
            waitFor("Actual native engine loads all logical pages or sheets",150000){JSONObject(eval(kind,state)).getInt(total)==count}
            assertEquals(count,JSONObject(eval(kind,state)).getInt(total))
            if(name.endsWith(".hwpx")||name.endsWith(".docx")){
                val r=documentBounds();device.swipe(r.centerX(),r.top+r.height()*3/4,r.centerX(),r.top+r.height()/3,30)
                waitFor("Actual one-finger scrolling reaches another page"){JSONObject(eval(kind,state)).getInt(index)>0}
                record.put("actualAndroidContinuousScroll",true)
            }
            if(name.endsWith(".pptx")){
                val r=documentBounds();device.swipe(r.left+r.width()*3/4,r.top+r.height()/2,r.left+r.width()/4,r.top+r.height()/2,25)
                waitFor("Native horizontal gesture reaches next slide"){JSONObject(eval(kind,state)).getInt(index)>0}
                record.put("actualAndroidSlideSwipe",true)
            }
            if(name.endsWith(".xlsx")){
                tap("문서 메뉴");tap("시트 목록");tap("Viewer 3")
                waitFor("Third native sheet selected"){JSONObject(eval(kind,state)).getInt(index)==2}
                // Use the actual readonly search UI, not an edit-only UNO address
                // command rejected by the native frontend's readonly policy.
                tap("문서 메뉴");tap("찾기")
                val input=device.wait(Until.findObject(By.pkg(context.packageName).desc("찾을 내용")),10000)
                    ?: device.wait(Until.findObject(By.pkg(context.packageName).clazz("android.widget.EditText")),10000)
                    ?: throw AssertionError("Actual readonly cell search field missing")
                input.text="CELL_CV5000_S3"
                tap("다음 결과");tap("설정 닫기")
                waitFor("Native search reaches the distant populated cell"){
                    eval(kind,"app.activeDocument.activeLayout.viewedRectangle.x1>10000 && app.activeDocument.activeLayout.viewedRectangle.y1>100000")=="true"
                }
                record.put("distantCellReachedBySearch",true)
                record.put("distantCell","CV5000")
            }else{
                tap("문서 메뉴");tap("마지막 페이지")
                waitFor("Actual last logical page selected"){JSONObject(eval(kind,state)).getInt(index)==count-1}
                tap("이전 페이지");waitFor("Actual previous page"){JSONObject(eval(kind,state)).getInt(index)==count-2}
                tap("다음 페이지");waitFor("Actual next page"){JSONObject(eval(kind,state)).getInt(index)==count-1}
            }
            val zoom=if(kind=="hangul")api+".captureView().zoom" else "app.map.getZoom()"
            val before=eval(kind,zoom).toDouble();pinch()
            waitFor("Actual Android two-finger input changes display scale"){eval(kind,zoom).toDouble()>before+.01}
            record.put("zoomBefore",before).put("zoomAfter",eval(kind,zoom).toDouble()).put("actualAndroidPinch",true)
            if(kind=="office"){
                val beforeDouble=eval(kind,zoom).toDouble();val r=documentBounds()
                device.click(r.centerX(),r.top+r.height()*2/5);SystemClock.sleep(100);device.click(r.centerX(),r.top+r.height()*2/5)
                waitFor("Office reading double tap zooms without entering editing"){eval(kind,zoom).toDouble()>beforeDouble+.01}
                assertEquals("false",eval(kind,"app.map.isEditMode()"))
                assertEquals("false",eval(kind,"window.studioOffice.authorized"))
                record.put("readingDoubleTapZoom",true).put("doubleTapStaysReadonly",true)
            }
            if(name.endsWith(".docx")){
                tap("문서 메뉴");tap("첫 페이지")
                waitFor("First page"){JSONObject(eval(kind,state)).getInt(index)==0}
                val y=eval(kind,"app.activeDocument.activeLayout.viewedRectangle.y1").toDouble()
                val r=documentBounds();device.swipe(r.centerX(),r.top+r.height()*3/4,r.centerX(),r.top+r.height()/3,30)
                waitFor("Writer continuous layout scrolls beyond first page"){eval(kind,"app.activeDocument.activeLayout.viewedRectangle.y1").toDouble()>y+100}
                record.put("continuousScroll",true)
            }
            val beforeRotation=JSONObject(eval(kind,state));val zoomBeforeRotation=eval(kind,zoom).toDouble()
            val rotationToken=UUID.randomUUID().toString();eval(kind,"window.__viewerRotationToken="+JSONObject.quote(rotationToken))
            device.setOrientationLeft();SystemClock.sleep(1000)
            waitFor("Viewer survives native landscape rotation"){eval(kind,"innerWidth>innerHeight")=="true" && JSONObject(eval(kind,state)).getInt(total)==count}
            device.setOrientationNatural()
            waitFor("Viewer is ready after returning to portrait"){eval(kind,"innerWidth<innerHeight")=="true" && JSONObject(eval(kind,state)).getInt(total)==count}
            assertFalse(File(working.path+".edited").exists())
            assertEquals(workingHash,DocumentRepository.digest(working));assertEquals(originalHash,DocumentRepository.digest(original))
            if(kind=="hangul"){
                assertEquals("Rotation must keep the same live engine",rotationToken,eval(kind,"window.__viewerRotationToken"))
                record.put("sameEngineAcrossRotation",true)
                assertEquals("Rotation keeps the document page",beforeRotation.getInt(index),JSONObject(eval(kind,state)).getInt(index))
                assertEquals("Rotation keeps display zoom",zoomBeforeRotation,eval(kind,zoom).toDouble(),.002)
                record.put("rotationPageAndZoomPreserved",true)
            }
            record.put("finalState",JSONObject(eval(kind,state))).put("nativeRotation",true).put("workingUnchanged",true).put("originalUnchanged",true)
            device.takeScreenshot(File(evidence,name+".png"))
            device.pressBack()
            if(kind=="office")waitFor("Office close request"){File(working.path+".close-request").isFile}
            else waitFor("Hangul close"){closed.isFile}
            if(kind=="office"){waitFor("Native Office exits"){device.executeShellCommand("pidof "+context.packageName+":office").trim().isEmpty()};assertFalse(repo.completeOfficeClose(session))}
            assertEquals(originalHash,DocumentRepository.digest(original));assertFalse(File(working.path+".edited").exists())
            completed=true
        }finally{
            runCatching {device.setOrientationNatural();device.unfreezeRotation()}
            runCatching {probeClient.close()}
            if(!completed)runCatching {
                record.put("observedState",JSONObject(eval(kind,state)))
                if(kind=="office")record.put("officeGeometry",JSONObject(eval(kind,"JSON.stringify({layout:app.activeDocument?.activeLayout?.type,view:app.activeDocument?.activeLayout?.viewedRectangle,zoom:app.map.getZoom(),address:(document.querySelector('#addressInput input')||document.querySelector('input#addressInput'))?.value,cellAddress:app.map.stateChangeHandler.getItemValue('.uno:CellAddress'),changed:window.studioOffice.changed,dpiScale:app.dpiScale,twipsToPixels:app.twipsToPixels,frame:app.activeDocument?.activeLayout?.frameSize,fileSize:app.activeDocument?.fileSize,firstPart:app.impress?.partList?.[0],pageRects:app.file?.writer?.pageRectangleList?.slice(0,2)})")))
            }
            record.put("completed",completed);File(evidence,name+".json").writeText(record.toString(2))
            if(!completed){
                runCatching {device.dumpWindowHierarchy(File(evidence,name+"-failure.xml"));device.takeScreenshot(File(evidence,name+"-failure.png"))}
                // Preserve documents and metadata; stop only the isolated failed engine.
                runCatching {device.executeShellCommand("pidof "+context.packageName+":"+kind).trim().split(Regex("\\s+")).mapNotNull {it.toIntOrNull()}.forEach {android.os.Process.killProcess(it)}}
            }
        }
    }
}
