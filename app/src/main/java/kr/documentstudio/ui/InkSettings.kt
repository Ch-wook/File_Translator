package kr.documentstudio.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kr.documentstudio.*
import kr.documentstudio.core.*

@Composable
fun InkSettingsDialog(settings: Settings,vm: DocumentActions,onClose: ()->Unit) {
    var hex by remember {mutableStateOf(String.format("%06X",settings.penColor and 0xffffff))}
    var name by remember {mutableStateOf("")}
    AlertDialog(onDismissRequest=onClose,title={Text("펜 설정 · 다음에도 유지")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        OutlinedTextField(hex,{hex=it.take(6).uppercase()},label={Text("색상 RRGGBB")},singleLine=true)
        TextButton({if(hex.matches(Regex("[0-9A-F]{6}"))) vm.settings(settings.copy(penColor=android.graphics.Color.parseColor("#$hex"))) else vm.message("6자리 색상 값을 입력하세요.")}) {Text("색 적용")}
        Text("투명도 ${(settings.penOpacity*100).toInt()}%");Slider(settings.penOpacity,{vm.settings(settings.copy(penOpacity=it))},valueRange=0.1f..1f)
        Text("필압 곡선 ${String.format("%.2f",settings.pressureCurve)} · 낮을수록 약한 압력에도 굵게")
        Slider(settings.pressureCurve,{vm.settings(settings.copy(pressureCurve=it))},valueRange=0.25f..3f)
        Text("획 부드럽게 ${(settings.smoothing*100).toInt()}%")
        Slider(settings.smoothing,{vm.settings(settings.copy(smoothing=it))},valueRange=0f..1f)
        Row {Checkbox(settings.autoSnap,{vm.settings(settings.copy(autoSnap=it))});Text("펜을 떼면 직선·사각형·원 보정",Modifier.padding(top=12.dp))}
        TextButton({val colors=settings.favoriteColors;vm.settings(settings.copy(favoriteColors=if(settings.penColor in colors) colors-settings.penColor else (colors+settings.penColor).takeLast(16)))}) {Text(if(settings.penColor in settings.favoriteColors) "현재 색 즐겨찾기 해제" else "현재 색 즐겨찾기")}
        HorizontalDivider();Text("즐겨찾는 펜 ${settings.presets.size}/12")
        OutlinedTextField(name,{name=it.take(40)},label={Text("펜 이름")},singleLine=true)
        TextButton({val n=name.trim();if(n.isNotEmpty()) {vm.settings(settings.copy(presets=(settings.presets.filterNot {it.name==n}+PenPreset.from(n,settings)).takeLast(12)));name=""}},enabled=name.isNotBlank()) {Text("현재 설정 저장")}
        settings.presets.forEach {p->Row(Modifier.fillMaxWidth()) {TextButton({vm.settings(p.apply(settings))},Modifier.weight(1f)) {Text(p.name)};TextButton({vm.settings(settings.copy(presets=settings.presets-p))}) {Text("삭제")}}}
    }},confirmButton={TextButton(onClose) {Text("닫기")}})
}

@Composable
fun InkLayersDialog(edits: Edits,active: String,vm: DocumentActions,onSelect: (String)->Unit,onClose: ()->Unit) {
    var name by remember {mutableStateOf("")}
    AlertDialog(onDismissRequest=onClose,title={Text("필기 레이어 ${edits.layers.size}/8")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("위에서 아래 순서로 그립니다. 숨김 레이어는 내보내기에 표시되지 않습니다.")
        edits.layers.forEachIndexed {i,l->
            FilterChip(l.id==active,{onSelect(l.id)},label={Text(l.name)})
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton({vm.inkLayers(edits.layers.map {if(it.id==l.id) it.copy(visible=!it.visible) else it})}) {Text(if(l.visible) "숨기기" else "표시")}
                TextButton({vm.inkLayers(edits.layers.map {if(it.id==l.id) it.copy(locked=!it.locked) else it})}) {Text(if(l.locked) "잠금 해제" else "잠금")}
                TextButton({vm.inkLayers(edits.layers.toMutableList().apply {add(i-1,removeAt(i))})},enabled=i>0) {Text("앞 순서")}
                TextButton({vm.inkLayers(edits.layers-l)},enabled=edits.layers.size>1 && edits.strokes.none {it.layerId==l.id}) {Text("삭제")}
                TextButton({if(name.isNotBlank()) vm.inkLayers(edits.layers.map {if(it.id==l.id) it.copy(name=name.trim()) else it})},enabled=name.isNotBlank()) {Text("이름 적용")}
            }
        }
        OutlinedTextField(name,{name=it.take(60)},label={Text("레이어 이름")},singleLine=true)
        TextButton({val l=InkLayer(java.util.UUID.randomUUID().toString(),name.trim());vm.inkLayers(edits.layers+l);onSelect(l.id);name=""},enabled=edits.layers.size<8 && name.isNotBlank()) {Text("새 레이어")}
    }},confirmButton={TextButton(onClose) {Text("닫기")}})
}
