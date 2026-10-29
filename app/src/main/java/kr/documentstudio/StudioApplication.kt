package kr.documentstudio

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class StudioApplication: Application(),org.libreoffice.androidlib.StudioDocumentHost {
    override fun publishResult(source: java.io.File,destination: android.net.Uri,originalUri: String?) {
        kr.documentstudio.data.ConversionFiles.publish(this,source,destination,originalUri)
    }
    override fun sameDocument(left: String?,right: String?)=kr.documentstudio.data.DocumentUris.same(left,right)
    override fun describeFailure(failure: Throwable): String {
        kr.documentstudio.data.SafeDiagnostics.record(this,failure)
        return kr.documentstudio.data.UserProblems.explain(failure).let {it.title+" · "+it.advice}
    }
    override fun onCreate() {super.onCreate();kr.documentstudio.data.SafeDiagnostics.install(this);PDFBoxResourceLoader.init(this)}
}
