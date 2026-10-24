package kr.documentstudio

import android.app.Application
import androidx.core.content.FileProvider
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kr.documentstudio.core.Format
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class OfficeDefaultRoutingTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private fun main(block: ()->Unit)=instrumentation.runOnMainSync(block)
    private fun await(model: EditorViewModel) {
        val until=android.os.SystemClock.uptimeMillis()+25000
        while(model.state.value.busy && android.os.SystemClock.uptimeMillis()<until) android.os.SystemClock.sleep(20)
        assertFalse("Document operation timed out",model.state.value.busy)
    }
    private fun scenario(block: (EditorViewModel)->Unit) {
        lateinit var model: EditorViewModel
        main {model=EditorViewModel(ApplicationProvider.getApplicationContext<Application>());model.settings(Settings(visualOffice=true))}
        try {block(model)} finally {main {model.settings(Settings());model.viewModelScope.cancel()}}
    }
    @Test fun eachOfficeFamilyRequestsItsOwnVisualEditorAndConsumesOnlyOnce()=scenario {model->
        for(format in listOf(Format.DOCX,Format.XLSX,Format.PPTX)) {
            main {model.create(format,visual=true)};await(model)
            val request=model.state.value.officeLaunch!!
            assertEquals(model.state.value.session!!.id,request.sessionId)
            main {model.consumeOfficeLaunch(request)}
            assertNull(model.state.value.officeLaunch)
            main {model.consumeOfficeLaunch(request)}
            assertNull(model.state.value.officeLaunch)
        }
    }
    @Test fun legacyOptOutCannotReenableExtractedTextEditors()=scenario {model->
        main {model.settings(Settings(visualOffice=false));model.create(Format.DOCX,visual=true)};await(model)
        assertEquals(Format.DOCX,model.state.value.session!!.format)
        assertNotNull(model.state.value.officeLaunch)
        main {model.consumeOfficeLaunch(model.state.value.officeLaunch!!);model.settings(Settings(visualOffice=true));model.create(Format.HWPX,visual=true)};await(model)
        assertEquals(Format.HWPX,model.state.value.session!!.format)
        assertNotNull(model.state.value.officeLaunch)
        main {model.consumeOfficeLaunch(model.state.value.officeLaunch!!);model.create(Format.PDF,visual=true)};await(model)
        assertNull(model.state.value.officeLaunch)
    }
    @Test fun openingAndRestoringExistingOfficeRequestsCorrectSession()=scenario {model->
        main {model.create(Format.DOCX)};await(model)
        val original=model.state.value.session!!
        main {model.home();model.restore(original.id,visual=true)};await(model)
        assertEquals(original.id,model.state.value.officeLaunch!!.sessionId)
        main {model.consumeOfficeLaunch(model.state.value.officeLaunch!!)}
        val app=ApplicationProvider.getApplicationContext<Application>()
        val file=File(app.cacheDir,"tests/${UUID.randomUUID()}.docx").apply {parentFile!!.mkdirs();original.base.copyTo(this)}
        val uri=FileProvider.getUriForFile(app,"kr.documentstudio.testfiles",file)
        main {model.open(uri,visual=true)};await(model)
        assertEquals(model.state.value.session!!.id,model.state.value.officeLaunch!!.sessionId)
        assertNotEquals(original.id,model.state.value.officeLaunch!!.sessionId)
    }
    @Test fun failedOpenDoesNotLaunchPreviouslyActiveDocument()=scenario {model->
        main {model.create(Format.DOCX)};await(model)
        val id=model.state.value.active
        val app=ApplicationProvider.getApplicationContext<Application>()
        val file=File(app.cacheDir,"tests/${UUID.randomUUID()}.docx").apply {parentFile!!.mkdirs();writeText("not a word file")}
        val uri=FileProvider.getUriForFile(app,"kr.documentstudio.testfiles",file)
        main {model.open(uri,visual=true)};await(model)
        assertEquals(id,model.state.value.active)
        assertNull(model.state.value.officeLaunch)
        assertNotNull(model.state.value.message)
    }
}
