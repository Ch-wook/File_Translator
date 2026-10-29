package kr.documentstudio.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kr.documentstudio.*
import kr.documentstudio.core.Format
import java.text.DateFormat
import java.util.Date

private val newFormats=listOf(Format.DOCX,Format.XLSX,Format.PPTX,Format.HWPX,Format.PDF,Format.MD,Format.TXT)

@Composable
fun WorkspaceHome(state: UiState,vm: EditorViewModel,new: ()->Unit,open: ()->Unit,folder: ()->Unit,create: (Format)->Unit) {
    var query by rememberSaveable {mutableStateOf("")}
    var favorites by rememberSaveable {mutableStateOf(false)}
    val context=androidx.compose.ui.platform.LocalContext.current
    val fontScale=androidx.compose.ui.platform.LocalDensity.current.fontScale
    var help by rememberSaveable {mutableStateOf(!context.getSharedPreferences("onboarding",0).getBoolean("home-help-dismissed",false))}
    var hidden by rememberSaveable {mutableStateOf(false)}
    var sort by rememberSaveable {mutableStateOf("최근 수정")}
    var filter by rememberSaveable {mutableStateOf<String?>(null)}
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns=if(maxWidth>=700.dp && fontScale<1.4f) 4 else if(maxWidth>=420.dp && fontScale<1.3f) 3 else 2
        val dates=remember {DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT)}
        val rows=remember(state.recents,favorites,query,filter,hidden,sort) {state.recents.filter {r -> (if(hidden)r.hidden else !r.hidden) && (!favorites || r.favorite) && r.name.contains(query,true) && (filter==null || Format.entries.firstOrNull {it.extension.equals(r.name.substringAfterLast('.'),true)}?.let {WorkspaceFamily.of(it).name==filter}==true)}.let {list->when(sort){"이름"->list.sortedBy {it.name.lowercase()};"형식"->list.sortedWith(compareBy({it.name.substringAfterLast('.').lowercase()},{it.name.lowercase()}));else->list.sortedByDescending {it.modified}}}}
        LazyColumn(Modifier.fillMaxSize().widthIn(max=1200.dp).align(Alignment.TopCenter),contentPadding=PaddingValues(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item {
                Text("무엇을 만들까요?",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
                Text("파일에 맞는 편집 도구로 바로 시작하세요.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=6.dp))
            }
            if(help)item {ElevatedCard {Column(Modifier.padding(14.dp)) {
                Text("파일 열기 → 읽기 → 편집 → 저장",fontWeight=FontWeight.SemiBold)
                Text("파일 변환은 홈에서, 자필 서명은 한글 편집기의 삽입 메뉴에서 시작하세요. 자동 복구본은 원본 저장과 별개입니다.",style=MaterialTheme.typography.bodySmall)
                TextButton({help=false;context.getSharedPreferences("onboarding",0).edit().putBoolean("home-help-dismissed",true).apply()}) {Text("안내 닫기")}
            }}}
            item {Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Button(open,enabled=!state.busy,modifier=Modifier.weight(1f).heightIn(min=52.dp),shape=RoundedCornerShape(12.dp)) {Icon(Icons.Default.FolderOpen,null);Spacer(Modifier.width(8.dp));Text("파일 열기")}
                OutlinedButton(new,enabled=!state.busy,modifier=Modifier.weight(1f).heightIn(min=52.dp),shape=RoundedCornerShape(12.dp)) {Icon(Icons.Default.Add,null);Spacer(Modifier.width(8.dp));Text("새 문서")}
            }}
            items(newFormats.chunked(columns)) {formats -> Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                formats.forEach {format ->
                    val family=WorkspaceFamily.of(format)
                    OutlinedCard(onClick={create(format)},enabled=!state.busy,modifier=Modifier.weight(1f).testTag("new-"+format.extension),shape=RoundedCornerShape(14.dp),colors=CardDefaults.outlinedCardColors(containerColor=MaterialTheme.colorScheme.surface),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            FormatBadge(format,Modifier.size(36.dp))
                            Text(format.label,style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=10.dp),maxLines=2,overflow=TextOverflow.Ellipsis)
                            Text(".${format.extension}",style=MaterialTheme.typography.bodySmall,color=family.accent,modifier=Modifier.padding(top=3.dp))
                        }
                    }
                }
                repeat(columns-formats.size) {Spacer(Modifier.weight(1f))}
            }}
            item {val context=androidx.compose.ui.platform.LocalContext.current
                OutlinedButton({context.startActivity(android.content.Intent(context,ConversionActivity::class.java))},enabled=!state.busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Icon(Icons.Default.Transform,null);Spacer(Modifier.width(8.dp));Text("파일 변환")}}
            item {TextButton(folder,enabled=!state.busy) {Icon(Icons.Default.DriveFolderUpload,null);Spacer(Modifier.width(8.dp));Text("폴더 탐색·파일 검색")}}
            item {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("최근 작업 · 복구",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                    FilterChip(favorites,{favorites=!favorites},label={Text("즐겨찾기")},leadingIcon={Icon(Icons.Default.Star,null,Modifier.size(16.dp))})
                }
                OutlinedTextField(query,{query=it},singleLine=true,placeholder={Text("최근 파일 이름 검색")},leadingIcon={Icon(Icons.Default.Search,null)},modifier=Modifier.fillMaxWidth().padding(top=10.dp),shape=RoundedCornerShape(12.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    FilterChip(hidden,{hidden=!hidden},label={Text("숨긴 목록")})
                    var sorting by remember {mutableStateOf(false)}
                    Box {TextButton({sorting=true}) {Text("정렬 · "+sort)}
                        DropdownMenu(sorting,{sorting=false}) {listOf("최근 수정","이름","형식").forEach {value->DropdownMenuItem(text={Text(value)},onClick={sort=value;sorting=false})}}
                    }
                    FilterChip(filter==null,{filter=null},label={Text("전체")})
                    WorkspaceFamily.entries.forEach {f->FilterChip(filter==f.name,{filter=if(filter==f.name) null else f.name},label={Text(f.title)})}
                }
            }
            if(rows.isEmpty()) item {Column(Modifier.fillMaxWidth().padding(vertical=28.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Icon(Icons.Default.FolderOpen,null,tint=Color(0xFF9CA8BA),modifier=Modifier.size(36.dp))
                Text(if(query.isNotBlank() || filter!=null || favorites) "조건에 맞는 문서가 없습니다." else "아직 최근 문서가 없습니다.",color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=10.dp))
            }}
            items(rows,key={it.id}) {r ->
                val format=Format.entries.firstOrNull {it.extension.equals(r.name.substringAfterLast('.'),true)} ?: Format.TXT
                Surface(onClick={if(!state.busy) vm.reopenRecent(r.id,visual=true)},shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surface,enabled=!state.busy) {
                    Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        FormatBadge(format)
                        Column(Modifier.weight(1f)) {
                            Text(r.name,maxLines=1,overflow=TextOverflow.Ellipsis,fontWeight=FontWeight.Medium)
                            Text((if(r.dirty) "수정 내용 복구 가능 · " else "")+remember(r.modified){dates.format(Date(r.modified))},style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=3.dp))
                        }
                        IconButton({vm.favorite(r.id)},enabled=!state.busy) {Icon(if(r.favorite) Icons.Default.Star else Icons.Default.StarBorder,(if(r.favorite) "즐겨찾기 해제: " else "즐겨찾기 추가: ")+r.name,tint=if(r.favorite) Color(0xFFD59628) else Color(0xFF8490A2))}
                        var recentMenu by remember {mutableStateOf(false)}
                        Box {
                            IconButton({recentMenu=true},enabled=!state.busy) {Icon(Icons.Default.MoreVert,"최근 문서 메뉴: "+r.name)}
                            DropdownMenu(recentMenu,{recentMenu=false}) {
                                DropdownMenuItem(text={Text(if(r.hidden)"목록에 다시 표시" else "최근 목록에서 숨기기")},onClick={recentMenu=false;vm.hideRecent(r.id,!r.hidden)})
                                DropdownMenuItem(text={Text("원본과 복구 자료는 삭제하지 않습니다")},enabled=false,onClick={})
                            }
                        }
                    }
                }
            }
            item {WorkspaceStatus("자동 복구본은 원본 파일 저장과 별개입니다")}
        }
    }
}

@Composable
fun NewDocumentDialog(busy: Boolean,create: (Format)->Unit,close: ()->Unit) {
    AlertDialog(onDismissRequest=close,title={Text("새 문서")},text={Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        (newFormats+listOf(Format.HWP,Format.XLS,Format.PPT,Format.DOC)).forEach {f ->
            TextButton({create(f)},enabled=f!=Format.DOC && !busy,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(10.dp)) {
                FormatBadge(f);Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if(f==Format.DOC) "${f.label} · 기존 파일 편집" else "${f.label}  .${f.extension}",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(when(f) {Format.DOCX->"페이지와 문단";Format.XLSX->"셀과 수식";Format.PPTX->"슬라이드와 발표";Format.HWPX->"한글 문서";Format.PDF->"페이지와 주석";Format.MD->"작성과 미리보기";Format.TXT->"간결한 텍스트 편집";else->"이전 형식"},style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Default.ChevronRight,null,tint=Color(0xFF8490A2))
            }
        }
    }},confirmButton={TextButton(close) {Text("닫기")}})
}
