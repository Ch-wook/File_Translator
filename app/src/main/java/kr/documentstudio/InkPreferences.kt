package kr.documentstudio

import android.content.SharedPreferences
import kr.documentstudio.core.InkTool
import org.json.JSONArray
import org.json.JSONObject

data class PenPreset(val name: String,val tool: String,val color: Int,val width: Float,val opacity: Float,val curve: Float,val smoothing: Float,val snap: Boolean) {
    fun apply(s: Settings)=s.copy(penTool=tool,penColor=color,penWidth=width,penOpacity=opacity,pressureCurve=curve,smoothing=smoothing,autoSnap=snap)
    companion object {fun from(name: String,s: Settings)=PenPreset(name,s.penTool,s.penColor,s.penWidth,s.penOpacity,s.pressureCurve,s.smoothing,s.autoSnap)}
}
object InkPreferences {
    fun extend(s: Settings,p: SharedPreferences): Settings = s.copy(
        pressureCurve=p.getFloat("pressureCurve",1f).finite(0.25f,3f,1f),smoothing=p.getFloat("smoothing",0f).finite(0f,1f,0f),
        visualOffice=p.getBoolean("visualOffice",true),autoSnap=p.getBoolean("autoSnap",false),toolbarTop=p.getBoolean("toolbarTop",false),toolbarCollapsed=p.getBoolean("toolbarCollapsed",false),
        favoriteColors=runCatching {val a=JSONArray(p.getString("favoriteColors","[]"));require(a.length()<=16);(0 until a.length()).map {a.getInt(it)}.distinct()}.getOrDefault(emptyList()),
        presets=runCatching {val a=JSONArray(p.getString("penPresets","[]"));require(a.length()<=12);(0 until a.length()).map {i->a.getJSONObject(i).let {j->
            val name=j.getString("name");val tool=j.getString("tool");require(name.length in 1..40 && tool in InkTool.entries.map {it.name})
            PenPreset(name,tool,j.getInt("color"),j.getDouble("width").toFloat().finite(0.002f,0.025f,0.004f),j.getDouble("opacity").toFloat().finite(0.1f,1f,1f),j.getDouble("curve").toFloat().finite(0.25f,3f,1f),j.getDouble("smoothing").toFloat().finite(0f,1f,0f),j.getBoolean("snap"))
        }}}.getOrDefault(emptyList()))
    fun save(e: SharedPreferences.Editor,s: Settings): SharedPreferences.Editor = e
        .putFloat("pressureCurve",s.pressureCurve).putFloat("smoothing",s.smoothing).putBoolean("autoSnap",s.autoSnap)
        .putBoolean("toolbarTop",s.toolbarTop).putBoolean("toolbarCollapsed",s.toolbarCollapsed)
        .putString("favoriteColors",JSONArray(s.favoriteColors.distinct().take(16)).toString())
        .putString("penPresets",JSONArray().apply {s.presets.take(12).forEach {p->put(JSONObject().apply {
            put("name",p.name);put("tool",p.tool);put("color",p.color);put("width",p.width);put("opacity",p.opacity);put("curve",p.curve);put("smoothing",p.smoothing);put("snap",p.snap)
        })}}.toString())
    private fun Float.finite(low: Float,high: Float,fallback: Float)=if(isFinite()) coerceIn(low,high) else fallback
}
