package kr.documentstudio.core

import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import org.w3c.dom.Document
import org.w3c.dom.Element

object OfficeObjects {
    val operations=setOf("word.image","word.hyperlink","word.header","word.footer","slide.image","slide.deleteObject","slide.objectOrder")
    private val W=PackageDocument.W;private val A=PackageDocument.A;private val R=PackageDocument.R
    private const val P="http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val PR="http://schemas.openxmlformats.org/package/2006/relationships"
    private const val CT="http://schemas.openxmlformats.org/package/2006/content-types"
    private const val WP="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    private const val PIC="http://schemas.openxmlformats.org/drawingml/2006/picture"
    fun apply(parts: MutableMap<String,ByteArray>,xml: (String)->Document,change: DocumentChange,attachment: File?) {
        val word=change.type.startsWith("word.");val path=if(word) "word/document.xml" else change.target;val doc=xml(path);val args=change.arguments
        val relPath=path.substringBeforeLast('/')+"/_rels/"+path.substringAfterLast('/')+".rels"
        fun relations(): Document {if(relPath !in parts) parts[relPath]="<Relationships xmlns='$PR'/>".toByteArray();return xml(relPath)}
        fun relation(type: String,target: String,external: Boolean=false): String {val rels=relations();val id="studio"+UUID.randomUUID().toString().replace("-","");rels.documentElement.appendChild(rels.element(PR,"Relationship") {setAttribute("Id",id);setAttribute("Type","$R/$type");setAttribute("Target",target);if(external) setAttribute("TargetMode","External")});return id}
        fun contentType(part: String,type: String) {val ct=xml("[Content_Types].xml");ct.documentElement.appendChild(ct.element(CT,"Override") {setAttribute("PartName","/$part");setAttribute("ContentType",type)})}
        fun paragraph(text: String)=doc.element(W,"w:p") {appendChild(doc.element(W,"w:r") {appendChild(doc.element(W,"w:t") {textContent=text;setAttributeNS("http://www.w3.org/XML/1998/namespace","xml:space","preserve")})})}
        fun appendBody(node: Element) {val body=Xml.elements(doc,"body").single();body.insertBefore(node,Xml.child(body,"sectPr"))}
        when(change.type) {
            "word.image","slide.image" -> {
                require(attachment!=null && attachment.length() in 24..16L*1024*1024) {"그림은 최대 16 MiB PNG까지 지원합니다."};val bytes=attachment.readBytes()
                require(bytes.take(8)==listOf(137.toByte(),80.toByte(),78.toByte(),71.toByte(),13.toByte(),10.toByte(),26.toByte(),10.toByte()) && bytes.copyOfRange(12,16).toString(Charsets.US_ASCII)=="IHDR") {"정규화된 PNG 그림이 필요합니다."}
                val iw=ByteBuffer.wrap(bytes,16,4).int;val ih=ByteBuffer.wrap(bytes,20,4).int;require(iw>0 && ih>0 && iw.toLong()*ih<=8_000_000)
                val w=(args["width"] ?: "12").toDouble();val h=args["height"]?.takeIf {it.isNotBlank()}?.toDouble() ?: w*ih/iw;require(w.isFinite() && h.isFinite() && w in 0.1..100.0 && h in 0.1..100.0)
                val cx=(w*360000).toLong();val cy=(h*360000).toLong();val root=if(word) "word" else "ppt";val name="studio-${UUID.randomUUID()}.png";val part="$root/media/$name";parts[part]=bytes;contentType(part,"image/png")
                val rid=relation("image",if(word) "media/$name" else "../media/$name")
                val id=(Xml.elements(doc,if(word) "docPr" else "cNvPr").maxOfOrNull {it.getAttribute("id").toLongOrNull() ?: 0L} ?: 0L)+1
                val picture="""<pic:pic xmlns:pic="$PIC" xmlns:a="$A" xmlns:r="$R"><pic:nvPicPr><pic:cNvPr id="$id" name="Studio picture $id"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed="$rid"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>"""
                if(word) {
                    val p=paragraph("");val run=Xml.elements(p,"r").single();run.removeChild(run.firstChild)
                    val drawing=doc.element(W,"w:drawing");val inline=doc.element(WP,"wp:inline") {setAttribute("distT","0");setAttribute("distB","0");setAttribute("distL","0");setAttribute("distR","0")}
                    inline.appendChild(doc.element(WP,"wp:extent") {setAttribute("cx",cx.toString());setAttribute("cy",cy.toString())});inline.appendChild(doc.element(WP,"wp:docPr") {setAttribute("id",id.toString());setAttribute("name","Studio picture $id")})
                    inline.appendChild(doc.element(WP,"wp:cNvGraphicFramePr") {appendChild(doc.element(A,"a:graphicFrameLocks") {setAttribute("noChangeAspect","1")})})
                    inline.appendChild(doc.element(A,"a:graphic") {appendChild(doc.element(A,"a:graphicData") {setAttribute("uri",PIC);appendChild(doc.importNode(Xml.parse(picture.toByteArray()).documentElement,true))})});drawing.appendChild(inline);run.appendChild(drawing);appendBody(p)
                } else {
                    val tree=Xml.elements(doc,"spTree").single();val x=(args["x"] ?: "1").toDouble();val y=(args["y"] ?: "1").toDouble();require(x.isFinite() && y.isFinite() && x in 0.0..100.0 && y in 0.0..100.0)
                    val pic=Xml.parse(picture.toByteArray()).documentElement
                    val element=doc.element(P,"p:pic") {appendChild(doc.element(P,"p:nvPicPr") {appendChild(doc.element(P,"p:cNvPr") {setAttribute("id",id.toString());setAttribute("name","Studio picture $id")});appendChild(doc.element(P,"p:cNvPicPr") {appendChild(doc.element(A,"a:picLocks") {setAttribute("noChangeAspect","1")})});appendChild(doc.element(P,"p:nvPr"))});val fill=Xml.child(pic,"blipFill")!!;appendChild(doc.importNode(fill,true).also {(it as Element).setPrefix("p")});appendChild(doc.element(P,"p:spPr") {appendChild(doc.element(A,"a:xfrm") {appendChild(doc.element(A,"a:off") {setAttribute("x",(x*360000).toLong().toString());setAttribute("y",(y*360000).toLong().toString())});appendChild(doc.element(A,"a:ext") {setAttribute("cx",cx.toString());setAttribute("cy",cy.toString())})});appendChild(doc.element(A,"a:prstGeom") {setAttribute("prst","rect");appendChild(doc.element(A,"a:avLst"))})})}
                    // p:blipFill uses the presentation namespace, while its children are DrawingML.
                    val imported=Xml.child(element,"blipFill")!!;val proper=doc.element(P,"p:blipFill");while(imported.hasChildNodes()) proper.appendChild(imported.firstChild);element.replaceChild(proper,imported)
                    tree.insertBefore(element,Xml.child(tree,"extLst"))
                }
            }
            "word.hyperlink" -> {
                val target=args.getValue("url");require(target.length<=2048 && java.net.URI(target).scheme?.lowercase() in listOf("https","http","mailto")) {"http, https, mailto 링크만 지원합니다."};val id=relation("hyperlink",target,true)
                val text=args.getValue("text");require(text.length<=32767);val p=doc.element(W,"w:p") {appendChild(doc.element(W,"w:hyperlink") {setAttributeNS(R,"r:id",id);appendChild(doc.element(W,"w:r") {appendChild(doc.element(W,"w:rPr") {appendChild(doc.element(W,"w:color") {setAttributeNS(W,"w:val","0563C1")});appendChild(doc.element(W,"w:u") {setAttributeNS(W,"w:val","single")})});appendChild(doc.element(W,"w:t") {textContent=text})})})};appendBody(p)
            }
            "word.header","word.footer" -> {
                val type=change.type.substringAfter('.');val body=Xml.elements(doc,"body").single();val section=Xml.child(body,"sectPr") ?: doc.element(W,"w:sectPr").also(body::appendChild)
                var reference=Xml.elements(section,type+"Reference").firstOrNull {it.getAttributeNS(W,"type")=="default"}
                val part=if(reference!=null) {val id=reference.getAttributeNS(R,"id");val rel=Xml.elements(relations(),"Relationship").first {it.getAttribute("Id")==id};val t=rel.getAttribute("Target");java.net.URI(if(t.startsWith('/')) t else "/word/$t").normalize().path.removePrefix("/")}
                    else {val name="studio-$type-${UUID.randomUUID()}.xml";val id=relation(type,name);reference=doc.element(W,"w:${type}Reference") {setAttributeNS(W,"w:type","default");setAttributeNS(R,"r:id",id)};val children=(0 until section.childNodes.length).map {section.childNodes.item(it)}.filterIsInstance<Element>();section.insertBefore(reference,children.firstOrNull {if(type=="header") it.localName!="headerReference" else it.localName !in listOf("headerReference","footerReference")});val part="word/$name";parts[part]="<w:${if(type=="header") "hdr" else "ftr"} xmlns:w='$W'/>".toByteArray();contentType(part,"application/vnd.openxmlformats-officedocument.wordprocessingml.$type+xml");part}
                val hf=xml(part);val text=args["text"] ?: "";require(text.length<=32767);val p=paragraph(text);hf.documentElement.appendChild(hf.importNode(p,true))
                if(type=="footer" && args["pageNumber"]=="true") hf.documentElement.appendChild(hf.element(W,"w:p") {appendChild(hf.element(W,"w:fldSimple") {setAttributeNS(W,"w:instr"," PAGE ");appendChild(hf.element(W,"w:r") {appendChild(hf.element(W,"w:t") {textContent="1"})})})})
            }
            "slide.deleteObject","slide.objectOrder" -> {
                val tree=Xml.elements(doc,"spTree").single();fun objects()=(0 until tree.childNodes.length).map {tree.childNodes.item(it)}.filterIsInstance<Element>().filter {it.localName in listOf("sp","pic","graphicFrame","grpSp","cxnSp")}
                val all=objects();val shape=args["shapeId"]?.let {id->all.firstOrNull {Xml.elements(it,"cNvPr").firstOrNull()?.getAttribute("id")==id}} ?: all.getOrNull(args.getValue("shape").toInt()) ?: error("개체 번호를 확인하세요.")
                if(change.type=="slide.deleteObject") {val id=Xml.elements(shape,"cNvPr").first().getAttribute("id");require(Xml.elements(doc,"spTgt").none {it.getAttribute("spid")==id}) {"애니메이션이 참조하는 개체는 삭제할 수 없습니다."};tree.removeChild(shape)}
                else {val index=args.getValue("index").toInt();require(index in all.indices);tree.removeChild(shape);tree.insertBefore(shape,objects().getOrNull(index) ?: Xml.child(tree,"extLst"))}
            }
        }
    }
    private fun Document.element(ns: String,name: String,configure: Element.()->Unit = {})=createElementNS(ns,name).apply(configure)
}
