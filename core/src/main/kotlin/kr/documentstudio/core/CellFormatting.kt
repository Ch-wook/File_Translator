package kr.documentstudio.core

import org.apache.poi.ss.usermodel.DataFormatter
import java.util.Locale

object CellFormatting {
    private val formatter=ThreadLocal.withInitial {DataFormatter(Locale.US)}
    val builtins=mapOf("0" to "General","1" to "0","2" to "0.00","3" to "#,##0","4" to "#,##0.00","9" to "0%","10" to "0.00%","11" to "0.00E+00","14" to "m/d/yy","15" to "d-mmm-yy","16" to "d-mmm","17" to "mmm-yy","18" to "h:mm AM/PM","19" to "h:mm:ss AM/PM","20" to "h:mm","21" to "h:mm:ss","22" to "m/d/yy h:mm","37" to "#,##0 ;(#,##0)","38" to "#,##0 ;[Red](#,##0)","39" to "#,##0.00;(#,##0.00)","40" to "#,##0.00;[Red](#,##0.00)","45" to "mm:ss","46" to "[h]:mm:ss","47" to "mmss.0","48" to "##0.0E+0","49" to "@")
    fun format(value: String,code: String?,date1904: Boolean=false): String {
        val number=value.toDoubleOrNull()?.takeIf(Double::isFinite) ?: return value
        if(code.isNullOrBlank() || code=="@") return value
        return runCatching {formatter.get().formatRawCellContents(number,0,code,date1904)}.getOrDefault(value)
    }
}
