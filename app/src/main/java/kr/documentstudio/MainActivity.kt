package kr.documentstudio

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kr.documentstudio.ui.StudioApp

class MainActivity: ComponentActivity() {
    private val vm: EditorViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        enableEdgeToEdge()
        setContent { StudioApp(vm) }
        if(savedInstanceState==null) handle(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handle(intent) }
    private fun handle(intent: Intent) {
        val uri: Uri? = when(intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if(android.os.Build.VERSION.SDK_INT>=33) intent.getParcelableExtra(Intent.EXTRA_STREAM,Uri::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if(uri!=null && uri.scheme=="content") vm.open(uri)
    }
    override fun onStop() { vm.flush(); super.onStop() }
    override fun onKeyDown(keyCode: Int,event: android.view.KeyEvent): Boolean {
        if(event.isCtrlPressed) when(keyCode) {
            android.view.KeyEvent.KEYCODE_Z -> {if(event.isShiftPressed) vm.redo() else vm.undo();return true}
            android.view.KeyEvent.KEYCODE_Y -> {vm.redo();return true}
            android.view.KeyEvent.KEYCODE_S -> {vm.state.value.session?.let {s -> if(s.uri!=null) vm.save(Uri.parse(s.uri)) else vm.message("처음 저장할 때는 저장 버튼으로 위치를 선택하세요.")};return true}
        }
        return super.onKeyDown(keyCode,event)
    }
}
