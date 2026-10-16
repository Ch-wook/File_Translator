package kr.documentstudio.core

object InkLayers {
    fun validate(layers: List<InkLayer>) {
        require(layers.size in 1..8 && layers.map {it.id}.distinct().size==layers.size) {"레이어는 1~8개여야 합니다."}
        require(layers.all {it.id.matches(Regex("[a-zA-Z0-9-]{1,64}")) && it.name.length in 1..60})
    }
    fun ordered(edits: Edits,includeHidden: Boolean=false): List<Stroke> = edits.layers.filter {includeHidden || it.visible}.flatMap {layer->edits.strokes.filter {it.layerId==layer.id}}
    fun editable(edits: Edits): Set<String> = edits.layers.filter {it.visible && !it.locked}.map {it.id}.toSet()
    fun update(edits: Edits,layers: List<InkLayer>): Edits {
        validate(layers)
        require(edits.strokes.all {s->layers.any {it.id==s.layerId}}) {"필기가 있는 레이어는 먼저 비운 뒤 삭제하세요."}
        return edits.copy(layers=layers)
    }
}
