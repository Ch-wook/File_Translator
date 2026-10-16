package kr.documentstudio.data

import kr.documentstudio.core.*
import org.json.JSONArray
import org.json.JSONObject

object StateCodec {
    fun encode(edits: Edits): JSONObject = JSONObject().apply {
        put("text", edits.text); put("values", JSONObject(edits.values)); put("bold", JSONObject(edits.bold))
        put("styles",JSONObject().apply {edits.styles.forEach {(key,value) -> put(key,JSONObject(value))}})
        put("strokes", JSONArray().apply { edits.strokes.forEach { s -> put(JSONObject().apply {
            put("id", s.id); put("anchor", s.anchor); put("tool", s.tool.name); put("color", s.color); put("width", s.width.toDouble())
            put("opacity",s.opacity.toDouble())
            put("points", JSONArray().apply { s.points.forEach { p -> put(JSONArray(listOf(p.x.toDouble(), p.y.toDouble(), p.pressure.toDouble(), p.tilt.toDouble()))) } })
        }) } })
    }
    fun decode(json: JSONObject): Edits {
        val values = json.optJSONObject("values") ?: JSONObject()
        val bold = json.optJSONObject("bold") ?: JSONObject()
        val strokes = json.optJSONArray("strokes") ?: JSONArray()
        require(strokes.length() <= 20000)
        var totalPoints=0
        val styles=json.optJSONObject("styles") ?: JSONObject()
        return Edits(json.optString("text"), values.keys().asSequence().associateWith { values.getString(it) },
            bold.keys().asSequence().associateWith { bold.getBoolean(it) }, (0 until strokes.length()).map { i ->
                val s = strokes.getJSONObject(i); val points = s.getJSONArray("points"); require(points.length() <= 100000)
                totalPoints+=points.length();require(totalPoints<=200000) {"필기 포인트 용량 제한 초과"}
                val width=s.getDouble("width").toFloat();require(width.isFinite() && width in 0.0001f..0.1f)
                Stroke(s.getString("id"), s.getString("anchor"), InkTool.valueOf(s.getString("tool")), s.getInt("color"), width,
                    (0 until points.length()).map { j -> points.getJSONArray(j).let { p ->
                        val x=p.getDouble(0).toFloat();val y=p.getDouble(1).toFloat();val pressure=p.getDouble(2).toFloat();val tilt=p.optDouble(3,0.0).toFloat()
                        require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f && pressure.isFinite() && tilt.isFinite())
                        InkPoint(x,y,pressure,tilt)
                    } },s.optDouble("opacity",1.0).toFloat().also {require(it.isFinite() && it in 0f..1f)})
            }, styles.keys().asSequence().associateWith {key -> val value=styles.getJSONObject(key);value.keys().asSequence().associateWith {value.getString(it)}})
    }
}
