package kr.documentstudio.data

import kotlin.math.*

/** Display-only raster bounds. File page dimensions and font sizes never change. */
object PdfRasterBudget {
    fun requestedWidth(viewportPixels: Int,zoom: Float): Int {
        require(viewportPixels>0 && zoom.isFinite() && zoom>0)
        return (ceil(viewportPixels.toDouble()*zoom/128)*128).toInt().coerceIn(128,4096)
    }
    fun dimensions(pageWidth: Int,pageHeight: Int,requestedWidth: Int,maxPixels: Int=4_000_000): Pair<Int,Int> {
        require(pageWidth>0 && pageHeight>0 && requestedWidth in 1..4096 && maxPixels in 65_536..4_000_000)
        val scale=minOf(requestedWidth.toDouble()/pageWidth,4096.0/pageHeight,sqrt(maxPixels.toDouble()/pageWidth/pageHeight))
        return (pageWidth*scale).toInt().coerceAtLeast(1) to (pageHeight*scale).toInt().coerceAtLeast(1)
    }
}
