package kr.documentstudio

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import kr.documentstudio.core.Format
import kr.documentstudio.data.*
import java.io.File

class ConversionModel(app: Application): AndroidViewModel(app) {
    val repo=DocumentRepository(app)
    var source by mutableStateOf<Session?>(null)
    var target by mutableStateOf("")
    var status by mutableStateOf("기기 안에서 처리합니다. 원본은 변경하지 않습니다.")
    var busy by mutableStateOf(false)
    var result by mutableStateOf<File?>(null)
    var savedUri by mutableStateOf<Uri?>(null)
    var dpi by mutableStateOf(144)
    var pageSpec by mutableStateOf("")
    var size by mutableStateOf("12")
    var encoding by mutableStateOf("UTF-8")
    private var job: Job?=null
    fun action(block: suspend ()->Unit) {
        if(busy)return;busy=true
        job=viewModelScope.launch {try{block()}catch(e:CancellationException){status="변환을 취소했습니다."}catch(e:Exception){status=e.message ?: "변환 실패"}finally{busy=false}}
    }
    fun load(uri: Uri?=null,id: String?=null)=action {
        status="파일 확인 중";result?.delete();result=null;savedUri=null
        source=if(id!=null)repo.restore(id) else repo.open(requireNotNull(uri),encoding)
        target=PortableConversions.targets(source!!.format).firstOrNull() ?: ""
        status="문서 확인 완료 · "+source!!.format.extension.uppercase()
    }
    fun cancel(){job?.cancel()}
    fun convert()=action {
        val s=requireNotNull(source);result?.delete();result=null;savedUri=null
        val coroutine=coroutineContext
        result=withContext(Dispatchers.IO){PortableConversions.convert(getApplication(),s,target,dpi,pageSpec,size.toFloatOrNull() ?: error("글자 크기를 확인하세요."),{coroutine.ensureActive()},{text->status=text})}
        status="변환 완료 · ${result!!.length()} bytes · 저장 전"
    }
    fun save(uri: Uri)=action {
        val file=requireNotNull(result)
        withContext(Dispatchers.IO){ConversionFiles.publish(getApplication(),file,uri,source?.uri)}
        savedUri=uri;status="저장 완료 · 결과 파일을 다시 읽어 확인했습니다."
    }
}

