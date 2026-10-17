package kr.documentstudio.data

import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.*
import com.tom_roush.pdfbox.util.Matrix
import kr.documentstudio.core.*

/** Standard InkList remains editable; its appearance also preserves pressure. */
object PdfInkAppearance {
    fun margin(stroke: Stroke,points: List<InkPoint>,width: Float): Float = stroke.width*width*(points.maxOfOrNull {InkGeometry.widthResponse(stroke.tool,it.pressure,stroke.pressureCurve,Math.PI.toFloat()/2)} ?: 1f)+2
    fun write(doc: PDDocument,annotation: PDAnnotationMarkup,s: Stroke,points: List<InkPoint>,xy: List<Pair<Float,Float>>,width: Float) {
        val rect=annotation.rectangle
        val stream=PDAppearanceStream(doc).apply {bBox=PDRectangle(rect.width,rect.height);resources=PDResources()}
        PDPageContentStream(doc,stream).use {out->
            out.transform(Matrix.getTranslateInstance(-rect.lowerLeftX,-rect.lowerLeftY));out.setLineCapStyle(1);out.setLineJoinStyle(1)
            out.setStrokingColor(annotation.color);out.setNonStrokingColor(annotation.color)
            out.setGraphicsStateParameters(PDExtendedGraphicsState().apply {strokingAlphaConstant=annotation.constantOpacity;nonStrokingAlphaConstant=annotation.constantOpacity})
            if(xy.all {it==xy.first()}) {
                val (x,y)=xy.first();val r=s.width*width*InkGeometry.widthResponse(s.tool,points.first().pressure,s.pressureCurve,points.first().tilt)/2;val k=r*.55228475f
                out.moveTo(x+r,y);out.curveTo(x+r,y+k,x+k,y+r,x,y+r);out.curveTo(x-k,y+r,x-r,y+k,x-r,y);out.curveTo(x-r,y-k,x-k,y-r,x,y-r);out.curveTo(x+k,y-r,x+r,y-k,x+r,y);out.closePath();out.fill()
            } else if(s.tool in setOf(InkTool.PEN,InkTool.FOUNTAIN,InkTool.PENCIL,InkTool.BRUSH)) {
                for(i in 1 until xy.size) {val a=points[i-1];val b=points[i];out.setLineWidth(s.width*width*InkGeometry.widthResponse(s.tool,(a.pressure+b.pressure)/2,s.pressureCurve,a.tilt));out.moveTo(xy[i-1].first,xy[i-1].second);out.lineTo(xy[i].first,xy[i].second);out.stroke()}
            } else {
                out.setLineWidth(s.width*width);out.moveTo(xy.first().first,xy.first().second);for(i in 1 until xy.size) out.lineTo(xy[i].first,xy[i].second);out.stroke()
            }
        }
        annotation.appearance=PDAppearanceDictionary().apply {setNormalAppearance(stream)}
        if(annotation.constantOpacity<1 && doc.version<1.4f) doc.version=1.4f
    }
}
