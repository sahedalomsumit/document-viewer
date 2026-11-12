package com.sahed.document_viewer

import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class OfficePreviewInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun archive(extension: String, vararg parts: Pair<String, String>): File =
        File.createTempFile("office-test-", ".$extension", context.cacheDir).also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                parts.forEach { (path, text) ->
                    zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry()
                }
            }
        }

    private fun word(text: String = "Hello &amp; welcome"): File = archive("docx",
        "word/document.xml" to """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>$text</w:t></w:r></w:p></w:body></w:document>""")

    private fun assertPdf(file: File, format: DocumentFormat): Int {
        val output = File.createTempFile("preview-test-", ".pdf", context.cacheDir)
        try {
            OfficePreview.convert(file, format, output)
            return ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { pdf -> assertTrue(pdf.pageCount > 0); pdf.pageCount }
            }
        } finally { output.delete() }
    }

    @Test fun wordTextAndPagination() {
        val file = word()
        try {
            assertTrue(OfficePreview.read(file, DocumentFormat.DOCX).single().text.contains("Hello & welcome"))
            assertEquals(1, assertPdf(file, DocumentFormat.DOCX))
        } finally { file.delete() }
        val long = word((1..200).joinToString("<w:br/>") { "Line $it" }.replace("<w:br/>", "</w:t><w:br/><w:t>"))
        try { assertTrue(assertPdf(long, DocumentFormat.DOCX) > 1) } finally { long.delete() }
    }

    @Test fun presentationUsesDeclaredSlideOrderNotArchiveOrder() {
        val file = archive("pptx",
            "ppt/presentation.xml" to """<p:presentation xmlns:p="p" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><p:sldIdLst><p:sldId id="256" r:id="second"/><p:sldId id="257" r:id="first"/></p:sldIdLst></p:presentation>""",
            "ppt/_rels/presentation.xml.rels" to """<Relationships><Relationship Id="first" Target="slides/slide1.xml"/><Relationship Id="second" Target="slides/slide2.xml"/></Relationships>""",
            "ppt/slides/slide1.xml" to "<slide><p><t>First</t></p></slide>",
            "ppt/slides/slide2.xml" to "<slide><p><t>Second</t></p></slide>")
        try {
            assertEquals(listOf("Second", "First"), OfficePreview.read(file, DocumentFormat.PPTX).map { it.text.trim() })
            assertEquals(2, assertPdf(file, DocumentFormat.PPTX))
        } finally { file.delete() }
    }

    @Test fun spreadsheetResolvesStringsInlineTextAndCachedFormulas() {
        val file = archive("xlsx",
            "xl/workbook.xml" to """<workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Budget" r:id="sheet1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships><Relationship Id="sheet1" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/sharedStrings.xml" to "<sst><si><r><t>Shared </t></r><r><t>text</t></r></si></sst>",
            "xl/worksheets/sheet1.xml" to """<worksheet><sheetData><row><c r="A1" t="s"><v>0</v></c><c r="B1" t="inlineStr"><is><t>Inline</t></is></c><c r="C1"><f>1+2</f><v>3</v></c><c r="D1" t="b"><v>1</v></c></row></sheetData></worksheet>""")
        try {
            val section = OfficePreview.read(file, DocumentFormat.XLSX).single()
            assertEquals("Budget", section.title)
            listOf("A1: Shared text", "B1: Inline", "C1: 3", "D1: TRUE").forEach { assertTrue(section.text.contains(it)) }
            assertPdf(file, DocumentFormat.XLSX)
        } finally { file.delete() }
    }

    @Test fun legacyExcelWorksWithoutDesktopJavaApis() {
        val file = File.createTempFile("legacy-test-", ".xls", context.cacheDir)
        try {
            HSSFWorkbook().use { book ->
                book.createSheet("Legacy").createRow(0).createCell(0).setCellValue("Legacy cell")
                file.outputStream().use(book::write)
            }
            assertTrue(OfficePreview.read(file, DocumentFormat.XLS).single().text.contains("A1: Legacy cell"))
            assertPdf(file, DocumentFormat.XLS)
        } finally { file.delete() }
    }

    @Test fun legacyWordAndPowerPointWorkWithoutDesktopJavaApis() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        for ((name, format) in listOf("simple.doc" to DocumentFormat.DOC, "basic_test_ppt_file.ppt" to DocumentFormat.PPT)) {
            val file = File(context.cacheDir, "test-$name")
            try {
                assets.open("office/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
                assertTrue(OfficePreview.read(file, format).any { it.text.isNotBlank() })
                assertPdf(file, format)
            } finally { file.delete() }
        }
    }

    @Test fun rejectsDtdAndInvalidOfficeContent() {
        val file = archive("docx", "word/document.xml" to """<!DOCTYPE doc [<!ENTITY x SYSTEM "file:///etc/passwd">]><doc><t>&x;</t></doc>""")
        try {
            try { OfficePreview.read(file, DocumentFormat.DOCX); fail("DTD must be rejected") }
            catch (_: Exception) { /* Expected: no external entity access. */ }
        } finally { file.delete() }
        val bad = archive("docx", "unrelated.xml" to "<test/>")
        try {
            try { OfficePreview.read(bad, DocumentFormat.DOCX); fail("Missing content must be rejected") }
            catch (_: Exception) { }
        } finally { bad.delete() }
    }

    @Test fun officeIntentKeepsOriginalForSharingAndSurvivesRotation() {
        val file = word()
        val preferences = context.getSharedPreferences("recent_pdfs", 0)
        val recents = preferences.getString("items", null)
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                setDataAndType(uri, DocumentFormat.DOCX.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }).use { scenario ->
                val deadline = SystemClock.uptimeMillis() + 15000
                var ready = false
                while (!ready && SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity { ready = ViewModelProvider(it)[ReaderSession::class.java].source != null }
                    if (!ready) SystemClock.sleep(50)
                }
                assertTrue("Office intent did not open", ready)
                scenario.recreate()
                scenario.onActivity {
                    val session = ViewModelProvider(it)[ReaderSession::class.java]
                    assertEquals(DocumentFormat.DOCX, session.format)
                    assertNotEquals(session.originalFile, session.source)
                    assertArrayEquals(file.readBytes(), checkNotNull(session.originalFile).readBytes())
                    assertTrue(checkNotNull(session.source).exists())
                }
            }
        } finally { preferences.edit().putString("items", recents).commit(); file.delete() }
    }
}
