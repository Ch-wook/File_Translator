package kr.documentstudio.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kr.documentstudio.core.Format

enum class WorkspaceFamily(val title: String,val accent: Color,val icon: ImageVector,val tabs: List<String>) {
    WORD("Word",Color(0xFF2858A5),Icons.Default.Description,listOf("홈","삽입","레이아웃","보기","그리기")),
    SHEET("Excel",Color(0xFF217346),Icons.Default.TableChart,listOf("홈","삽입","수식","데이터","보기","그리기")),
    SLIDE("PowerPoint",Color(0xFFC45332),Icons.Default.Slideshow,listOf("홈","삽입","슬라이드","보기","그리기")),
    HANGUL("한글",Color(0xFF167DA5),Icons.AutoMirrored.Filled.Article,listOf("홈","삽입","레이아웃","보기","그리기")),
    PDF("PDF",Color(0xFFB8343D),Icons.Default.PictureAsPdf,listOf("보기","주석","편집","페이지","그리기")),
    TEXT("텍스트",Color(0xFF6953A1),Icons.Default.Code,listOf("홈","보기","그리기"));
    companion object {
        fun of(format: Format)=when(format) {
            Format.DOCX,Format.DOC->WORD;Format.XLSX,Format.XLS->SHEET;Format.PPTX,Format.PPT->SLIDE
            Format.HWPX,Format.HWP->HANGUL;Format.PDF->PDF;else->TEXT
        }
    }
}

@Composable
fun readableAccent(color: Color)=if(MaterialTheme.colorScheme.surface.luminance()<.3f)lerp(color,Color.White,.45f) else color

@Composable
fun FormatBadge(format: Format,modifier: Modifier=Modifier) {
    val family=WorkspaceFamily.of(format)
    val accent=readableAccent(family.accent)
    Surface(modifier.size(44.dp),shape=RoundedCornerShape(12.dp),color=accent.copy(alpha=0.10f)) {
        Box(contentAlignment=Alignment.Center) {Icon(family.icon,null,tint=accent,modifier=Modifier.size(25.dp))}
    }
}

@Composable
fun WorkspaceRibbon(family: WorkspaceFamily,tab: String,expanded: Boolean,busy: Boolean,onTab: (String)->Unit,onExpand: ()->Unit,content: @Composable ()->Unit) {
    Surface(color=MaterialTheme.colorScheme.surface,tonalElevation=1.dp,modifier=Modifier.testTag("workspace-ribbon")) {
        Column {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(start=6.dp)) {
                    family.tabs.forEach {name ->
                        val selected=name==tab
                        Column(Modifier.width(IntrinsicSize.Max).selectableTab(selected,!busy) {onTab(name)}.testTag("ribbon-$name"),horizontalAlignment=Alignment.CenterHorizontally) {
                            Text(name,Modifier.padding(horizontal=16.dp,vertical=13.dp),maxLines=1,softWrap=false,color=if(selected) family.accent else Color(0xFF5D6470),fontWeight=if(selected) FontWeight.Bold else FontWeight.Normal,style=MaterialTheme.typography.labelLarge)
                            Box(Modifier.height(3.dp).fillMaxWidth().background(if(selected) family.accent else Color.Transparent))
                        }
                    }
                }
                IconButton(onExpand,modifier=Modifier.size(48.dp)) {Icon(if(expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,if(expanded) "도구 접기" else "도구 펼치기")}
            }
            if(expanded) Column(Modifier.fillMaxWidth().heightIn(max=152.dp).verticalScroll(rememberScrollState())) {content()}
            HorizontalDivider(color=Color(0xFFE2E6EC))
        }
    }
}

private fun Modifier.selectableTab(selected: Boolean,enabled: Boolean,click: ()->Unit): Modifier =
    this.widthIn(min=64.dp).then(Modifier.selectable(selected=selected,enabled=enabled,role=androidx.compose.ui.semantics.Role.Tab,onClick=click))

@Composable
fun WorkspaceStatus(left: String,right: String="") {
    Surface(color=MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=7.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(left,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.weight(1f),maxLines=2)
            if(right.isNotEmpty()) Text(right,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)
        }
    }
}
