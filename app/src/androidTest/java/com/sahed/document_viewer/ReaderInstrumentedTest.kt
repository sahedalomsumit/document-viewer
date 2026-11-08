package com.sahed.document_viewer

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.KeyEvent
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReaderInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var originalRecents: String? = null
    private var originalPromptSeen = false

    @Before fun preserveLibrary() {
        originalRecents = context.getSharedPreferences("recent_pdfs", 0).getString("items", null)
        val preferences = context.getSharedPreferences("onboarding", 0)
        originalPromptSeen = preferences.getBoolean("default_prompt_seen", false)
        preferences.edit().putBoolean("default_prompt_seen", true).commit()
    }

    @After fun restoreLibrary() {
        context.getSharedPreferences("recent_pdfs", 0).edit().putString("items", originalRecents).commit()
        context.getSharedPreferences("onboarding", 0).edit().putBoolean("default_prompt_seen", originalPromptSeen).commit()
    }

    private fun fixture(): File {
        val file = File(context.cacheDir, "reader-test-${System.nanoTime()}.pdf")
        val document = PdfDocument()
        try {
            for (index in 1..3) {
                val page = document.startPage(PdfDocument.PageInfo.Builder(600, 800, index).create())
                page.canvas.drawColor(Color.WHITE)
                val paint = Paint().apply { color = Color.rgb(20, 80, 60); textSize = 32f }
                page.canvas.drawText("Reading room / $index", 48f, 100f, paint)
                paint.textSize = 18f
                page.canvas.drawText("A three-page document for reader regression tests.", 48f, 150f, paint)
                document.finishPage(page)
            }
            file.outputStream().use(document::writeTo)
        } finally { document.close() }
        return file
    }

    private fun open(file: File): ActivityScenario<MainActivity> {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return ActivityScenario.launch(Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    private fun await(scenario: ActivityScenario<MainActivity>, condition: (MainActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            scenario.onActivity { ready = condition(it) }
            if (ready) return
            SystemClock.sleep(50)
        }
        fail("Reader did not reach expected state within 15 seconds")
    }

    private fun awaitPage(scenario: ActivityScenario<MainActivity>, number: Int) = await(scenario) {
        it.findViewById<TextView>(R.id.pageIndicator).text.toString() == "Page $number of 3" &&
            it.findViewById<View>(R.id.loadingProgress).visibility != View.VISIBLE && pageView(it)?.drawable != null
    }

    private fun pageView(activity: MainActivity): ZoomImageView? {
        val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.pdfPages)
        val page = ViewModelProvider(activity)[ReaderSession::class.java].page
        return (list.findViewHolderForAdapterPosition(page) as? PdfPageAdapter.Holder)?.view
    }

    private fun awaitWindow(condition: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            if (root != null && condition(root)) return root
            SystemClock.sleep(100)
        }
        throw AssertionError("Expected window did not appear")
    }

    private fun captureLayout(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(500) // Allow entrance animations to finish before visual inspection.
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    @Test fun phoneLayoutPortraitAndLandscape() {
        ActivityScenario.launch(MainActivity::class.java).use {
            captureLayout("layout-home")
        }
        open(fixture()).use { scenario ->
            awaitPage(scenario, 1)
            scenario.onActivity { assertToolsFillCard(it) }
            captureLayout("layout-reader-portrait")
            scenario.onActivity {
                it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            await(scenario) { it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            awaitPage(scenario, 1)
            scenario.onActivity { activity ->
                val controls = activity.findViewById<android.widget.LinearLayout>(R.id.controls)
                val tools = activity.findViewById<View>(R.id.markupTools)
                val counter = activity.findViewById<View>(R.id.pageIndicator)
                assertEquals(android.widget.LinearLayout.HORIZONTAL, controls.orientation)
                assertToolsFillCard(activity)
                assertTrue("Page counter should sit beside tools", counter.left >= tools.right)
                assertTrue("Reader should retain space in landscape",
                    activity.findViewById<View>(R.id.viewerContainer).height > controls.height)
            }
            captureLayout("layout-reader-landscape")
            scenario.onActivity {
                it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            await(scenario) { it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
        }
    }

    @Test fun readerToolsSupportLargeTextOnNarrowScreens() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val config = android.content.res.Configuration(activity.resources.configuration).apply {
                    fontScale = 1.6f
                }
                val themed = android.view.ContextThemeWrapper(
                    activity.createConfigurationContext(config), R.style.Theme_DocumentViewer)
                val root = android.view.LayoutInflater.from(themed).inflate(R.layout.activity_main, null)
                val tools = root.findViewById<android.widget.HorizontalScrollView>(R.id.markupTools)
                val controls = root.findViewById<View>(R.id.controls)
                controls.visibility = View.VISIBLE
                tools.visibility = View.VISIBLE
                val density = themed.resources.displayMetrics.density
                root.measure(View.MeasureSpec.makeMeasureSpec((320 * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((640 * density).toInt(), View.MeasureSpec.EXACTLY))
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                listOf(R.id.readTool, R.id.penTool, R.id.textTool, R.id.undoTool).forEach { id ->
                    val button = root.findViewById<TextView>(id)
                    assertTrue("Tool needs a 48dp touch target", button.height >= (48 * density).toInt())
                    assertTrue("Large tool text must not be clipped",
                        button.height - button.compoundPaddingTop - button.compoundPaddingBottom >= button.layout.height)
                }
                val row = tools.getChildAt(0)
                if (row.width > tools.width) {
                    assertTrue("Overflowing tools must be scrollable", tools.canScrollHorizontally(1))
                    tools.isSmoothScrollingEnabled = false
                    tools.fullScroll(View.FOCUS_RIGHT)
                    val undo = root.findViewById<View>(R.id.undoTool)
                    assertTrue("Undo must be reachable", undo.right - tools.scrollX <= tools.width)
                }
            }
        }
    }

    private fun assertToolsFillCard(activity: MainActivity) {
        val tools = activity.findViewById<android.widget.HorizontalScrollView>(R.id.markupTools)
        val row = tools.getChildAt(0) as android.widget.LinearLayout
        val buttons = listOf(R.id.readTool, R.id.penTool, R.id.textTool, R.id.undoTool)
            .map { activity.findViewById<View>(it) }
        assertEquals("Buttons should start at the card padding", row.paddingLeft, buttons.first().left)
        assertEquals("Buttons should fill through the card padding", row.width - row.paddingRight, buttons.last().right)
        buttons.zipWithNext().forEach { (left, right) ->
            assertEquals("No unused space between tools", left.right, right.left)
        }
        assertTrue("Tool row must fill the card", row.width >= tools.width)
    }

    @Test fun readerToolsFillCardWhenTheyFit() {
        open(fixture()).use { scenario ->
            awaitPage(scenario, 1)
            scenario.onActivity { activity ->
                assertToolsFillCard(activity)
                val tools = activity.findViewById<android.widget.HorizontalScrollView>(R.id.markupTools)
                val row = tools.getChildAt(0) as android.widget.LinearLayout
                assertEquals(android.view.Gravity.CENTER, row.gravity)
                val first = activity.findViewById<View>(R.id.readTool)
                val last = activity.findViewById<View>(R.id.undoTool)
                val groupWidth = last.right - first.left
                if (groupWidth <= tools.width - row.paddingLeft - row.paddingRight) {
                    val groupCenter = row.left + (first.left + last.right) / 2f - tools.scrollX
                    assertEquals(tools.width / 2f, groupCenter, 1f)
                }
            }
        }
    }

    @Test fun homeHidesUnavailableActions() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                assertEquals(View.VISIBLE, it.findViewById<View>(R.id.emptyState).visibility)
                val toolbar = it.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
                assertFalse(toolbar.menu.findItem(R.id.action_export).isVisible)
                assertTrue(it.findViewById<View>(R.id.openButton).isEnabled)
            }
        }
    }

    @Test fun footerShowsActualVersionAndRoundedLogo() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
                assertEquals("Version $version", it.findViewById<TextView>(R.id.versionLabel).text.toString())
                assertTrue(it.findViewById<TextView>(R.id.copyrightLabel).text.contains("Sahed Alom Sumit"))
                val logo = android.graphics.BitmapFactory.decodeResource(it.resources, R.drawable.logo_rounded)
                assertEquals(0, Color.alpha(logo.getPixel(0, 0)))
                assertEquals(255, Color.alpha(logo.getPixel(logo.width / 2, logo.height / 2)))
                logo.recycle()
            }
        }
    }

    @Test fun developerLinksOpenCorrectDestinations() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val destinations = mutableListOf<String>()
        val monitor = object : android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): android.app.Instrumentation.ActivityResult? {
                if (intent.action == Intent.ACTION_VIEW && intent.data?.scheme == "https") {
                    destinations.add(intent.data.toString())
                    return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null)
                }
                return null
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    it.findViewById<View>(R.id.authorLink).performClick()
                    val toolbar = it.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
                    assertTrue(toolbar.menu.findItem(R.id.action_more_apps).isVisible)
                    assertEquals(!it.isDefaultPdfViewer(), toolbar.menu.findItem(R.id.action_default).isVisible)
                    toolbar.menu.performIdentifierAction(R.id.action_more_apps, 0)
                }
                assertEquals(listOf("https://sahedalomsumit.com", "https://play.google.com/store/apps/dev?id=7428141351679449484"), destinations)
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun defaultPromptAppearsOnceAndCanBeReopenedFromMenu() {
        context.getSharedPreferences("onboarding", 0).edit().putBoolean("default_prompt_seen", false).commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val root = awaitWindow { it.findAccessibilityNodeInfosByText("Your everyday PDF viewer").isNotEmpty() }
            assertTrue(root.findAccessibilityNodeInfosByText("Not now").first().performAction(AccessibilityNodeInfo.ACTION_CLICK))
            scenario.recreate()
            scenario.onActivity {
                assertTrue(context.getSharedPreferences("onboarding", 0).getBoolean("default_prompt_seen", false))
                it.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
                    .menu.performIdentifierAction(R.id.action_default, 0)
            }
            val reopened = awaitWindow { it.findAccessibilityNodeInfosByText("Choose PDF").isNotEmpty() }
            assertTrue(reopened.findAccessibilityNodeInfosByText("Not now").first().performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
    }

    @Test fun heartPulseStopsInBackgroundAndRestartsOnReturn() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val field = MainActivity::class.java.getDeclaredField("heartPulse").apply { isAccessible = true }
            scenario.onActivity { assertNotNull(field.get(it)) }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.onActivity { assertNull(field.get(it)) }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            scenario.onActivity { assertNotNull(field.get(it)) }
        }
    }

    @Test fun defaultViewerMenuIsRecheckedOnReturn() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                val menu = it.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).menu
                menu.findItem(R.id.action_default).isVisible = it.isDefaultPdfViewer()
            }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            scenario.onActivity {
                val menu = it.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).menu
                assertEquals(!it.isDefaultPdfViewer(), menu.findItem(R.id.action_default).isVisible)
            }
        }
    }

    @Test fun pageNavigationAndResume() {
        val file = fixture()
        open(file).use { scenario ->
            awaitPage(scenario, 1)
            scenario.onActivity {
                val list = it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.pdfPages)
                list.scrollBy(0, pageView(it)!!.height + 24)
            }
            awaitPage(scenario, 2)
            scenario.onActivity {
                val list = it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.pdfPages)
                list.scrollBy(0, 100000)
            }
            awaitPage(scenario, 3)
            scenario.onActivity {
                assertFalse(it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.pdfPages).canScrollVertically(1))
            }
        }
        open(file).use { scenario -> awaitPage(scenario, 3) }
        file.delete()
    }

    @Test fun marksSurviveRecreationAndUndo() {
        val file = fixture()
        open(file).use { scenario ->
            awaitPage(scenario, 1)
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.penTool).performClick()
                val view = pageView(activity)!!
                val now = SystemClock.uptimeMillis()
                for ((action, x) in listOf(MotionEvent.ACTION_DOWN to 0.4f, MotionEvent.ACTION_MOVE to 0.6f, MotionEvent.ACTION_UP to 0.6f)) {
                    val event = MotionEvent.obtain(now, now + 30, action, view.width * x, view.height * 0.5f, 0)
                    view.onTouchEvent(event)
                    event.recycle()
                }
                assertEquals(1, view.strokes.size)
            }
            scenario.recreate()
            awaitPage(scenario, 1)
            scenario.onActivity {
                assertEquals(1, pageView(it)!!.strokes.size)
                it.findViewById<View>(R.id.undoTool).performClick()
                assertTrue(pageView(it)!!.strokes.isEmpty())
                assertFalse(it.findViewById<View>(R.id.undoTool).isEnabled)
            }
        }
        file.delete()
    }

    @Test fun invalidPdfReturnsToUsableHome() {
        val file = File(context.cacheDir, "invalid-test.pdf").apply { writeText("not a PDF") }
        open(file).use { scenario ->
            await(scenario) { it.findViewById<View>(R.id.openButton).isEnabled }
            scenario.onActivity { assertEquals(View.VISIBLE, it.findViewById<View>(R.id.emptyState).visibility) }
        }
        file.delete()
    }

    @Test fun continuousScrollChangesPageWithoutNavigationButtons() {
        val file = fixture()
        open(file).use { scenario ->
            awaitPage(scenario, 1)
            scenario.onActivity {
                val list = it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.pdfPages)
                assertEquals(3, list.adapter!!.itemCount)
                assertTrue(list.canScrollVertically(1))
                list.scrollBy(0, pageView(it)!!.height + 24)
            }
            awaitPage(scenario, 2)
            scenario.onActivity {
                it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.pdfPages).scrollBy(0, -100000)
            }
            awaitPage(scenario, 1)
        }
        file.delete()
    }

    @Test fun typedTextSurvivesRecreationAndCanBeUndone() {
        val file = fixture()
        open(file).use { scenario ->
            awaitPage(scenario, 1)
            scenario.onActivity {
                it.findViewById<View>(R.id.textTool).performClick()
                assertEquals(ZoomImageView.Tool.TEXT, pageView(it)!!.tool)
                pageView(it)!!.onTextRequested!!.invoke(0.2f, 0.5f)
            }
            val root = awaitWindow { it.findAccessibilityNodeInfosByText("Add text").isNotEmpty() }
            fun findInput(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (node.className?.toString()?.endsWith("EditText") == true) return node
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    findInput(child)?.let { return it }
                }
                return null
            }
            val input = requireNotNull(findInput(root)) { "Text annotation dialog must contain an editor" }
            val args = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Site inspection notes")
            }
            assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
            assertTrue(root.findAccessibilityNodeInfosByText("Add").last().performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await(scenario) { pageView(it)?.strokes?.firstOrNull()?.text == "Site inspection notes" }
            scenario.recreate()
            awaitPage(scenario, 1)
            scenario.onActivity {
                assertEquals("Site inspection notes", pageView(it)!!.strokes.single().text)
                assertTrue(ViewModelProvider(it)[ReaderSession::class.java].dirty)
                it.findViewById<View>(R.id.undoTool).performClick()
                assertTrue(pageView(it)!!.strokes.isEmpty())
            }
        }
        file.delete()
    }

    @Test fun readGestureScrollsTheDocumentRatherThanDraggingOnePage() {
        val file = fixture()
        open(file).use { scenario ->
            awaitPage(scenario, 1)
            scenario.onActivity {
                val list = it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.pdfPages)
                val now = SystemClock.uptimeMillis()
                for (step in 0..12) {
                    val action = when (step) {
                        0 -> MotionEvent.ACTION_DOWN
                        12 -> MotionEvent.ACTION_CANCEL // End without fling so the assertion is deterministic.
                        else -> MotionEvent.ACTION_MOVE
                    }
                    MotionEvent.obtain(now, now + step * 30L, action,
                        list.width / 2f, list.height * (0.85f - step * 0.05f), 0).let { event ->
                        list.dispatchTouchEvent(event)
                        event.recycle()
                    }
                }
                assertTrue("A read-mode drag must scroll the page list", list.computeVerticalScrollOffset() > 0)
                assertTrue(pageView(it)?.strokes.isNullOrEmpty())
            }
        }
        file.delete()
    }

    @Test fun darkPaletteIsIdenticalForDayAndNightConfigurations() {
        for (mode in listOf(android.content.res.Configuration.UI_MODE_NIGHT_NO, android.content.res.Configuration.UI_MODE_NIGHT_YES)) {
            val config = android.content.res.Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
            }
            val themed = context.createConfigurationContext(config)
            assertEquals(Color.rgb(10, 15, 13), themed.getColor(R.color.background))
            assertEquals(Color.WHITE, themed.getColor(R.color.text_primary))
        }
    }

    @Test fun legacyWelcomePdfAndItsRecentEntryAreRemoved() {
        val legacy = File(context.cacheDir, "welcome.pdf").apply { writeText("old onboarding sample") }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", legacy)
        RecentStore(context).add("welcome.pdf", uri)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                assertFalse(legacy.exists())
                assertFalse(RecentStore(context).list().any { it.uri == uri.toString() })
            }
        }
    }

    @Test fun cacheReusesBitmapsAndClearsSafely() {
        val file = fixture()
        val cache = PageCache()
        val first = cache.render(file, 0)
        assertSame(first, cache.render(file, 0))
        assertTrue(first.width <= 2000 && first.height <= 2000)
        cache.clear()
        assertFalse(first.isRecycled) // A displayed bitmap must never be recycled on eviction.
        assertNotSame(first, cache.render(file, 0))
        file.delete()
    }

    @Test fun doubleTapZoomsAndFitRestoresPage() {
        val file = fixture()
        open(file).use { scenario ->
            awaitPage(scenario, 1)
            scenario.onActivity { activity ->
                val view = pageView(activity)!!
                view.fitToView()
                val before = FloatArray(9).also { view.imageMatrix.getValues(it) }
                val now = SystemClock.uptimeMillis()
                for ((offset, action) in listOf(0L to MotionEvent.ACTION_DOWN, 20L to MotionEvent.ACTION_UP,
                    100L to MotionEvent.ACTION_DOWN, 120L to MotionEvent.ACTION_UP)) {
                    MotionEvent.obtain(now, now + offset, action, view.width / 2f, view.height / 2f, 0).let {
                        view.onTouchEvent(it)
                        it.recycle()
                    }
                }
                val zoomed = FloatArray(9).also { view.imageMatrix.getValues(it) }
                assertTrue(zoomed[0] > before[0] * 2)
                view.fitToView()
                val fitted = FloatArray(9).also { view.imageMatrix.getValues(it) }
                assertArrayEquals(before, fitted, 0.001f)
            }
        }
        file.delete()
    }

    @Test fun pageCannotBeDraggedOffScreen() {
        val file = fixture()
        open(file).use { scenario ->
            awaitPage(scenario, 1)
            scenario.onActivity { activity ->
                val view = pageView(activity)!!
                view.fitToView()
                val before = FloatArray(9).also { view.imageMatrix.getValues(it) }
                val now = SystemClock.uptimeMillis()
                for ((action, x) in listOf(MotionEvent.ACTION_DOWN to 100f, MotionEvent.ACTION_MOVE to 10000f,
                    MotionEvent.ACTION_UP to 10000f)) {
                    MotionEvent.obtain(now, now + 50, action, x, 100f, 0).let {
                        view.onTouchEvent(it)
                        it.recycle()
                    }
                }
                val after = FloatArray(9).also { view.imageMatrix.getValues(it) }
                assertArrayEquals(before, after, 0.001f)
            }
        }
        file.delete()
    }

    @Test fun annotationsRenderAndClipToPage() {
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val strokes = listOf(ZoomImageView.Stroke(mutableListOf(PointF(0.2f, 0.5f), PointF(0.8f, 0.5f))))
        ZoomImageView.drawStrokes(Canvas(bitmap), strokes, 200f, 200f)
        assertNotEquals(Color.WHITE, bitmap.getPixel(100, 100))
        assertEquals(Color.WHITE, bitmap.getPixel(0, 0))
        bitmap.recycle()
    }

    @Test fun exportExplainsFlatteningBeforeProceeding() {
        val file = fixture()
        open(file).use { scenario ->
            awaitPage(scenario, 1)
            scenario.onActivity {
                it.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
                    .menu.performIdentifierAction(R.id.action_export, 0)
            }
            val root = awaitWindow { it.findAccessibilityNodeInfosByText("Pages become images").isNotEmpty() }
            assertTrue(root.findAccessibilityNodeInfosByText("Pages become images").isNotEmpty())
            val cancel = root.findAccessibilityNodeInfosByText("Cancel").first()
            assertTrue(cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitPage(scenario, 1)
        }
        file.delete()
    }

    @Test fun exportCreatesThreeReadablePagesWithMarksAndLeavesOriginalUnchanged() {
        val file = fixture()
        val original = file.readBytes()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        open(file).use { scenario ->
            awaitPage(scenario, 1)
            lateinit var session: ReaderSession
            scenario.onActivity {
                session = ViewModelProvider(it)[ReaderSession::class.java]
                session.markup.getValue(0).add(ZoomImageView.Stroke(
                    mutableListOf(PointF(0.2f, 0.5f), PointF(0.8f, 0.5f))))
                session.markup.getValue(0).add(ZoomImageView.Stroke(
                    mutableListOf(PointF(0.2f, 0.7f)), "Exported note"))
                it.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
                    .menu.performIdentifierAction(R.id.action_export, 0)
            }
            val root = awaitWindow { it.findAccessibilityNodeInfosByText("Create copy").isNotEmpty() }
            assertTrue(root.findAccessibilityNodeInfosByText("Create copy")
                .first().performAction(AccessibilityNodeInfo.ACTION_CLICK))
            var exported: File? = null
            val deadline = SystemClock.uptimeMillis() + 15000
            while (exported == null && SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync { exported = session.pendingExport }
                SystemClock.sleep(50)
            }
            assertNotNull("Export did not finish", exported)
            ParcelFileDescriptor.open(exported!!, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { assertEquals(3, it.pageCount) }
            }
            val markedPage = PageCache().render(exported!!, 0)
            assertNotEquals(Color.WHITE, markedPage.getPixel(markedPage.width / 2, markedPage.height / 2))
            var textPixels = 0
            for (y in (markedPage.height * 0.65f).toInt() until (markedPage.height * 0.71f).toInt()) {
                for (x in (markedPage.width * 0.2f).toInt() until (markedPage.width * 0.6f).toInt()) {
                    if (markedPage.getPixel(x, y) != Color.WHITE) textPixels++
                }
            }
            assertTrue("Typed text must appear in the exported PDF", textPixels > 20)
            assertArrayEquals(original, file.readBytes())
            // Dismiss the system save picker without creating any user-visible test file.
            awaitWindow { it.packageName.toString().contains("documentsui") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitWindow { it.packageName.toString() == context.packageName }
        }
        file.delete()
    }
}
