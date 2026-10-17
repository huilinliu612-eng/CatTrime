/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.composition

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.view.ViewTreeObserver.OnPreDrawListener
import android.view.WindowInsets
import androidx.annotation.Size
import androidx.core.graphics.component1
import androidx.core.graphics.component2
import androidx.core.graphics.component3
import androidx.core.graphics.component4
import com.osfans.trime.core.Candidates
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.candidates.popup.PagedCandidatesUi
import com.osfans.trime.ime.candidates.popup.resolvedHorizontalLayout
import com.osfans.trime.ime.core.BaseInputView
import com.osfans.trime.ime.core.TouchEventReceiverWindow
import com.osfans.trime.ime.core.TrimeInputMethodService
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.withTheme
import splitties.views.dsl.core.wrapContent
import splitties.views.horizontalPadding
import splitties.views.setPaddingDp
import splitties.views.verticalPadding
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class CandidatesView(
    service: TrimeInputMethodService,
    rime: RimeSession,
    theme: Theme,
) : BaseInputView(service, rime, theme) {
    private val ctx = context.withTheme(android.R.style.Theme_DeviceDefault_Settings)

    private val layout by AppPrefs.defaultInstance().candidates.layout
    private val position by AppPrefs.defaultInstance().candidates.position

    /**
     * Opacity of this window's own background, in percent. Text and the highlighted item are
     * drawn by the children, so they stay readable while the background fades.
     */
    private val backgroundAlpha by AppPrefs.defaultInstance().candidates.backgroundAlpha

    private var candidates = Candidates.Paged()
    private var composition = CompositionProto()

    /**
     * Offset of the first candidate the window shows.
     *
     * Only a new page or a new "candidates per line" setting resets it (see [updateUi]); the
     * window's arrows move it within the page without Rime being involved.
     */
    private var slotOffset = 0
    private var lastPage: Candidates.Paged? = null
    private var lastPerLine = 0

    /** Whether the previous update laid the candidates out as a row; a flip re-derives the slot. */
    private var lastHorizontal = false

    /**
     * How many candidates the last laid-out window held -- the step the arrows take.
     *
     * Only a stacked (vertical) window is cut into several windows; a row shows the whole page,
     * so its capacity is the page size and a step leaves the page for Rime immediately.
     */
    private var lastCapacity = 0

    /**
     * Window offsets visited by the arrows within the current page, oldest first.
     *
     * A slice always holds the same number of candidates ([lastCapacity]), but a page's last
     * window may be shorter; replaying the offsets is what keeps a backward step exact.
     */
    private val windowHistory = ArrayDeque<Int>()

    private val anchorPosition = RectF()
    private val parentSize = floatArrayOf(0f, 0f)

    /**
     * A region of this view's parent that the window must stay clear of, in the parent's
     * coordinate space. Set while a floating keyboard is on screen, empty otherwise.
     */
    private val obstruction = RectF()

    private var shouldUpdatePosition = false

    /**
     * layout update may or may not cause [CandidatesView]'s size [onSizeChanged],
     * in either case, we should reposition it
     */
    private val layoutListener =
        OnGlobalLayoutListener {
            shouldUpdatePosition = true
        }

    /**
     * [CandidatesView]'s position is calculated based on it's size,
     * so we need to recalculate the position after layout,
     * and before any actual drawing to avoid flicker
     */
    private val preDrawListener =
        OnPreDrawListener {
            if (shouldUpdatePosition) {
                updatePosition()
            }
            true
        }

    private val preeditUi =
        PreeditUi(
            ctx,
            theme,
            setupPreeditView = { setPaddingDp(3, 1, 3, 1) },
            onMoveCursor = { pos -> rime.launchOnReady { it.moveCursorPos(pos) } },
        )

    // 显式类型：onCandidateAction 回调里引用了 candidatesUi 自身（toggleFullDisplay），
    // 省略类型会让编译器陷入递归推断。
    private val candidatesUi: PagedCandidatesUi =
        PagedCandidatesUi(
            ctx,
            theme,
            onCandidateClick = { index -> rime.launchOnReady { it.selectCandidate(index + slotOffset, global = false) } },
            onCandidateAction = { index, text, view ->
                // 仅当注解确实被截断且尚未展开时才提供「完整显示」：未截断的候选
                // 没有可展开内容，已展开的无需再展开，菜单项都不出现。
                val toggle: (() -> Unit)? =
                    if (candidatesUi.canToggleFullDisplay(text)) {
                        { candidatesUi.toggleFullDisplay(text) }
                    } else {
                        null
                    }
                showCandidateActionMenu(index + slotOffset, text, view, global = false, toggle)
            },
            onPrevPage = { stepRow(forward = false) },
            onNextPage = { stepRow(forward = true) },
        )

    private val touchEventReceiverWindow = TouchEventReceiverWindow(this)

    private var bottomInsets = 0

    override fun handleRimeMessage(it: RimeMessage<*>) {
        when (it) {
            is RimeMessage.CompositionMessage -> {
                composition = it.data
                updateUi()
            }
            is RimeMessage.PagedCandidatesMessage -> {
                candidates = it.data
                updateUi()
            }
            else -> {}
        }
    }

    private fun evaluateVisibility(): Boolean = !composition.preedit.isNullOrEmpty() ||
        candidates.candidates.isNotEmpty()

    private fun updateUi() {
        preeditUi.update(composition)
        preeditUi.root.visibility = if (preeditUi.visible) VISIBLE else GONE
        val perLine = perLine()
        val page = candidates
        // A row shows the whole page, so there is no window to move through and no offset to
        // keep: the arrows turn Rime's pages. Only the stacked layout is cut into windows.
        val horizontal = resolvedHorizontalLayout(candidates, layout)
        // A flip between the row and the stack is as good as a new page: the old offset described
        // a window of the other layout (and a row always sits at 0), so the stack has to reopen on
        // whatever Rime highlights instead of keeping an offset that may hide it.
        val layoutChanged = horizontal != lastHorizontal
        slotOffset =
            when {
                horizontal -> {
                    windowHistory.clear()
                    0
                }
                // A new page (or a new per-line count) puts the window back onto whatever Rime
                // highlights: browsing is a detour within a page, not a state that outlives it.
                layoutChanged || page != lastPage || perLine != lastPerLine -> {
                    windowHistory.clear()
                    slotOffsetFor(page.highlighted, perLine)
                }
                else ->
                    slotOffset.coerceAtMost(
                        lastSlotOffset(page.candidates.size, lastCapacity.coerceAtLeast(1)),
                    )
            }
        lastPage = page
        lastPerLine = perLine
        lastHorizontal = horizontal
        val window = candidateWindow(page, slotOffset, perLine, horizontal)
        // the candidate layout is queried natively with the page itself
        candidatesUi.update(window.page, layout, usableRowWidth().toInt())
        lastCapacity = window.capacity
        visibility = if (evaluateVisibility()) {
            VISIBLE
        } else {
            // RecyclerView won't update its items when ancestor view is GONE
            INVISIBLE
        }
    }

    /**
     * How many candidates this window shows at once: the "max candidates per row" setting.
     *
     * The candidates drawn on the keyboard and this window are the same widget, so the setting is
     * the authority for both. It applies to a vertical layout too. There is no "row" to count
     * there, but this window is anchored to the cursor, so an unbounded stack would cover the
     * screen -- the same number caps it instead, and the arrows step the stack the way they step
     * a row.
     *
     * Settings only allows editing the value while the keyboard's candidate mode fills the row,
     * but the value applies either way.
     */
    private fun perLine(): Int {
        val keyboard = AppPrefs.defaultInstance().keyboard
        val count =
            if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                keyboard.maxSpanCount.getValue()
            } else {
                keyboard.maxSpanCountLandscape.getValue()
            }
        return count.coerceAtLeast(1)
    }

    /** Width the row may occupy: the parent, less the window's own spacing budget and padding. */
    private fun usableRowWidth(): Float {
        val parentWidth = parentSize[0]
        if (parentWidth <= 0f) return 0f
        return parentWidth - 2 * dp(SPACING) - paddingLeft - paddingRight
    }

    /**
     * Moves the window one step within the page, and hands the step over to Rime once it has
     * reached the end of the page. No candidate is ever skipped: the step is the window's capacity,
     * and the arrows turn the page only after every window of it has been shown.
     */
    private fun stepRow(forward: Boolean) {
        if (!forward) {
            // Replay where the row came from; offsets the arrows did not produce (a page reset
            // puts the window on the highlighted candidate) fall back to a plain step back.
            val previous =
                windowHistory.removeLastOrNull()
                    ?: steppedSlotOffset(slotOffset, stepSize(), candidates.candidates.size, false)
            if (previous == null) {
                rime.launchOnReady { it.changeCandidatePage(true) }
                return
            }
            slotOffset = previous
            updateUi()
            return
        }
        // Step by what the window showed, not by the setting's maximum: a row that could only fit
        // half of it would otherwise jump over the candidates in between.
        val next = steppedSlotOffset(slotOffset, stepSize(), candidates.candidates.size, true)
        if (next == null) {
            rime.launchOnReady { it.changeCandidatePage(false) }
            return
        }
        windowHistory.addLast(slotOffset)
        slotOffset = next
        updateUi()
    }

    /** How far a step moves the window: what the last one actually showed. */
    private fun stepSize(): Int = lastCapacity.takeIf { it > 0 } ?: perLine()

    /** Re-derives the row after a setting that sizes it changed, without rebuilding this view. */
    fun refreshSlotLayout() {
        updateUi()
    }

    /** This window's background: the theme's own decoration, faded by the opacity setting. */
    private fun windowBackground(): Drawable? = ColorManager.getDecorDrawable(
        colorKey = "text_back_color",
        borderColorKey = "candidate_border_color",
        borderPx = dp(theme.window.border),
        cornerRadius = dp(theme.window.cornerRadius),
        alpha = backgroundAlpha * 255 / 100,
    )

    private fun updatePosition() {
        if (visibility != VISIBLE) return
        val (parentWidth, parentHeight) = parentSize
        if (parentWidth <= 0 || parentHeight <= 0) {
            translationX = 0f
            translationY = 0f
            return
        }
        val (horizontal, top, _, bottom) = anchorPosition
        val w = width
        val h = height
        val selfWidth = w.toFloat()
        val selfHeight = h.toFloat()
        val spacingDp = dp(SPACING)
        val anchorTop = top - spacingDp
        val anchorBottom = bottom + spacingDp
        val bottomLimit = parentHeight - bottomInsets
        val bottomSpace = bottomLimit - anchorBottom

        val tX: Float
        val positionedY: Float

        val minX = spacingDp
        val minY = spacingDp
        val maxX = parentWidth - selfWidth - spacingDp
        val flipAbove = anchorBottom + selfHeight > bottomLimit && // bottom space is not enough
            anchorTop > bottomSpace // top space is larger than bottom
        val maxY = if (flipAbove) anchorTop - selfHeight else bottomLimit - selfHeight - spacingDp
        when (position) {
            PopupPosition.TOP_RIGHT -> {
                tX = maxX
                positionedY = minY
            }
            PopupPosition.TOP_LEFT -> {
                tX = minX
                positionedY = minY
            }
            PopupPosition.BOTTOM_RIGHT -> {
                tX = maxX
                positionedY = maxY
            }
            PopupPosition.BOTTOM_LEFT -> {
                tX = minX
                positionedY = maxY
            }
            PopupPosition.FOLLOW -> {
                tX =
                    if (layoutDirection == LAYOUT_DIRECTION_RTL) {
                        val rtlOffset = parentWidth - horizontal
                        if (rtlOffset + selfWidth > parentWidth - spacingDp) {
                            selfWidth - parentWidth + spacingDp
                        } else {
                            -rtlOffset
                        }
                    } else {
                        if (horizontal + selfWidth > parentWidth - spacingDp) {
                            parentWidth - selfWidth - spacingDp
                        } else {
                            horizontal
                        }
                    }
                positionedY = if (flipAbove) anchorTop - selfHeight else anchorBottom
            }
        }
        // The vertical axis is overridden while an obstruction is on screen (see
        // [setObstruction]); the chosen [position] still drives the horizontal one.
        val tY =
            candidatesWindowTranslationY(
                positionedY = positionedY,
                obstructionTop = obstruction.top.takeIf { !obstruction.isEmpty() },
                selfHeight = selfHeight,
                spacing = spacingDp,
                minY = minY,
            )
        translationX = tX
        translationY = tY
        // A modal IME dialog takes every tap on the screen (its scrim covers the input view).
        // This receiver is a window of its own, above the input view, so it has to step aside
        // or it would keep feeding taps to the candidate row through the dialog.
        if (service.isDialogShowing) {
            touchEventReceiverWindow.dismiss()
        } else {
            // update touchEventReceiverWindow's position after CandidatesView's
            touchEventReceiverWindow.showAt(tX.roundToInt(), tY.roundToInt(), w, h)
        }
        shouldUpdatePosition = false
    }

    /** Re-evaluates the touch receiver after a modal IME dialog appeared or went away. */
    fun onDialogVisibilityChanged() {
        if (service.isDialogShowing) {
            touchEventReceiverWindow.dismiss()
        } else {
            shouldUpdatePosition = true
            invalidate()
        }
    }

    fun updateCursorAnchor(
        anchorPosition: RectF,
        @Size(2) parent: FloatArray,
    ) {
        this.anchorPosition.set(anchorPosition)
        val (parentWidth, parentHeight) = parent
        parentSize[0] = parentWidth
        parentSize[1] = parentHeight
        // The row is measured against the parent, so a new parent size re-lays it out as well.
        updateUi()
        updatePosition()
    }

    /**
     * Declares a region of the parent that this window must not cover, in the parent's
     * coordinate space, or `null` to clear it.
     *
     * A floating keyboard is the reason this exists: it overlays the editor, so it can appear
     * right underneath the cursor anchor, and the window would then be drawn on top of the
     * keys the user is aiming at.
     *
     * Relocation is deferred to the pre-draw pass -- callers include a layout callback, where
     * moving a window would re-enter layout.
     */
    fun setObstruction(rect: RectF?) {
        val next = rect?.takeIf { !it.isEmpty() }
        if (next == null) {
            if (obstruction.isEmpty()) return
            obstruction.setEmpty()
        } else {
            if (obstruction == next) return
            obstruction.set(next)
        }
        shouldUpdatePosition = true
        invalidate()
    }

    /**
     * Refreshes the area this window is placed against, without touching the cursor anchor.
     *
     * The height is the keyboard's top edge (or the whole content view while it floats), so it
     * changes whenever the keyboard changes height. That usually happens while the window is
     * hidden, and the value captured when it was last shown would then describe a keyboard that
     * no longer exists -- the window would be docked to the old edge and land on the keys.
     */
    fun updateParentSize(parent: FloatArray) {
        if (parentSize[0] == parent[0] && parentSize[1] == parent[1]) return
        val widthChanged = parentSize[0] != parent[0]
        parentSize[0] = parent[0]
        parentSize[1] = parent[1]
        // The row is measured against the parent's width, so only a new width re-splits it.
        if (widthChanged) updateUi()
        shouldUpdatePosition = true
        invalidate()
    }

    /**
     * Anchor candidates view to bottom-left corner, takes navbar bottom insets into consideration.
     * Should only be used when [CursorAnchorInfo][android.view.inputmethod.CursorAnchorInfo] is invalid
     */
    fun updateCursorAnchor(@Size(2) parent: FloatArray) {
        val (parentWidth, parentHeight) = parent
        val bottom = parentHeight - bottomInsets
        anchorPosition.set(0f, bottom, 0f, bottom)
        parentSize[0] = parentWidth
        parentSize[1] = parentHeight
        updateUi()
        updatePosition()
    }

    init {
        visibility = INVISIBLE

        minWidth = dp(theme.window.minWidth)
        verticalPadding = dp(theme.window.insets.vertical)
        horizontalPadding = dp(theme.window.insets.horizontal)
        alpha = theme.window.alpha
        background = windowBackground()
        clipToOutline = true
        outlineProvider = ViewOutlineProvider.BACKGROUND
        elevation = dp(theme.window.shadow)
        add(
            preeditUi.root,
            lParams(wrapContent, wrapContent) {
                topOfParent()
                startOfParent()
            },
        )
        add(
            candidatesUi.root,
            lParams(matchConstraints, wrapContent) {
                matchConstraintMinWidth = wrapContent
                below(preeditUi.root)
                centerHorizontally()
                bottomOfParent()
            },
        )

        isFocusable = false
        layoutParams = ViewGroup.LayoutParams(wrapContent, wrapContent)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Reserve SPACING on both sides so that when updatePosition() docks the
        // window to a parent edge, its own spacing budget (minX/maxX = spacingDp)
        // is always achievable. Only cap when the spec constrains the width —
        // an UNSPECIFIED spec has no parent edges to reserve spacing from.
        val newWidthMeasureSpec =
            if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                widthMeasureSpec
            } else {
                val maxWidth = MeasureSpec.getSize(widthMeasureSpec) -
                    dp(2 * SPACING).roundToInt()
                MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST)
            }
        super.onMeasure(newWidthMeasureSpec, heightMeasureSpec)
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            bottomInsets = getNavBarBottomInset(insets)
        }
        return insets
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        viewTreeObserver.addOnPreDrawListener(preDrawListener)
    }

    override fun setVisibility(visibility: Int) {
        if (visibility != VISIBLE) {
            touchEventReceiverWindow.dismiss()
        } else if (this.visibility != VISIBLE) {
            // Recalibrate on every show: while hidden, the keyboard may have changed height or
            // been dragged away, and reusing the translation from the previous show would leave
            // the window at a position that its current anchor/size no longer imply.
            shouldUpdatePosition = true
            invalidate()
        }
        super.setVisibility(visibility)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(preDrawListener)
        viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        touchEventReceiverWindow.dismiss()
        super.onDetachedFromWindow()
    }

    companion object {
        /**
         * Spacing in density-independent pixels (dp) kept between the candidate
         * window and the parent edges whenever the window docks to them.
         */
        private const val SPACING = 5f
    }
}

/**
 * Vertical translation of the candidate window.
 *
 * [positionedY] is the placement the chosen [PopupPosition] asks for. When [obstructionTop]
 * is given -- the top edge of a floating keyboard -- the window is pinned just above it
 * instead, so it never hides the keys. Pinning rather than merely nudging also keeps the
 * window still while the cursor moves around underneath the keyboard.
 *
 * A keyboard dragged all the way up leaves no room; the window is then clamped to [minY],
 * which is the top of the parent, rather than being pushed off screen.
 */
internal fun candidatesWindowTranslationY(
    positionedY: Float,
    obstructionTop: Float?,
    selfHeight: Float,
    spacing: Float,
    minY: Float,
): Float = if (obstructionTop == null) {
    positionedY
} else {
    (obstructionTop - spacing - selfHeight).coerceAtLeast(minY)
}
