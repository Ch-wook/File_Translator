package kr.documentstudio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kr.documentstudio.DocumentActions
import kr.documentstudio.Settings
import kr.documentstudio.core.*
import kr.documentstudio.data.Session

/** The host owns files and ink; original document editing belongs exclusively to its format engine. */
@Composable
fun VisualDocumentWorkspace(s: Session,settings: Settings,vm: DocumentActions,busy: Boolean,presentation: PresentationState=PresentationState.READING,open: ()->Unit) {
    var ink by rememberSaveable(s.id) {mutableStateOf(false)}
    val family=WorkspaceFamily.of(s.format)
    val available=EditorPolicy.available(s.format,android.os.Build.VERSION.SDK_INT)
    Column(Modifier.fillMaxSize().testTag("visual-document-workspace")) {
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
            TextButton({ink=false;vm.readDocument()},enabled=!busy) {Icon(family.icon,null);Text("문서")}
            TextButton({vm.beginInk();ink=true},enabled=!busy) {Icon(Icons.Default.Draw,null);Text("그리기")}
            Spacer(Modifier.weight(1f))
            FilledTonalButton(open,enabled=!busy && available) {Text("원본 문서 보기")}
        }
        if(ink && presentation.allowsMutation) Box(Modifier.weight(1f)) {DrawingPage(s,s.sections.firstOrNull(),0,settings,vm,busy)}
        else Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {
            Column(Modifier.widthIn(max=480.dp).padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Icon(family.icon,null,Modifier.size(56.dp),tint=family.accent)
                Text(s.name,style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(top=16.dp))
                Text(if(s.readOnlyReason!=null) s.readOnlyReason else if(available) "원본 페이지·셀·슬라이드로 엽니다. 문서 화면의 ‘편집하기’로 수정할 수 있습니다." else "이 형식의 원본 문서 편집은 Android 11 이상이 필요합니다.",modifier=Modifier.padding(vertical=12.dp))
                Button(open,enabled=!busy && available,modifier=Modifier.testTag("resume-visual-editor")) {Text("원본 보기")}
                if(s.recoveryWarning!=null) Text(s.recoveryWarning,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(top=12.dp))
            }
        }
        WorkspaceStatus(if(ink) "문서와 별도로 보존하는 필기" else "${s.format.label} · 원본 형식 편집",if(s.dirty) "수정됨" else "저장됨")
    }
}
