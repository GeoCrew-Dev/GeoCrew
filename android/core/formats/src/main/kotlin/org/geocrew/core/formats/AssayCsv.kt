package org.geocrew.core.formats

import org.geocrew.core.model.AssayTable
import org.geocrew.core.model.AssayValue
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Reads a lab's result sheet saved as CSV: one row per sample, a column with
 * the sample number and one column per element or oxide. Lab sheets are
 * often saved from Excel in GBK, so text that isn't valid UTF-8 is read as
 * GB18030. A second row that holds only units (ppm, %, 10⁻⁶…) is folded into
 * the column names, giving "Cu(ppm)". Columns without a single number are
 * left out, so remarks and method columns don't become map attributes.
 */
object AssayCsv {
    private val SAMPLE_NAMES = setOf("样品编号", "样品号", "样号", "原样号", "编号", "原号", "送样号", "sample", "sample no", "sample_no", "sampleno", "sample id", "sample_id", "sampleid", "no", "no.", "id")
    private val UNIT_LABELS = setOf("", "单位", "unit", "units")

    fun read(bytes: ByteArray): AssayTable = parse(decode(bytes))

    fun decode(bytes: ByteArray): String {
        val utf8 = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            utf8.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, Charset.forName("GB18030"))
        }
        return text.removePrefix("\uFEFF")
    }

    fun parse(text: String): AssayTable {
        val rows = CsvFormat.parse(text).map { r -> r.map { it.trim() } }.filter { r -> r.any { it.isNotEmpty() } }
        if (rows.isEmpty()) throw FormatException("文件是空的")
        val header = rows.first()
        val noCol = header.indexOfFirst { it.lowercase() in SAMPLE_NAMES }
            .takeIf { it >= 0 } ?: header.indexOfFirst { "样品" in it || "样号" in it }.takeIf { it >= 0 } ?: 0
        var body = rows.drop(1)
        var names = header.mapIndexed { i, h -> h.ifEmpty { "列${i + 1}" } }
        // A units row: no sample number (or "单位"), and nothing in it reads as a value.
        body.firstOrNull()?.let { u ->
            val label = u.getOrNull(noCol).orEmpty().lowercase()
            val cells = u.filterIndexed { i, _ -> i != noCol }
            if (label in UNIT_LABELS && cells.any { it.isNotEmpty() } && cells.none { AssayValue.parse(it) != null } && cells.all { it.length <= 12 }) {
                names = names.mapIndexed { i, n -> u.getOrNull(i)?.takeIf { i != noCol && it.isNotEmpty() }?.let { "$n($it)" } ?: n }
                body = body.drop(1)
            }
        }
        val parsed = body.mapNotNull { r ->
            val no = r.getOrNull(noCol)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val values = names.indices.filter { it != noCol }.mapNotNull { i ->
                r.getOrNull(i)?.let(AssayValue::parse)?.let { names[i] to it }
            }.toMap()
            AssayTable.Row(no, values)
        }
        if (parsed.isEmpty()) throw FormatException("没有读到样品行：第一行应是列名，其中一列是样品编号")
        val used = names.filterIndexed { i, n -> i != noCol && parsed.any { n in it.values } }.distinct()
        if (used.isEmpty()) throw FormatException("没有找到数值列，请确认分析结果是数字（低于检出限可写成 <0.05）")
        return AssayTable(used, parsed)
    }
}
