package kr.documentstudio.data

import kr.documentstudio.core.*

fun Session.sheetCells(section: Section)=section.cells+edits.values.filterKeys {it.startsWith(section.id+"#")}.mapKeys {it.key.substringAfter('#')}
fun Session.calculator(section: Section)=Formula(sheetCells(section),sections.associate {it.sourceName to sheetCells(it)},section.sourceName,section.date1904)
