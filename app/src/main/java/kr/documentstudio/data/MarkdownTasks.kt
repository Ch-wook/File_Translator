package kr.documentstudio.data

import org.commonmark.node.*
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.renderer.NodeRenderer
import org.commonmark.renderer.html.HtmlRenderer

data class MarkdownTask(val offset: Int,val checked: Boolean)
object MarkdownTasks {
    private fun parser()=Parser.builder().extensions(listOf(TablesExtension.create(),org.commonmark.ext.gfm.strikethrough.StrikethroughExtension.create())).includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build()
    fun parse(source: String): Node=parser().parse(source)
    fun task(node: Text,source: String): MarkdownTask? {
        if(node.parent !is Paragraph || node.parent.parent !is ListItem || node.parent.firstChild!==node || node.parent.parent.firstChild!==node.parent) return null
        if(!Regex("^\\[[ xX]](?:[ \\t]|$)").containsMatchIn(node.literal)) return null
        val offset=node.sourceSpans.firstOrNull()?.inputIndex ?: return null
        if(offset<0 || offset+3>source.length || source[offset]!='[' || source[offset+2]!=']' || source[offset+1] !in " xX") return null
        return MarkdownTask(offset,source[offset+1]!=' ')
    }
    fun tasks(source: String): List<MarkdownTask> {
        val result=mutableListOf<MarkdownTask>();parse(source).accept(object: AbstractVisitor() {override fun visit(text: Text) {task(text,source)?.let {result.add(it)}}});return result
    }
    fun toggle(source: String,offset: Int): String {
        val task=tasks(source).firstOrNull {it.offset==offset} ?: error("체크리스트가 변경되었습니다.")
        return source.replaceRange(offset+1,offset+2,if(task.checked) " " else "x")
    }
    fun html(source: String,token: String): String = HtmlRenderer.builder().extensions(listOf(TablesExtension.create(),org.commonmark.ext.gfm.strikethrough.StrikethroughExtension.create())).escapeHtml(true).sanitizeUrls(true)
        .nodeRendererFactory {ctx->object: NodeRenderer {
            override fun getNodeTypes(): Set<Class<out Node>> = setOf(Text::class.java)
            override fun render(node: Node) {
                val text=node as Text;val task=task(text,source)
                if(task!=null) {ctx.writer.tag("a",mapOf("href" to "studio-task://$token/${task.offset}","role" to "checkbox","aria-checked" to task.checked.toString(),"aria-label" to if(task.checked) "완료 해제" else "완료 표시"));ctx.writer.text(if(task.checked) "☑" else "☐");ctx.writer.tag("/a");ctx.writer.text(text.literal.drop(3))}
                else ctx.writer.text(text.literal)
            }
        }}.build().render(parse(source))
}
