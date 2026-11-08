package com.sahed.document_viewer

import android.graphics.PointF
import android.view.ViewGroup
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Only visible pages hold views/bitmaps; the complete document is a continuous list. */
class PdfPageAdapter(
    private val session: ReaderSession,
    private val ratios: List<Float>,
    private val scope: LifecycleCoroutineScope,
    private val changed: () -> Unit,
    private val requestText: (Int, Float, Float) -> Unit,
    private val renderFailed: () -> Unit
) : RecyclerView.Adapter<PdfPageAdapter.Holder>() {
    var tool = ZoomImageView.Tool.READ
        set(value) { field = value; holders.forEach { it.view.tool = value } }
    var interactionEnabled = true
        set(value) { field = value; holders.forEach { it.view.isEnabled = value } }
    private val holders = mutableSetOf<Holder>()
    private var list: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) { list = recyclerView }
    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) { list = null }

    class Holder(val view: ZoomImageView) : RecyclerView.ViewHolder(view) {
        var job: Job? = null
    }

    override fun getItemCount() = ratios.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = ZoomImageView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                bottomMargin = (8 * resources.displayMetrics.density).toInt()
            }
        }
        return Holder(view).also { holders.add(it) }
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.job?.cancel()
        val view = holder.view
        val width = list?.width?.takeIf { it > 0 }
            ?: view.resources.displayMetrics.widthPixels
        view.layoutParams = view.layoutParams.apply { height = (width * ratios[position]).toInt().coerceAtLeast(1) }
        view.setImageDrawable(null)
        view.tool = tool
        view.isEnabled = interactionEnabled
        view.strokes = session.markup.getOrPut(position) { mutableListOf() }
        view.contentDescription = "${session.title}, page ${position + 1} of $itemCount"
        view.onMarkupChanged = changed
        view.onTextRequested = { x, y -> requestText(position, x, y) }
        val source = session.source ?: return
        holder.job = scope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) { session.pages.render(source, position) }
                view.setImageBitmap(bitmap)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { renderFailed() }
        }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.job?.cancel()
        holder.view.setImageDrawable(null)
        holder.view.strokes = mutableListOf()
        holder.view.onTextRequested = null
        holder.view.onMarkupChanged = null
    }

    fun currentView(list: RecyclerView, page: Int): ZoomImageView? =
        (list.findViewHolderForAdapterPosition(page) as? Holder)?.view

    fun addText(page: Int, x: Float, y: Float, text: String) {
        session.markup.getOrPut(page) { mutableListOf() }.add(
            ZoomImageView.Stroke(mutableListOf(PointF(x, y)), text))
        holders.filter { it.bindingAdapterPosition == page }.forEach { it.view.invalidate() }
        changed()
    }

    fun dispose() {
        holders.forEach { it.job?.cancel(); it.view.setImageDrawable(null) }
        holders.clear()
    }
}
