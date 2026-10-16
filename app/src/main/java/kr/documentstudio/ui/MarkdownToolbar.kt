package kr.documentstudio.ui

import android.widget.EditText
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kr.documentstudio.core.MarkdownEditing

@Composable
fun MarkdownToolbar(editor: ()->EditText?,enabled: Boolean) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        listOf("heading" to "제목","bold" to "굵게","italic" to "기울임","list" to "목록","number" to "번호","task" to "체크리스트","table" to "표","code" to "인라인 코드","block" to "코드 블록","link" to "링크","quote" to "인용").forEach {(command,label)->
            TextButton({editor()?.let {view ->val edit=MarkdownEditing.apply(view.text.toString(),view.selectionStart,view.selectionEnd,command);view.text.replace(0,view.text.length,edit.text);view.setSelection(edit.first,edit.last);view.requestFocus()}},enabled=enabled) {Text(label)}
        }
    }
}
