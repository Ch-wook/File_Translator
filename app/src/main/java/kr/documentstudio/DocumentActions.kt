package kr.documentstudio

import kr.documentstudio.core.*

/** A pane keeps its own target even if another pane receives keyboard focus. */
class DocumentActions(private val model: EditorViewModel,val sessionId: String) {
    val repository get()=model.repository
    fun focus()=model.select(sessionId)
    fun message(value: String?)=model.message(value)
    fun settings(value: Settings)=model.settings(value)
    fun textFromOcr(value: String)=model.textFromOcr(value)
    fun text(value: String)=model.text(value,sessionId)
    fun field(id: String,value: String)=model.field(id,value,sessionId)
    fun bold(id: String,value: Boolean)=model.bold(id,value,sessionId)
    fun style(id: String,value: Map<String,String>)=model.style(id,value,sessionId)
    fun change(value: DocumentChange)=model.change(value,sessionId)
    fun inkLayers(value: List<InkLayer>)=model.inkLayers(value,sessionId)
    fun paper(value: Paper)=model.paper(value,sessionId)
    fun addStroke(value: Stroke)=model.addStroke(value,sessionId)
    fun erase(ids: Set<String>)=model.erase(ids,sessionId)
    fun eraseInk(anchor: String,gesture: List<InkPoint>,radius: Float,aspect: Float,partial: Boolean,projection: InkProjection?=null)=model.eraseInk(anchor,gesture,radius,aspect,partial,projection,sessionId)
    fun transformInk(ids: Set<String>,dx: Float=0f,dy: Float=0f,scale: Float=1f,degrees: Float=0f,duplicate: Boolean=false,projection: InkProjection?=null)=model.transformInk(ids,dx,dy,scale,degrees,duplicate,projection,sessionId)
    fun reencode(charset: String,bom: Boolean)=model.reencode(charset,bom,sessionId)
}
