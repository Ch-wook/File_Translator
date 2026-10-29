package kr.documentstudio

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kr.documentstudio.core.Format
import kr.documentstudio.data.PdfEngine
import kr.documentstudio.data.ConversionFiles
import kr.documentstudio.core.Edits
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.documentstudio.data.Session
import kr.documentstudio.ui.PdfContinuousReader
import kr.documentstudio.ui.WorkspaceFamily

/** Result viewing has no dependency on the original editor's busy/close transaction. */
class ConversionPreviewActivity: ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val uri=intent.data
        setContent {kr.documentstudio.ui.StudioTheme(WorkspaceFamily.of(Format.PDF).accent){
            var session by remember {mutableStateOf<Session?>(null)}
            var error by remember {mutableStateOf<String?>(null)}
            var page by rememberSaveable {mutableIntStateOf(0)}
            val preview=remember {File(cacheDir,"conversion-preview-"+java.util.UUID.randomUUID()+".pdf")}
            DisposableEffect(preview){onDispose{preview.delete()}}
            LaunchedEffect(uri){
                try {
                    require(uri?.scheme=="content") {"결과 파일을 선택하세요."}
                    session=withContext(Dispatchers.IO){
                        contentResolver.openInputStream(requireNotNull(uri))?.use {input->preview.outputStream().use {out->
                            val buffer=ByteArray(65536);var total=0L
                            while(true){val n=input.read(buffer);if(n<0)break;total+=n;require(total<=128L*1024*1024) {"결과 파일이 너무 큽니다."};out.write(buffer,0,n)}
                        }} ?: error("결과 파일을 읽지 못했습니다.")
                        val info=PdfEngine.inspect(preview)
                        val name=runCatching {contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use {cursor->if(cursor.moveToFirst())cursor.getString(0) else null}}.getOrNull() ?: "변환 결과.pdf"
                        // Preview the actual saved bytes, without adopting an older recovery session.
                        Session(java.util.UUID.randomUUID().toString(),name,Format.PDF,uri.toString(),ConversionFiles.digest(preview),preview,null,null,info,Edits(),Edits())
                    }
                }
                catch(e:kotlinx.coroutines.CancellationException){throw e}
                catch(e:Exception){error=kr.documentstudio.data.UserProblems.explain(e).let {it.title+" · "+it.advice}}
            }
            Surface {Column(Modifier.fillMaxSize().systemBarsPadding()){
                Row(Modifier.fillMaxWidth().heightIn(min=48.dp),horizontalArrangement=Arrangement.spacedBy(4.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
                    TextButton({finish()}){Text("닫기")}
                    Text(session?.name ?: "변환 결과",maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
                    session?.let {s->Text("${page+1}/${s.pdf!!.pages}",style=MaterialTheme.typography.labelMedium)}
                    TextButton({startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"PDF 공유"))},enabled=session!=null){Text("공유")}
                }
                val s=session
                if(s!=null)Box(Modifier.weight(1f)){PdfContinuousReader(s,page,0,false,onVisiblePage={page=it},onEditPage=null)}
                else if(error!=null)Text(error!!,modifier=Modifier.padding(16.dp))
                else Box(Modifier.fillMaxSize(),contentAlignment=androidx.compose.ui.Alignment.Center){CircularProgressIndicator()}
            }}
        }}
    }
}
