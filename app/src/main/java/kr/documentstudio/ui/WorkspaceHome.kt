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
    var filter by rememberSaveable {mutableStateOf<String?>(null)}
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns=if(maxWidth>=600.dp) 4 else 3
        LazyColumn(Modifier.fillMaxSize().widthIn(max=1200.dp).align(Alignment.TopCenter),contentPadding=PaddingValues(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item {
                Text("무엇을 만들까요?",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
                Text("파일에 맞는 편집 도구로 바로 시작하세요.",style=MaterialTheme.typography.bodyMedium,color=Color(0xFF647084),modifier=Modifier.padding(top=6.dp))
            }
            item {Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Button(open,enabled=!state.busy,modifier=Modifier.weight(1f).heightIn(min=52.dp),shape=RoundedCornerShape(12.dp)) {Icon(Icons.Default.FolderOpen,null);Spacer(Modifier.width(8.dp));Text("파일 열기")}
                OutlinedButton(new,enabled=!state.busy,modifier=Modifier.weight(1f).heightIn(min=52.dp),shape=RoundedCornerShape(12.dp)) {Icon(Icons.Default.Add,null);Spacer(Modifier.width(8.dp));Text("새 문서")}
            }}
            items(newFormats.chunked(columns)) {formats -> Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                formats.forEach {format ->
                    val family=WorkspaceFamily.of(format)
                    OutlinedCard(onClick={create(format)},enabled=!state.busy,modifier=Modifier.weight(1f).testTag("new-"+format.extension),shape=RoundedCornerShape(14.dp),colors=CardDefaults.outlinedCardColors(containerColor=Color.White),border=BorderStroke(1.dp,Color(0xFFE3E7EF))) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            FormatBadge(format,Modifier.size(36.dp))
                            Text(format.label,style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=10.dp),maxLines=1,overflow=TextOverflow.Ellipsis)
                            Text(".${format.extension}",style=MaterialTheme.typography.bodySmall,color=family.accent,modifier=Modifier.padding(top=3.dp))
                        }
                    }
                }
                repeat(columns-formats.size) {Spacer(Modifier.weight(1f))}
            }}
            item {TextButton(folder,enabled=!state.busy) {Icon(Icons.Default.DriveFolderUpload,null);Spacer(Modifier.width(8.dp));Text("폴더 탐색·파일 검색")}}
            item {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("최근 작업 · 복구",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                    FilterChip(favorites,{favorites=!favorites},label={Text("즐겨찾기")},leadingIcon={Icon(Icons.Default.Star,null,Modifier.size(16.dp))})
                }
                OutlinedTextField(query,{query=it},singleLine=true,placeholder={Text("최근 파일 이름 검색")},leadingIcon={Icon(Icons.Default.Search,null)},modifier=Modifier.fillMaxWidth().padding(top=10.dp),shape=RoundedCornerShape(12.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    FilterChip(filter==null,{filter=null},label={Text("전체")})
                    WorkspaceFamily.entries.forEach {f->FilterChip(filter==f.name,{filter=if(filter==f.name) null else f.name},label={Text(f.title)})}
                }
            }
            val rows=state.recents.filter {r -> (!favorites || r.favorite) && r.name.contains(query,true) && (filter==null || Format.entries.firstOrNull {it.extension.equals(r.name.substringAfterLast('.'),true)}?.let {WorkspaceFamily.of(it).name==filter}==true)}
            if(rows.isEmpty()) item {Column(Modifier.fillMaxWidth().padding(vertical=28.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Icon(Icons.Default.FolderOpen,null,tint=Color(0xFF9CA8BA),modifier=Modifier.size(36.dp))
                Text(if(query.isNotBlank() || filter!=null || favorites) "조건에 맞는 문서가 없습니다." else "아직 최근 문서가 없습니다.",color=Color(0xFF647084),modifier=Modifier.padding(top=10.dp))
            }}
            items(rows,key={it.id}) {r ->
                val format=Format.entries.firstOrNull {it.extension.equals(r.name.substringAfterLast('.'),true)} ?: Format.TXT
                Surface(onClick={if(!state.busy) vm.restore(r.id,visual=true)},shape=RoundedCornerShape(12.dp),color=Color.White,enabled=!state.busy) {
                    Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        FormatBadge(format)
                        Column(Modifier.weight(1f)) {
                            Text(r.name,maxLines=1,overflow=TextOverflow.Ellipsis,fontWeight=FontWeight.Medium)
                            Text((if(r.dirty) "수정 내용 복구 가능 · " else "")+DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(r.modified)),style=MaterialTheme.typography.bodySmall,color=Color(0xFF6D7786),modifier=Modifier.padding(top=3.dp))
                        }
                        IconButton({vm.favorite(r.id)},enabled=!state.busy) {Icon(if(r.favorite) Icons.Default.Star else Icons.Default.StarBorder,"즐겨찾기",tint=if(r.favorite) Color(0xFFD59628) else Color(0xFF8490A2))}
                    }
                }
            }
            item {WorkspaceStatus("기기 안에 작업 복구본 자동 저장","ZIP 백업은 파일 열기로 가져오기")}
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
                    Text(if(f==Format.DOC) "${f.label} · 기존 파일 편집" else "${f.label}  .${f.extension}",color=Color(0xFF263143))
                    Text(when(f) {Format.DOCX->"페이지와 문단";Format.XLSX->"셀과 수식";Format.PPTX->"슬라이드와 발표";Format.HWPX->"한글 문서";Format.PDF->"페이지와 주석";Format.MD->"작성과 미리보기";Format.TXT->"간결한 텍스트 편집";else->"이전 형식"},style=MaterialTheme.typography.labelSmall,color=Color(0xFF748094))
                }
                Icon(Icons.Default.ChevronRight,null,tint=Color(0xFF8490A2))
            }
        }
    }},confirmButton={TextButton(close) {Text("닫기")}})
}
