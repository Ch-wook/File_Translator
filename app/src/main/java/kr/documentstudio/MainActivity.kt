package kr.documentstudio

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import kr.documentstudio.ui.StudioApp

class MainActivity: ComponentActivity() {
    private val vm: EditorViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { StudioApp(vm) }
        // Intercept shortcuts before the focused EditText handles its own undo.
        // Delegate through the public Window callback, preserving AndroidX dispatch.
        // Recreation can preserve a Window carrying the previous Activity's
        // callback. Always delegate to this Activity and its current view tree.
        val callback: android.view.Window.Callback=this
        window.callback=object: android.view.Window.Callback by callback {
            override fun dispatchKeyShortcutEvent(event: android.view.KeyEvent): Boolean =
                handleShortcut(event) || callback.dispatchKeyShortcutEvent(event)
        }
        if(savedInstanceState==null) handle(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handle(intent) }
    private fun handle(intent: Intent) {
        val uri: Uri? = when(intent.action) {
            Intent.ACTION_VIEW,Intent.ACTION_EDIT -> intent.data
            Intent.ACTION_SEND -> if(android.os.Build.VERSION.SDK_INT>=33) intent.getParcelableExtra(Intent.EXTRA_STREAM,Uri::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if(uri!=null && uri.scheme=="content") {
            val read=intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0
            val write=intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION!=0
            runCatching {when {
                read && write->contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                read->contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
                write->contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }}
            vm.openExternal(uri,if(intent.action==Intent.ACTION_EDIT) kr.documentstudio.core.OpenIntent.EDIT else kr.documentstudio.core.OpenIntent.VIEW)
        }
    }
    override fun onStop() { vm.flush(); super.onStop() }
    private fun handleShortcut(event: android.view.KeyEvent): Boolean {
        if(vm.state.value.session==null || event.action!=android.view.KeyEvent.ACTION_DOWN || !event.isCtrlPressed || event.isAltPressed) return false
        when(event.keyCode) {
            android.view.KeyEvent.KEYCODE_Z -> {if(event.isShiftPressed) vm.redo() else vm.undo();return true}
            android.view.KeyEvent.KEYCODE_Y -> {vm.redo();return true}
            android.view.KeyEvent.KEYCODE_S -> {if(!vm.state.value.busy) vm.state.value.session?.let {s -> if(s.pdf?.archival==true) vm.message("PDF/A는 저장 버튼으로 일반 PDF 복사본을 저장하세요.") else if(s.uri!=null) vm.save(Uri.parse(s.uri)) else vm.message("처음 저장할 때는 저장 버튼으로 위치를 선택하세요.")};return true}
        }
        return false
    }
}
