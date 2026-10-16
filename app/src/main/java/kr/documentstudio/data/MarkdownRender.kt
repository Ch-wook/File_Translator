package kr.documentstudio.data
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.*
import android.graphics.Typeface
import org.commonmark.node.*
import org.commonmark.parser.Parser
import org.commonmark.ext.gfm.tables.TablesExtension

object MarkdownRender {
    fun render(source: String): CharSequence {
        val document=Parser.builder().extensions(listOf(TablesExtension.create())).build().parse(source)
        val result=SpannableStringBuilder()
        fun children(node: Node,visit: (Node)->Unit) {var child=node.firstChild;while(child!=null) {visit(child);child=child.next}}
        fun visit(node: Node) {
            val start=result.length
            when(node) {
                is Text -> result.append(node.literal)
                is SoftLineBreak -> result.append(' ')
                is HardLineBreak -> result.append('\n')
                is Code -> result.append(node.literal)
                is FencedCodeBlock -> result.append(node.literal).append('\n')
                is IndentedCodeBlock -> result.append(node.literal).append('\n')
                is HtmlInline,is HtmlBlock -> Unit
                is ListItem -> {result.append("• ");children(node,::visit);if(result.lastOrNull()!='\n') result.append('\n')}
                is ThematicBreak -> result.append("────────────────────\n")
                else -> children(node,::visit)
            }
            val end=result.length
            fun span(s: Any) {if(end>start) result.setSpan(s,start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)}
            when(node) {
                is Heading -> {span(StyleSpan(Typeface.BOLD));span(RelativeSizeSpan(when(node.level) {1->1.8f;2->1.5f;else->1.2f}));result.append("\n\n")}
                is StrongEmphasis -> span(StyleSpan(Typeface.BOLD))
                is Emphasis -> span(StyleSpan(Typeface.ITALIC))
                is Code,is FencedCodeBlock,is IndentedCodeBlock -> span(TypefaceSpan("monospace"))
                is Link -> {span(UnderlineSpan());span(ForegroundColorSpan(android.graphics.Color.rgb(49,95,180)))}
                is BlockQuote -> {span(LeadingMarginSpan.Standard(16));result.append('\n')}
                is Paragraph -> result.append("\n\n")
                else -> {if(node.javaClass.simpleName=="TableCell") result.append("    ");if(node.javaClass.simpleName=="TableRow") result.append('\n')}
            }
        }
        visit(document);return result
    }
}
