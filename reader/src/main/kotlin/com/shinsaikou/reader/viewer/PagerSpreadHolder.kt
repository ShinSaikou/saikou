package com.shinsaikou.reader.viewer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.view.LayoutInflater
import androidx.core.view.isVisible
import com.shinsaikou.image.ImageUtil
import com.shinsaikou.reader.databinding.ReaderErrorBinding
import com.shinsaikou.reader.pipeline.PageState
import com.shinsaikou.reader.pipeline.TargetSize
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.R2LPagerViewer
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat

/**
 * The paged viewer's view of a [PageSpread], the Experimental two-page view (ruling R165 item 2).
 *
 * It loads both pages as `PagerPageHolder` loads one, and once both are Ready draws them side by side
 * as one bitmap ([SpreadImage]), so pinch and double-tap zoom act on the spread as one picture. In
 * right to left the earlier page is on the right. The landscape zoom is off: the spread is wide by
 * design, and zooming into it would hide a page.
 *
 * A page that turns out wide, or that the decoder cannot read, cannot share the screen: the viewer
 * is told ([PagerViewer.onSpreadPageWide]) and lays the pages out again, and this view goes with the
 * spread. A page that fails to load shows the error, and Retry retries the pages that failed.
 */
@SuppressLint("ViewConstructor")
class PagerSpreadHolder(
    readerThemedContext: Context,
    val viewer: PagerViewer,
    val spread: PageSpread,
) : ReaderPageImageView(readerThemedContext), ViewPagerAdapter.PositionableView {

    override val item: Any
        get() = spread

    private var progressIndicator: ReaderProgressIndicator? = null

    private var errorLayout: ReaderErrorBinding? = null

    private val scope = MainScope()

    private var loadJob: Job? = null

    init {
        loadJob = scope.launch { loadPagesAndProcessStatus() }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        loadJob?.cancel()
        loadJob = null
    }

    private fun initProgressIndicator() {
        if (progressIndicator == null) {
            progressIndicator = ReaderProgressIndicator(context, viewer.host)
            addView(progressIndicator)
        }
    }

    /** Asks both pages' loaders for them and follows the two statuses until the view goes. */
    private suspend fun loadPagesAndProcessStatus() {
        if (spread.pages.any { it.chapter.pageLoader == null }) return

        supervisorScope {
            spread.pages.forEach { page ->
                launchIO { page.chapter.pageLoader?.loadPage(page) }
            }
            combine(spread.first.statusFlow, spread.second.statusFlow, ::Pair).collectLatest { (first, second) ->
                when {
                    first is PageState.Error -> setError(first.error)
                    second is PageState.Error -> setError(second.error)
                    first == PageState.Ready && second == PageState.Ready -> setImage()
                    else -> {
                        setLoading()
                        // Halfway once one page is in: the ring stands for the pair.
                        combine(spread.first.progressFlow, spread.second.progressFlow) { a, b ->
                            (progressOf(first, a) + progressOf(second, b)) / 2
                        }.collectLatest { progressIndicator?.setProgress(it) }
                    }
                }
            }
        }
    }

    private fun progressOf(state: PageState, progress: Int): Int = when (state) {
        PageState.Ready -> FULL
        PageState.DownloadImage -> progress
        else -> 0
    }

    private fun setLoading() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun setImage() {
        progressIndicator?.setProgress(0)
        val onScreen = spread.onScreen(viewer is R2LPagerViewer)
        val cropBorders = viewer.config.imageCropBorders
        val target = target()
        try {
            val (result, background) = withIOContext {
                val result = SpreadImage.draw(onScreen, cropBorders, target)
                val background = if (result is SpreadImage.Result.Drawn && viewer.config.automaticBackground) {
                    onScreen.first().stream?.let { open -> open().use { ImageUtil.chooseBackground(context, it) } }
                } else {
                    null
                }
                result to background
            }
            withUIContext {
                when (result) {
                    is SpreadImage.Result.Alone -> viewer.onSpreadPageWide(result.page)
                    is SpreadImage.Result.Drawn -> {
                        setImage(
                            BitmapDrawable(resources, result.bitmap),
                            null,
                            Config(
                                zoomDuration = viewer.config.doubleTapAnimDuration,
                                minimumScaleType = viewer.config.imageScaleType,
                                zoomStartPosition = viewer.config.imageZoomType,
                                landscapeZoom = false,
                            ),
                        )
                        pageBackground = background
                        removeErrorLayout()
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            withUIContext { setError(PageDecodeException(e)) }
        }
    }

    /**
     * What a page is decoded to cover: the pager's width, and twice its height, so a double tap's
     * zoom stays sharp. The display's size before the pager's first layout.
     */
    private fun target(): TargetSize {
        val metrics = resources.displayMetrics
        val width = viewer.pager.width.takeIf { it > 0 } ?: metrics.widthPixels
        val height = viewer.pager.height.takeIf { it > 0 } ?: metrics.heightPixels
        return TargetSize(width, height * 2, SpreadImage.PAGE_MAX_PIXELS)
    }

    private fun setError(error: Throwable?) {
        progressIndicator?.hide()
        showErrorLayout(error)
    }

    override fun onImageLoaded() {
        super.onImageLoaded()
        progressIndicator?.hide()
    }

    override fun onImageLoadError(error: Throwable?) {
        super.onImageLoadError(error)
        setError(PageDecodeException(error))
    }

    override fun onScaleChanged(newScale: Float) {
        super.onScaleChanged(newScale)
        viewer.host.hideMenu()
    }

    private fun showErrorLayout(error: Throwable?) {
        val layout = errorLayout ?: ReaderErrorBinding.inflate(LayoutInflater.from(context), this, true).also {
            it.actionRetry.viewer = viewer
            it.actionRetry.setOnClickListener {
                spread.pages
                    .filter { page -> page.status is PageState.Error }
                    .forEach { page -> page.chapter.pageLoader?.retryPage(page) }
            }
            errorLayout = it
        }
        layout.errorMessage.text = context.pageErrorMessage(error)
        layout.root.isVisible = true
    }

    private fun removeErrorLayout() {
        errorLayout?.root?.isVisible = false
        errorLayout = null
    }

    private companion object {
        const val FULL = 100
    }
}
