package com.sahed.document_viewer

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Xml
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.hwpf.extractor.WordExtractor
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.apache.poi.ss.usermodel.DataFormatter
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.zip.ZipFile

/** Offline text previews, not an Office layout engine. Never executes macros or follows links. */
internal object OfficePreview {
    const val MAX_INPUT_BYTES = 32L * 1024 * 1024
    private const val MAX_TEXT = 1_000_000
    private const val MAX_PAGES = 400
    private const val NO_TEXT = "No readable text. Images, charts and embedded objects are not shown in this preview."

    data class Section(val title: String, val text: String)

    fun convert(source: File, format: DocumentFormat, target: File) {
        require(source.length() <= MAX_INPUT_BYTES) { "Office file is too large (maximum 32 MB)." }
        val sections = read(source, format)
        render(sections, target)
    }

    internal fun read(source: File, format: DocumentFormat): List<Section> {
        val result = when (format) {
            DocumentFormat.DOCX, DocumentFormat.PPTX, DocumentFormat.XLSX ->
                Package(source).use { archive ->
                    when (format) {
                        DocumentFormat.DOCX -> listOf(Section("Word document", archive.paragraphs("word/document.xml")))
                        DocumentFormat.PPTX -> archive.slides()
                        else -> archive.sheets()
                    }
                }
            DocumentFormat.DOC -> source.inputStream().use { input -> WordExtractor(input).use {
                listOf(Section("Word document", bounded(it.text)))
            } }
            DocumentFormat.XLS -> source.inputStream().use { input -> HSSFWorkbook(input).use { book ->
                val formatter = DataFormatter(java.util.Locale.US)
                require(book.numberOfSheets <= 500) { "Too many worksheets." }
                (0 until book.numberOfSheets).map { index ->
                    val text = StringBuilder()
                    for (row in book.getSheetAt(index)) {
                        for (cell in row) {
                            val value = if (cell.cellType == org.apache.poi.ss.usermodel.CellType.FORMULA)
                                "=${cell.cellFormula}" else formatter.formatCellValue(cell)
                            if (value.isNotEmpty()) append(text, "${cell.address}: $value\n")
                        }
                        append(text, "\n")
                    }
                    Section(book.getSheetName(index), text.toString())
                }
            } }
            DocumentFormat.PPT -> binarySlides(source)
            else -> error("Not an Office file")
        }
        require(result.sumOf { it.text.length.toLong() } <= MAX_TEXT) { "Document contains too much text." }
        return result.ifEmpty { listOf(Section("Document", NO_TEXT)) }
    }

    private fun bounded(text: String): String {
        require(text.length <= MAX_TEXT) { "Document contains too much text." }
        return text.replace('\u0007', '\t').replace('\r', '\n')
    }

    private fun append(text: StringBuilder, value: String) {
        require(text.length + value.length <= MAX_TEXT) { "Document contains too much text." }
        text.append(value)
    }

