package com.sahed.document_viewer

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Choreographer
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sahed.document_viewer.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var session: ReaderSession
    private lateinit var recentStore: RecentStore
    private lateinit var recentAdapter: RecentAdapter
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private var busy = false
    private var pageLoading = false
    private var pageJob: Job? = null
    private var pageRequest = 0
    private var homeIcon: android.graphics.drawable.Drawable? = null
    private var heartPulse: android.animation.ValueAnimator? = null
    private var resumed = false
    private var pageAdapter: PdfPageAdapter? = null

    private val openPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) { /* Some providers only grant a temporary permission. */ }
            confirmDiscard { openDocument(uri) }
        }
    }

    private val chooseDefaultPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) { /* A temporary grant is enough to open this document. */ }
            try {
                // The normal resolver, not createChooser, exposes Android's Always button.
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (_: android.content.ActivityNotFoundException) {
                message("Open a PDF from Files, choose Document Viewer, then tap Always.")
            }
        }
    }

    private val saveCopy = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val source = session.pendingExport
        if (uri != null && source != null) {
            lifecycleScope.launch {
                setBusy(true)
                try {
                    withContext(Dispatchers.IO) {
                        val output = contentResolver.openOutputStream(uri) ?: error("No output stream")
                        output.use { out -> source.inputStream().use { it.copyTo(out) } }
                    }
                    session.dirty = false
                    message("Marked-up copy saved. Original unchanged.")
                } catch (_: Exception) { message("Could not save the copy. Your marks are still here.") }
                finally { setBusy(false) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 35) enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            // API 36 enforces edge-to-edge. Keep controls clear of system bars and cutouts.
            ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
                val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                insets
            }
            ViewCompat.requestApplyInsets(binding.root)
        }
        if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            binding.controls.orientation = android.widget.LinearLayout.HORIZONTAL
            binding.markupTools.layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            binding.pageIndicator.layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            androidx.core.view.WindowCompat.getInsetsController(window, binding.root)
                .isAppearanceLightNavigationBars = false
        }
        // This toolbar owns its XML menu. Registering it as an ActionBar would clear that menu.
        val logoSize = (32 * resources.displayMetrics.density).toInt()
        val logo = android.graphics.BitmapFactory.decodeResource(resources, R.drawable.logo_rounded)
        binding.toolbar.navigationIcon = android.graphics.drawable.BitmapDrawable(resources,
            Bitmap.createScaledBitmap(logo, logoSize, logoSize, true))
        homeIcon = binding.toolbar.navigationIcon
        val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        binding.versionLabel.text = getString(R.string.footer_version, version)
        binding.copyrightLabel.text = getString(R.string.footer_copyright, java.util.Calendar.getInstance().get(java.util.Calendar.YEAR))
        binding.authorLink.setOnClickListener { openWebsite("https://sahedalomsumit.com") }
        binding.toolbar.setNavigationOnClickListener {
            if (!busy) confirmDiscard { clearDocument(); showHome() }
        }
        session = ViewModelProvider(this)[ReaderSession::class.java]
        recentStore = RecentStore(this)
        recentAdapter = RecentAdapter(recentStore.list()) { if (!busy) confirmDiscard { openDocument(Uri.parse(it.uri)) } }
        binding.recentList.layoutManager = LinearLayoutManager(this)
        binding.recentList.adapter = recentAdapter
        binding.recentList.itemAnimator = null
        binding.pdfPages.layoutManager = LinearLayoutManager(this)
        binding.pdfPages.itemAnimator = null
        binding.pdfPages.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(view: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                val center = view.findChildViewUnder(view.width / 2f, view.height / 2f)
                val index = center?.let(view::getChildAdapterPosition)
                    ?: (view.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
                if (index >= 0) updateCurrentPage(index)
            }
        })
        binding.continueCard.setOnClickListener {
            recentStore.list().firstOrNull()?.let { item ->
                if (!busy) confirmDiscard { openDocument(Uri.parse(item.uri)) }
            }
        }
        listOf(binding.openButton, binding.readTool,
            binding.penTool, binding.textTool, binding.undoTool).forEach(DesignSystem::bouncyPress)
        binding.openButton.setOnClickListener { if (!busy) openPdf.launch(DocumentFormat.mimeTypes) }
        binding.toolbar.setOnMenuItemClickListener {
            if (busy) return@setOnMenuItemClickListener true
            when (it.itemId) {
                R.id.action_open -> openPdf.launch(DocumentFormat.mimeTypes)
                R.id.action_home -> confirmDiscard { clearDocument(); showHome() }
                R.id.action_jump -> jumpToPage()
                R.id.action_fit -> currentPageView()?.fitToView()
                R.id.action_share -> shareOriginal()
                R.id.action_export -> confirmExport()
                R.id.action_more_apps -> openWebsite("https://play.google.com/store/apps/dev?id=7428141351679449484")
                R.id.action_default -> askDefaultViewer()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        binding.pageIndicator.setOnClickListener { if (!busy) jumpToPage() }
        binding.readTool.setOnClickListener { selectTool(ZoomImageView.Tool.READ) }
        binding.penTool.setOnClickListener { selectTool(ZoomImageView.Tool.PEN) }
        binding.textTool.setOnClickListener { selectTool(ZoomImageView.Tool.TEXT) }
        binding.undoTool.setOnClickListener {
            val marks = session.markup[session.page]
            if (!busy && !marks.isNullOrEmpty()) {
                marks.removeAt(marks.lastIndex)
                currentPageView()?.invalidate()
                session.dirty = true
                updateControls()
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (busy) return
                if (session.source != null) confirmDiscard { clearDocument(); showHome() }
                else finish()
            }
        })
        val source = session.source
        if (source != null && source.exists()) {
            try { attachRenderer(source); showReader(); showPage(session.page) }
            catch (_: Exception) { clearDocument(); showHome(); message(getString(R.string.cannot_open)) }
        } else if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            showHome()
            openDocument(intent.data!!)
        } else showHome()
        handleScreenshotRequest()
        val preferences = getSharedPreferences("onboarding", MODE_PRIVATE)
        removeLegacyWelcome()
        if (savedInstanceState == null && intent?.action != Intent.ACTION_VIEW && !isDefaultPdfViewer() && !preferences.getBoolean("default_prompt_seen", false)) {
            preferences.edit().putBoolean("default_prompt_seen", true).apply()
            askDefaultViewer()
        }
    }

    private fun openWebsite(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: android.content.ActivityNotFoundException) { message("No app is available to open this link.") }
    }

    private fun askDefaultViewer() {
        val dialog = dialogBuilder().setTitle(R.string.default_title).setMessage(R.string.default_message)
            .setNegativeButton(R.string.not_now, null)
            .setPositiveButton("Choose PDF") { _, _ -> chooseDefaultPdf.launch(arrayOf("application/pdf")) }.create()
        showAppDialog(dialog) {
            (dialog.getButton(AlertDialog.BUTTON_POSITIVE).parent as? androidx.appcompat.widget.ButtonBarLayout)
                ?.setAllowStacking(false)
        }
    }

    internal fun isDefaultPdfViewer(): Boolean {
        return resolvedPdfViewerPackage() == packageName
    }

    private fun resolvedPdfViewerPackage(): String? {
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2Fdocument.pdf")
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
            .addCategory(Intent.CATEGORY_DEFAULT)
        return packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
    }

    private fun removeLegacyWelcome() {
        val legacy = File(cacheDir, "welcome.pdf")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", legacy).toString()
        recentStore.remove(uri)
        legacy.delete()
        recentAdapter.update(recentStore.list())
        updateRecentState()
    }

    private fun updateHeartPulse() {
        heartPulse?.cancel()
        heartPulse = null
        binding.creditsHeart.scaleX = 1f
        binding.creditsHeart.scaleY = 1f
        if (binding.emptyState.visibility != View.VISIBLE || !resumed) return
        if (android.os.Build.VERSION.SDK_INT >= 26 && !android.animation.ValueAnimator.areAnimatorsEnabled()) return
        if (android.os.Build.VERSION.SDK_INT < 26 && android.provider.Settings.Global.getFloat(contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f) return
        heartPulse = android.animation.ValueAnimator.ofFloat(1f, 1.16f, 1f).apply {
            duration = 1400
            repeatCount = android.animation.ValueAnimator.INFINITE
            addUpdateListener { binding.creditsHeart.scaleX = it.animatedValue as Float; binding.creditsHeart.scaleY = it.animatedValue as Float }
            start()
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        updateHeartPulse()
        binding.toolbar.menu.findItem(R.id.action_default)?.isVisible = !isDefaultPdfViewer()
    }

    override fun onPause() {
        resumed = false
        heartPulse?.cancel()
        heartPulse = null
        super.onPause()
    }

    private fun selectTool(tool: ZoomImageView.Tool, force: Boolean = false) {
        if (busy && !force) return
        pageAdapter?.tool = tool
        binding.toolHint.setText(when (tool) {
            ZoomImageView.Tool.READ -> R.string.reader_hint
            ZoomImageView.Tool.TEXT -> R.string.text_hint
            else -> R.string.draw_hint
        })
        listOf(binding.readTool, binding.penTool, binding.textTool).forEachIndexed { i, button ->
            val selected = i == tool.ordinal
            button.isSelected = selected
            button.contentDescription = "${button.text}${if (selected) ", selected" else ""}"
        }
    }

    private fun openDocument(uri: Uri) {
        if (busy) return
        lifecycleScope.launch {
            setBusy(true)
            var copied: File? = null
            var original: File? = null
            try {
                val name = withContext(Dispatchers.IO) { displayName(uri) }
                val format = withContext(Dispatchers.IO) {
                    DocumentFormat.from(name, contentResolver.getType(uri))
                        ?: error("Unsupported document format")
                }
                // A local copy also works with document providers whose descriptors cannot seek.
                withContext(Dispatchers.IO) {
                    val file = File.createTempFile("document-", ".${format.extension}", cacheDir)
                    original = file
                    val input = contentResolver.openInputStream(uri) ?: error("Unavailable document")
                    input.use { stream -> file.outputStream().use { out ->
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            total += count
                            require(format == DocumentFormat.PDF || total <= OfficePreview.MAX_INPUT_BYTES) {
                                "Office file is too large (maximum 32 MB)."
                            }
                            out.write(buffer, 0, count)
                        }
                    } }
                    copied = if (format == DocumentFormat.PDF) file else {
                        File.createTempFile("office-preview-", ".pdf", cacheDir).also {
                            copied = it
                            OfficePreview.convert(file, format, it)
                        }
                    }
                }
                // Validate before replacing the current document or its unsaved marks.
                withContext(Dispatchers.IO) {
                    ParcelFileDescriptor.open(copied, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                        PdfRenderer(descriptor).use { require(it.pageCount > 0) }
                    }
                }
                clearDocument()
                session.source = copied
                session.originalFile = original
                session.format = format
                session.title = name
                session.originalUri = uri.toString()
                attachRenderer(checkNotNull(copied))
                recentStore.add(name, uri)
                recentAdapter.update(recentStore.list())
                updateRecentState()
                showReader()
                if (format != DocumentFormat.PDF) message(getString(R.string.office_preview_notice))
                val rememberedPage = recentStore.list().find { it.uri == uri.toString() }?.page ?: 0
                showPage(rememberedPage.coerceIn(0, (pdfRenderer?.pageCount ?: 1) - 1))
            } catch (e: CancellationException) {
                if (copied != session.source) copied?.delete()
                if (original != session.originalFile) original?.delete()
                throw e
            } catch (_: Exception) {
                if (copied != session.source) copied?.delete()
                if (original != session.originalFile) original?.delete()
                message(getString(R.string.cannot_open))
            } finally { setBusy(false) }
        }
    }

    private fun attachRenderer(file: File) {
        closeRenderer()
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        try { pdfRenderer = PdfRenderer(descriptor); fileDescriptor = descriptor }
        catch (e: Exception) { descriptor.close(); throw e }
    }

    private fun showPage(index: Int) {
        val renderer = pdfRenderer ?: return
        if (index !in 0 until renderer.pageCount) return
        val source = session.source ?: return
        val count = renderer.pageCount
        val request = ++pageRequest
        pageJob?.cancel()
        pageJob = lifecycleScope.launch {
            pageLoading = true
            updateControls()
            try {
                if (pageAdapter == null) {
                    val ratios = withContext(Dispatchers.IO) {
                        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                            PdfRenderer(descriptor).use { pdf ->
                                (0 until count).map { page -> pdf.openPage(page).use { it.height.toFloat() / it.width } }
                            }
                        }
                    }
                    pageAdapter = PdfPageAdapter(session, ratios, lifecycleScope, {
                        session.dirty = true
                        updateControls()
                    }, ::addText) { message("This page could not be rendered.") }
                    binding.pdfPages.adapter = pageAdapter
                }
                (binding.pdfPages.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(index, 0)
                updateCurrentPage(index)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message("This page could not be rendered.") }
            finally {
                if (request == pageRequest) {
                    pageLoading = false
                    updateControls()
                }
            }
        }
    }

    private fun currentPageView() = pageAdapter?.currentView(binding.pdfPages, session.page)

    private fun updateCurrentPage(index: Int) {
        val count = pdfRenderer?.pageCount ?: return
        session.page = index
        binding.pageIndicator.text = getString(R.string.page_format, index + 1, count)
        session.originalUri?.let { recentStore.progress(it, index, count) }
        updateControls()
    }

    private fun addText(page: Int, x: Float, y: Float) {
        val input = EditText(this).apply {
            hint = "Enter text"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 6
            filters = arrayOf(android.text.InputFilter.LengthFilter(1000))
        }
        val container = android.widget.FrameLayout(this).apply {
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            addView(input)
        }
        val dialog = dialogBuilder().setTitle("Add text").setView(container)
            .setNegativeButton("Cancel", null).setPositiveButton("Add", null).create()
        showAppDialog(dialog) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = input.text.toString().trim()
                if (text.isEmpty()) input.error = "Enter some text"
                else { pageAdapter?.addText(page, x, y, text); dialog.dismiss() }
            }
            input.requestFocus()
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
    }

    private fun jumpToPage() {
        val count = pdfRenderer?.pageCount ?: return message("Open a document first.")
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "1–$count"
            setSingleLine()
            setText((session.page + 1).toString())
            selectAll()
        }
        val container = android.widget.FrameLayout(this).apply {
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            addView(input)
        }
        val dialog = dialogBuilder().setTitle("Go to page").setView(container)
            .setNegativeButton("Cancel", null).setPositiveButton("Go", null).create()
        showAppDialog(dialog) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val number = input.text.toString().toIntOrNull()
                if (number == null || number !in 1..count) input.error = "Enter a page from 1 to $count"
                else { showPage(number - 1); dialog.dismiss() }
            }
        }
    }

    private fun dialogBuilder() = MaterialAlertDialogBuilder(this)
        .setIcon(R.drawable.logo_rounded)
        .setBackground(android.graphics.drawable.GradientDrawable().apply {
            setColor(getColor(R.color.surface_2))
            cornerRadius = 20 * resources.displayMetrics.density
        })
        .setBackgroundInsetStart(0).setBackgroundInsetEnd(0)
        .setBackgroundInsetTop(0).setBackgroundInsetBottom(0)

    /** Same 92% / 600dp popup width and 20dp corners as Money Tracker. */
    private fun showAppDialog(dialog: AlertDialog, onShow: () -> Unit = {}) {
        dialog.setOnShowListener {
            val density = resources.displayMetrics.density
            val availableWidth = binding.root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            dialog.window?.setLayout(minOf((availableWidth * 0.92f).toInt(), (600 * density).toInt()),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            dialog.window?.setWindowAnimations(R.style.Animation_DocumentViewer_Dialog)
            listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)
                .mapNotNull { dialog.getButton(it) }.forEach(DesignSystem::bouncyPress)
            onShow()
        }
        dialog.show()
    }

    private fun confirmDiscard(action: () -> Unit) {
        if (!session.dirty) return action()
        val dialog = dialogBuilder().setTitle("Keep your marks?")
            .setMessage("Save a marked-up copy before leaving. Unsaved marks will be discarded.")
            .setPositiveButton("Save copy") { _, _ -> confirmExport() }
            .setNegativeButton("Discard") { _, _ -> action() }
            .setNeutralButton("Stay", null).create()
        showAppDialog(dialog)
    }

    private fun confirmExport() {
        if (session.source == null) return message("Open a document first.")
        val dialog = dialogBuilder().setTitle("Save marked-up copy")
            .setMessage("Creates a flattened PDF with your drawings and added text. Pages become images: searchable text, links, forms, and accessibility tags are not preserved. The original stays unchanged.")
            .setNegativeButton("Cancel", null).setPositiveButton("Create copy") { _, _ -> exportCopy() }.create()
        showAppDialog(dialog)
    }

    private fun exportCopy() {
        val source = session.source ?: return
        val snapshot = session.markup.mapValues { (_, strokes) -> strokes.map { stroke ->
            ZoomImageView.Stroke(stroke.points.map { android.graphics.PointF(it.x, it.y) }.toMutableList(), stroke.text)
        } }
        lifecycleScope.launch {
            setBusy(true)
            try {
                val output = withContext(Dispatchers.IO) {
                    val target = File.createTempFile("marked-", ".pdf", cacheDir)
                    try {
                        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                            PdfRenderer(descriptor).use { renderer ->
                                val result = PdfDocument()
                                try {
                                    for (index in 0 until renderer.pageCount) {
                                        renderer.openPage(index).use { page ->
                                            val scale = min(2f, 2000f / maxOf(page.width, page.height))
                                            val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1),
                                                (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                                            try {
                                                bitmap.eraseColor(Color.WHITE)
                                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                                ZoomImageView.drawStrokes(Canvas(bitmap), snapshot[index].orEmpty(), bitmap.width.toFloat(), bitmap.height.toFloat())
                                                val outPage = result.startPage(PdfDocument.PageInfo.Builder(page.width, page.height, index + 1).create())
                                                outPage.canvas.drawBitmap(bitmap, null, Rect(0, 0, page.width, page.height), null)
                                                result.finishPage(outPage)
                                            } finally { bitmap.recycle() }
                                        }
                                    }
                                    target.outputStream().use { result.writeTo(it) }
                                } finally { result.close() }
                            }
                        }
                        target
                    } catch (e: Exception) { target.delete(); throw e }
                }
                session.pendingExport?.delete()
                session.pendingExport = output
                saveCopy.launch(session.title.substringBeforeLast('.') + "-marked.pdf")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message("Could not create the copy. Your marks are still here.") }
            finally { setBusy(false) }
        }
    }

    private fun shareOriginal() {
        val file = session.originalFile ?: session.source ?: return message("Open a document first.")
        if (session.dirty) message("Sharing the original without marks. Use Save marked-up copy to include them.")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = session.format.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri(session.title, uri)
        }
        try { startActivity(Intent.createChooser(share, "Share document")) }
        catch (_: Exception) { message("No sharing app is available.") }
    }

    private fun showReader() {
        binding.emptyState.visibility = View.GONE
        updateHeartPulse()
        binding.viewerContainer.visibility = View.VISIBLE
        binding.controls.visibility = View.VISIBLE
        binding.markupTools.visibility = View.VISIBLE
        binding.toolHint.visibility = View.VISIBLE
        binding.toolbar.title = session.title
        binding.toolbar.subtitle = if (session.format == DocumentFormat.PDF) null else getString(R.string.office_preview)
        binding.toolbar.setNavigationIcon(R.drawable.ic_back)
        updateMenu(true)
        DesignSystem.entrance(binding.viewerContainer)
        selectTool(ZoomImageView.Tool.READ, force = true)
    }

    private fun showHome() {
        binding.emptyState.visibility = View.VISIBLE
        updateHeartPulse()
        binding.viewerContainer.visibility = View.GONE
        binding.controls.visibility = View.GONE
        binding.markupTools.visibility = View.GONE
        binding.toolHint.visibility = View.GONE
        binding.toolbar.title = getString(R.string.app_name)
        binding.toolbar.navigationIcon = homeIcon
        updateMenu(false)
        binding.toolbar.subtitle = null
        recentAdapter.update(recentStore.list())
        updateRecentState()
        listOf(binding.homeHeader, binding.heroCard, binding.recentEmpty, binding.recentList)
            .forEachIndexed { index, view -> DesignSystem.entrance(view, index) }
    }

    private fun updateRecentState() {
        val count = recentAdapter.itemCount
        binding.recentEmpty.visibility = if (count == 0) View.VISIBLE else View.GONE
        binding.recentCount.text = resources.getQuantityString(R.plurals.recent_count, count, count)
        val latest = recentStore.list().firstOrNull()
        binding.continueCard.visibility = if (latest == null) View.GONE else View.VISIBLE
        latest?.let {
            binding.continueTitle.text = it.name
            binding.continueDetail.text = if (it.pageCount > 0)
                getString(R.string.page_format, it.page + 1, it.pageCount) else "Pick up where you left off"
            binding.readingProgress.visibility = if (it.pageCount > 0) View.VISIBLE else View.GONE
            binding.readingProgress.progress = if (it.pageCount > 0) ((it.page + 1) * 100f / it.pageCount).toInt() else 0
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        updateControls()
    }

    private fun updateMenu(reader: Boolean) {
        binding.toolbar.menu.findItem(R.id.action_default)?.isVisible = !isDefaultPdfViewer()
        listOf(R.id.action_home, R.id.action_jump, R.id.action_fit, R.id.action_share, R.id.action_export)
            .forEach { binding.toolbar.menu.findItem(it)?.isVisible = reader }
    }

    private fun updateControls() {
        val working = busy || pageLoading
        binding.loadingProgress.visibility = if (working) View.VISIBLE else View.INVISIBLE
        binding.pdfPages.isEnabled = !working
        pageAdapter?.interactionEnabled = !working
        binding.openButton.isEnabled = !working
        binding.continueCard.isEnabled = !working
        binding.pageIndicator.isEnabled = !working
        listOf(binding.readTool, binding.penTool, binding.textTool).forEach { it.isEnabled = !working }
        binding.undoTool.isEnabled = !working && !session.markup[session.page].isNullOrEmpty()
        binding.toolbar.menu.let { menu -> for (i in 0 until menu.size()) menu.getItem(i).isEnabled = !working }
    }

    private fun displayName(uri: Uri): String {
        return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "Document"
    }

    private fun clearDocument() {
        pageRequest++
        pageJob?.cancel()
        pageAdapter?.dispose()
        binding.pdfPages.adapter = null
        pageAdapter = null
        pageLoading = false
        closeRenderer()
        // A shared cache file must remain available to its receiving app.
        if (session.source != session.originalFile) session.source?.delete()
        session.source = null
        session.originalFile = null
        session.format = DocumentFormat.PDF
        session.page = 0
        session.title = ""
        session.originalUri = null
        session.pages.clear()
        session.markup.clear()
        session.dirty = false
        updateControls()
    }

    private fun closeRenderer() {
        pdfRenderer?.close(); pdfRenderer = null
        fileDescriptor?.close(); fileDescriptor = null
    }

    private fun message(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun handleScreenshotRequest() {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val path = intent?.getStringExtra("screenshot") ?: intent?.getStringExtra("--screenshot") ?: return
        val requestedFile = File(path).canonicalFile
        // The exported viewer must never allow an Intent to overwrite preferences or other app data.
        if (!requestedFile.path.startsWith(cacheDir.canonicalPath + File.separator)) return
        val choreographer = Choreographer.getInstance()
        var frames = 0
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (isDestroyed || isFinishing) return
                if (++frames < 120 || busy) {
                    binding.root.invalidate()
                    choreographer.postFrameCallback(this)
                } else {
                    try {
                        val view = window.decorView.rootView
                        require(view.width > 0 && view.height > 0)
                        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                        try {
                            view.draw(Canvas(bitmap))
                            val target = File(path)
                            target.parentFile?.mkdirs()
                            val temporary = File(target.parentFile, target.name + ".partial")
                            FileOutputStream(temporary).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                            check(temporary.renameTo(target))
                        } finally { bitmap.recycle() }
                    } finally { finish() }
                }
            }
        }
        choreographer.postFrameCallback(callback)
    }

    override fun onDestroy() {
        pageAdapter?.dispose()
        closeRenderer()
        super.onDestroy()
    }
}
