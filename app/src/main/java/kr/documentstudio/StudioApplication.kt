package kr.documentstudio

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class StudioApplication: Application() {
    override fun onCreate() {super.onCreate();PDFBoxResourceLoader.init(this)}
}