class ConversionActivity: ComponentActivity() {
    private val model: ConversionModel by viewModels()
    private val choose=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)model.load(uri)}
    private val save=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){r->if(r.resultCode==RESULT_OK)r.data?.data?.let {model.save(it)}}
    private val engine=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){r->
        model.action {
            model.source?.let {model.source=model.repo.acceptOffice(it)}
            model.savedUri=r.data?.getStringExtra("conversionUri")?.let(Uri::parse)
            model.status=if(model.savedUri!=null)"PDF 변환·저장 완료" else "문서 엔진에서 PDF 변환을 선택해 결과를 저장하세요. 취소한 경우 결과 파일은 생성하지 않습니다."
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if(state==null)intent.getStringExtra("sessionId")?.let {model.load(id=it)}
        setContent {MaterialTheme {Surface {Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Row {TextButton({finish()}){Text("뒤로")};Text("파일 변환",style=MaterialTheme.typography.headlineSmall)}
            Text("원본을 보존하고 이 기기에서 변환합니다. 외부 서버에 전송하지 않습니다.")
            Button({choose.launch(arrayOf("*/*"))},enabled=!model.busy){Text("변환할 파일 선택")}
            Text(model.source?.name ?: "파일을 선택하세요.")
            if(model.source==null){Text("텍스트 인코딩");Row {for(value in listOf("UTF-8","MS949"))TextButton({model.encoding=value}){Text((if(model.encoding==value)"✓ " else "")+value)}}}
            model.source?.let {s->
                Text("결과 형식")
                Row(Modifier.fillMaxWidth()){for(t in PortableConversions.targets(s.format))FilterChip(model.target==t,{model.target=t},label={Text(t.uppercase())},enabled=!model.busy)}
                if(s.format==Format.PDF && model.target.endsWith("zip")){
                    OutlinedTextField(model.pageSpec,{model.pageSpec=it},label={Text("페이지 · 비우면 전체, 예: 1,3-5")},enabled=!model.busy)
                    Row{for(value in listOf(72,144,216))TextButton({model.dpi=value},enabled=!model.busy){Text((if(model.dpi==value)"✓ " else "")+value+" dpi")}}
                    Text("여러 페이지를 번호 순서의 이미지 ZIP으로 저장합니다.")
                }
                if(s.format in listOf(Format.TXT,Format.MD) && model.target=="pdf")OutlinedTextField(model.size,{model.size=it},label={Text("기본 크기 pt · 8~32")},enabled=!model.busy)
                if(s.format==Format.MD)Text("PDF는 기본 제목·강조·목록·코드 서식을 지원합니다. 원격 이미지는 내려받지 않으며 복잡한 표·HTML 배치는 보장하지 않습니다. HTML 변환은 표 구조를 유지합니다.")
                if(s.format in listOf(Format.HWP,Format.HWPX))Text("한글 PDF는 전체 페이지 이미지 방식입니다. 텍스트 선택·검색·링크는 유지되지 않습니다.")
                if(s.format==Format.XLSX)Text("현재 문서에 저장된 페이지·시트 출력 설정을 사용합니다. 넓은 시트의 맞춤 설정은 문서 엔진에서 확인하세요.")
                Button({startConversion()},enabled=!model.busy && model.target.isNotEmpty()){Text("변환 시작 · "+model.target.uppercase())}
            }
            if(model.busy){LinearProgressIndicator(Modifier.fillMaxWidth());TextButton({model.cancel()}){Text("취소")}}
            Text(model.status)
            val result=model.result
            if(result!=null || model.savedUri!=null){
                TextButton({openResult()}){Text("결과 열기")}
                if(result!=null)Button({val mime=mime(model.target);save.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime).putExtra(Intent.EXTRA_TITLE,resultName()))}){Text("다른 이름으로 저장")}
                TextButton({val uri=model.savedUri ?: resultUri();startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime(model.target)).putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"결과 공유"))}){Text("공유")}
            }
        }}}}
    }
    private fun mime(target: String)=when(target){"pdf"->"application/pdf";"txt","md"->"text/plain";"html"->"text/html";"hwpx"->"application/hwp+zip";else->"application/zip"}
    private fun resultName()=(model.source?.name?.substringBeforeLast('.') ?: "변환결과")+"."+model.target
    private fun resultUri(): Uri=FileProvider.getUriForFile(this,packageName+".conversionfiles",requireNotNull(model.result))
    private fun openResult(){val uri=model.savedUri ?: resultUri();val view=Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime(model.target)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if(model.target in listOf("pdf","txt","md","hwpx"))view.setClass(this,MainActivity::class.java)
        runCatching {startActivity(view)}.onFailure {model.status="이 결과를 열 수 있는 앱이 없습니다. 저장 또는 공유를 선택하세요."}
    }
    private fun startConversion(){
        val s=model.source ?: return
        if(model.target=="hwpx"){model.action {val converted=model.repo.convertHangul(s);val dir=File(cacheDir,"conversions").apply {mkdirs()};model.result=File(dir,java.util.UUID.randomUUID().toString()+".hwpx").also {it.writeBytes(converted.base.readBytes())};model.status="HWPX 변환 완료 · 보존 검사 통과"};return}
        if(s.format in DocumentRepository.visualFormats && model.target=="pdf"){
            model.action {require(s.readOnlyReason==null) {s.readOnlyReason ?: "보호된 문서는 변환할 수 없습니다."};require(s.format in listOf(Format.HWP,Format.HWPX) || android.os.Build.VERSION.SDK_INT>=30) {"Office PDF 변환은 Android 11 이상에서 지원합니다."};model.status="문서 엔진 준비 중";val file=model.repo.prepareOffice(s);engine.launch(Intent(this,if(s.format in listOf(Format.HWP,Format.HWPX))HangulEditorActivity::class.java else org.libreoffice.androidlib.StudioOfficeActivity::class.java).putExtra("sessionId",s.id).putExtra("workingPath",file.absolutePath).putExtra("displayName",s.name).putExtra("originalUri",s.uri).putExtra("conversionOnly",true).putExtra("openIntent","VIEW"))}
        }else model.convert()
    }
}