    // Read only text atoms from the legacy PowerPoint record stream. HSLF's presentation
    // model references desktop AWT classes, which are unavailable on Android.
    private fun binarySlides(source: File): List<Section> = source.inputStream().use { input -> POIFSFileSystem(input).use { fs ->
        val bytes = fs.createDocumentInputStream("PowerPoint Document").use { input ->
            require(input.available().toLong() <= MAX_INPUT_BYTES) { "Presentation is too large." }
            input.readBytes()
        }
        val text = StringBuilder()
        fun u16(offset: Int) = (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
        fun records(start: Int, end: Int, depth: Int) {
            require(depth <= 64) { "Presentation nesting is too deep." }
            var offset = start
            while (offset + 8 <= end) {
                val type = u16(offset + 2)
                val length = (0..3).fold(0L) { n, i -> n or ((bytes[offset + 4 + i].toLong() and 255) shl (8 * i)) }
                require(length <= end - offset - 8) { "Damaged presentation record." }
                val next = offset + 8 + length.toInt()
                if (u16(offset) and 15 == 15) records(offset + 8, next, depth + 1)
                else if (type == 4000 || type == 4008) {
                    val charset = if (type == 4000) Charsets.UTF_16LE else charset("windows-1252")
                    append(text, bounded(String(bytes, offset + 8, length.toInt(), charset)) + "\n\n")
                }
                offset = next
            }
        }
        records(0, bytes.size, 0)
        listOf(Section("PowerPoint text", text.toString()))
    } }

    private class Package(file: File) : java.io.Closeable {
        private val zip = ZipFile(file)
        private var expanded = 0L

        init {
            if (zip.size() > 10_000) {
                zip.close()
                error("Too many archive entries.")
            }
        }

        private fun xml(path: String, visit: (XmlPullParser, Int) -> Unit) {
            val entry = zip.getEntry(path) ?: error("Missing Office content: $path")
            val bytes = zip.getInputStream(entry).use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    expanded += count
                    require(out.size() + count <= 16 * 1024 * 1024 && expanded <= 64L * 1024 * 1024) {
                        "Expanded Office content is too large."
                    }
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(bytes.inputStream(), null)
            var depth = 0
            while (true) {
                val event = parser.nextToken()
                if (event == XmlPullParser.END_DOCUMENT) break
                require(event != XmlPullParser.DOCDECL) { "Document type declarations are not allowed." }
                if (event == XmlPullParser.START_TAG) { depth++; require(depth <= 128) }
                visit(parser, event)
                if (event == XmlPullParser.END_TAG) depth--
            }
        }

        fun paragraphs(path: String): String {
            val text = StringBuilder()
            var inText = false
            xml(path) { p, event ->
                when (event) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "t" -> inText = true
                        "tab" -> append(text, "\t")
                        "br", "cr" -> append(text, "\n")
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF ->
                        if (inText) append(text, p.text ?: "")
                    XmlPullParser.END_TAG -> when (p.name) {
                        "t" -> inText = false
                        "p" -> append(text, "\n")
                        "tc" -> append(text, "\t")
                    }
                }
            }
            return text.toString()
        }

        private fun relationships(path: String, base: String): Map<String, String> {
            val result = mutableMapOf<String, String>()
            xml(path) { p, event ->
                if (event == XmlPullParser.START_TAG && p.name == "Relationship" && p.attr("TargetMode") != "External") {
                    val target = p.attr("Target") ?: return@xml
                    val resolved = java.net.URI("/$base/").resolve(target).normalize().path.removePrefix("/")
                    require(resolved.startsWith("$base/")) { "Invalid Office relationship." }
                    result[p.attr("Id") ?: ""] = resolved
                }
            }
            return result
        }

        private fun XmlPullParser.attr(name: String): String? = (0 until attributeCount)
            .firstOrNull { getAttributeName(it) == name }?.let { getAttributeValue(it) }

        fun slides(): List<Section> {
            val links = relationships("ppt/_rels/presentation.xml.rels", "ppt")
            val paths = mutableListOf<String>()
            xml("ppt/presentation.xml") { p, event ->
                if (event == XmlPullParser.START_TAG && p.name == "sldId") {
                    val id = p.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
                    paths += links[id] ?: error("Missing slide")
                    require(paths.size <= 500) { "Too many slides." }
                }
            }
            return paths.mapIndexed { index, path -> Section("Slide ${index + 1}", paragraphs(path)) }
        }

        fun sheets(): List<Section> {
            val strings = mutableListOf<String>()
            if (zip.getEntry("xl/sharedStrings.xml") != null) {
                var value = StringBuilder()
                var inText = false
                xml("xl/sharedStrings.xml") { p, event ->
                    if (event == XmlPullParser.START_TAG) {
                        if (p.name == "si") value = StringBuilder()
                        if (p.name == "t") inText = true
                    } else if (event == XmlPullParser.END_TAG) {
                        if (p.name == "t") inText = false
                        if (p.name == "si") { strings += value.toString(); require(strings.size <= 100_000) }
                    } else if (inText && event in textEvents) append(value, p.text ?: "")
                }
            }
            val links = relationships("xl/_rels/workbook.xml.rels", "xl")
            val sheets = mutableListOf<Pair<String, String>>()
            xml("xl/workbook.xml") { p, event ->
                if (event == XmlPullParser.START_TAG && p.name == "sheet") {
                    sheets += (p.attr("name") ?: "Sheet") to (links[p.attr("id")] ?: error("Missing worksheet"))
                    require(sheets.size <= 500) { "Too many worksheets." }
                }
            }
            return sheets.map { (name, path) ->
                val text = StringBuilder()
                var address = ""
                var type = ""
                var value = StringBuilder()
                var formula = StringBuilder()
                var capture: String? = null
                xml(path) { p, event ->
                    when (event) {
                        XmlPullParser.START_TAG -> when (p.name) {
                            "c" -> { address = p.attr("r") ?: "Cell"; type = p.attr("t") ?: ""; value = StringBuilder(); formula = StringBuilder() }
                            "v", "t", "f" -> capture = p.name
                        }
                        XmlPullParser.END_TAG -> when (p.name) {
                            "v", "t", "f" -> capture = null
                            "c" -> {
                                val raw = value.toString()
                                val display = when {
                                    type == "s" -> strings.getOrNull(raw.toIntOrNull() ?: -1) ?: error("Invalid shared string")
                                    type == "b" -> if (raw == "1") "TRUE" else "FALSE"
                                    raw.isNotEmpty() -> raw
                                    formula.isNotEmpty() -> "=$formula"
                                    else -> ""
                                }
                                if (display.isNotEmpty()) append(text, "$address: $display\n")
                            }
                            "row" -> append(text, "\n")
                        }
                        in textEvents -> if (capture != null) append(if (capture == "f") formula else value, p.text ?: "")
                    }
                }
                Section(name, text.toString())
            }
        }

        override fun close() = zip.close()
    }

    private val textEvents = setOf(XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF)

    private fun render(sections: List<Section>, target: File) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 12f }
        val heading = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(20, 80, 60); textSize = 18f; typeface = Typeface.DEFAULT_BOLD }
        val footer = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 9f }
        val pdf = PdfDocument()
        try {
            var number = 0
            for (section in sections) {
                val content = section.text.trim().ifEmpty { NO_TEXT }
                val layout = StaticLayout.Builder.obtain(content, 0, content.length, paint, 515)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(3f, 1f).build()
                var firstLine = 0
                while (firstLine < layout.lineCount) {
                    require(++number <= MAX_PAGES) { "Preview exceeds 400 pages." }
                    val top = layout.getLineTop(firstLine)
                    var endLine = firstLine + 1
                    while (endLine < layout.lineCount && layout.getLineBottom(endLine) - top <= 700) endLine++
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, number).create())
                    val canvas = page.canvas
                    canvas.drawColor(Color.WHITE)
                    canvas.drawText(android.text.TextUtils.ellipsize(section.title, TextPaint(heading), 515f,
                        android.text.TextUtils.TruncateAt.END).toString(), 40f, 44f, heading)
                    canvas.save()
                    canvas.clipRect(40, 66, 555, 766)
                    canvas.translate(40f, 66f - top)
                    layout.draw(canvas)
                    canvas.restore()
                    canvas.drawText("Simplified Office preview · $number", 40f, 807f, footer)
                    pdf.finishPage(page)
                    firstLine = endLine
                }
            }
            target.outputStream().use(pdf::writeTo)
        } finally { pdf.close() }
    }
}
