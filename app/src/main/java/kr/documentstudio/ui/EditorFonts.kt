package kr.documentstudio.ui

import android.content.Context
import android.graphics.Typeface

/** Actual bundled files, not aliases for proprietary fonts. TXT/MD use these only for display. */
object EditorFonts {
    val files=linkedMapOf(
        "Noto Sans KR" to "NotoSansKR.ttf",
        "NanumGothic" to "NanumGothic-Regular.ttf",
        "NanumMyeongjo" to "NanumMyeongjo-Regular.ttf",
        "Gowun Dodum" to "GowunDodum-Regular.ttf",
        "Gowun Batang" to "GowunBatang-Regular.ttf",
        "Lato" to "Lato-Regular.ttf",
        "Cousine" to "Cousine-Regular.ttf"
    )
    val korean=setOf("Noto Sans KR","NanumGothic","NanumMyeongjo","Gowun Dodum","Gowun Batang")
    private val labels=mapOf("NanumGothic" to "나눔고딕","NanumMyeongjo" to "나눔명조","Gowun Dodum" to "고운돋움","Gowun Batang" to "고운바탕")
    fun label(name: String)=labels[name]?.let {"$it · $name"} ?: name
    val names=files.keys.toList()+listOf("시스템 고딕","시스템 바탕","고정폭")
    private val cache=mutableMapOf<String,Typeface>()
    @Synchronized fun face(context: Context,name: String): Typeface=cache.getOrPut(name) {
        files[name]?.let {Typeface.createFromAsset(context.assets,"fonts/$it")} ?: when(name) {
            "시스템 고딕"->Typeface.SANS_SERIF
            "시스템 바탕"->Typeface.SERIF
            else->Typeface.MONOSPACE
        }
    }
}
