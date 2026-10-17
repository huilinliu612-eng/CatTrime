/*
 * SPDX-FileCopyrightText: 2015 - 2024 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.popup

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import androidx.annotation.ColorInt
import androidx.core.text.buildSpannedString
import androidx.core.text.inSpans
import com.google.android.flexbox.FlexboxLayoutManager
import com.osfans.trime.core.CandidateProto
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.FontManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.util.sp
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.textView

class LabeledCandidateItemUi(
    override val ctx: Context,
    val theme: Theme,
) : Ui {
    private val labelSize = theme.window.foreground.labelFontSize
    private val textSize = theme.window.foreground.textFontSize
    private val commentSize = theme.window.foreground.commentFontSize
    private val labelFont = FontManager.getTypeface("label_font")
    private val textFont = FontManager.getTypeface("candidate_font")
    private val commentFont = FontManager.getTypeface("comment_font")
    private val labelColor = ColorManager.getColor("label_color")
    private val textColor = ColorManager.getColor("candidate_text_color")
    private val commentColor = ColorManager.getColor("comment_text_color")
    private val highlightLabelColor = ColorManager.getColor("hilited_label_color")
    private val highlightCommentTextColor = ColorManager.getColor("hilited_comment_text_color")
    private val highlightCandidateTextColor = ColorManager.getColor("hilited_candidate_text_color")
    private val highlightCandidateBackColor = ColorManager.getColor("hilited_candidate_back_color")

    /**
     * Fill for the candidates that are not highlighted, when [itemBackground] asks for one.
     *
     * The separator colour is the right source here: every theme already defines it to sit between
     * the window background and the highlight, so an item filled with it reads as "a slot of the
     * row" while the highlighted item's own colour still clearly wins.
     */
    private val itemBackColor: Int get() = ColorManager.getColor("candidate_separator_color")

    // Held as a delegate and read per bind rather than captured with `by`, which would freeze the
    // value at construction and leave the setting looking like it did nothing.
    private val itemBackground = AppPrefs.defaultInstance().candidates.itemBackground
    private val itemBackgroundAlpha = AppPrefs.defaultInstance().candidates.itemBackgroundAlpha

    override val root =
        textView {
            val v = dp(theme.window.itemPadding.vertical)
            val h = dp(theme.window.itemPadding.horizontal)
            setPadding(h, v, h, v)
            // 单行 + 省略号是所有候选的基础形态：放不下时先缩字号，缩到下限才出省略号。
            // 完整展示由 [setExpanded] 按需开启（候选窗口长按菜单的「完整显示」）。
            ellipsize = TextUtils.TruncateAt.END
        }

    private inline fun SpannableStringBuilder.inSpanWith(
        @ColorInt color: Int,
        textSize: Float,
        typeface: Typeface,
        builderAction: SpannableStringBuilder.() -> Unit,
    ) = inSpans(CandidateItemSpan(color, textSize, typeface), builderAction)

    /**
     * The label, candidate and comment, in the theme's own sizes scaled by [scale].
     *
     * [comment] defaults to the candidate's own annotation; [bindSingleLine] passes a
     * pre-truncated copy (or an empty string to leave the annotation out entirely).
     */
    private fun textOf(
        candidate: CandidateProto,
        highlighted: Boolean,
        scale: Float,
        comment: CharSequence = candidate.comment,
    ): CharSequence {
        val labelFg = if (highlighted) highlightLabelColor else labelColor
        val textFg = if (highlighted) highlightCandidateTextColor else textColor
        val commentFg = if (highlighted) highlightCommentTextColor else commentColor
        return buildSpannedString {
            inSpanWith(labelFg, ctx.sp(labelSize * scale), labelFont) { append(candidate.label) }
            append(" ")
            inSpanWith(textFg, ctx.sp(textSize * scale), textFont) { append(candidate.text) }
            if (comment.isNotBlank()) {
                append(" ")
                inSpanWith(commentFg, ctx.sp(commentSize * scale), commentFont) { append(comment) }
            }
        }
    }

    /** 当次 [update] 应用的行数上限（0=不限）；[setExpanded] 收起时按它还原。 */
    private var boundRowLimit = 1

    /** 行数上限的 TextView 口径：0 表示不限，其余至少 1 行。 */
    private fun maxLinesFor(limit: Int): Int = if (limit == 0) Int.MAX_VALUE else limit.coerceAtLeast(1)

    /** 最近一次 [update] 的绑定参数：展开/收起靠按它们重跑 [update] 完成。 */
    private var lastCandidate: CandidateProto? = null
    private var lastHighlighted = false
    private var lastMaxWidth = 0

    /** 展开前的行数上限：收起时还原，避免被展开时的不限行覆盖。 */
    private var collapsedRowLimit = 1

    /** 当前是否处于「完整显示」展开态；[update] 每次都会把它复位为收起。 */
    private var isExpanded = false

    /**
     * 当次 [update] 是否隐藏了内容：注解被截断/丢弃，或整单元缩小后仍溢出。
     * 持有者据此决定长按菜单是否提供「完整显示」。
     */
    var lastBindTruncated = false
        private set

    /** 注解截断测量用的画笔：与注解 span 同字号同字体（颜色不影响宽度）。 */
    private val commentPaint by lazy {
        TextPaint().apply {
            isAntiAlias = true
            typeface = commentFont
            textSize = ctx.sp(commentSize)
        }
    }

    /**
     * Binds one candidate, giving up as much of its size as [maxWidth] demands.
     *
     * A candidate that is longer than the space it has would otherwise be ellipsized, which drops
     * text the user is in the middle of reading. Shrinking costs size instead of text, and the
     * candidates drawn on the keyboard already behave that way, so both places read the same. Only
     * the squeezed items pay for it: the common case is measured, found to fit and left alone.
     *
     * [rowLimit] caps the lines a candidate may wrap onto (0 = unlimited), in either layout: a
     * horizontal item wraps onto its own lines exactly as a stacked one does. Only single-line
     * items shrink: a multi-line item carries its overflow by wrapping instead (with the overflow
     * ellipsized away at the cap), so [candidateItemScale] would be a no-op for it anyway.
     *
     * @return the applied scale (below 1 means the item is shrunk and still ellipsized — the
     * owner uses this to decide whether the long-press menu's "show full comment" applies;
     * a comment truncated to the remaining width reports through [lastBindTruncated] instead).
     */
    fun update(
        candidate: CandidateProto,
        highlighted: Boolean,
        maxWidth: Int,
        rowLimit: Int = 1,
    ): Float {
        boundRowLimit = rowLimit
        lastCandidate = candidate
        lastHighlighted = highlighted
        lastMaxWidth = maxWidth
        isExpanded = false
        lastBindTruncated = false
        root.maxLines = maxLinesFor(rowLimit)
        val scale =
            if (root.maxLines == 1) {
                bindSingleLine(candidate, highlighted, maxWidth)
            } else {
                root.text = textOf(candidate, highlighted, scale = 1f)
                1f
            }
        root.background =
            GradientDrawable().apply {
                setColor(
                    candidateItemBackColor(
                        highlighted = highlighted,
                        fillUnhighlighted = itemBackground.getValue(),
                        highlightColor = highlightCandidateBackColor,
                        itemColor = itemBackColor,
                        // Only the unhighlighted fill is faded; the highlighted one keeps the
                        // theme's own strength, since that contrast is what marks the selection.
                        itemAlpha = itemBackgroundAlpha.getValue() * 255 / 100,
                    ),
                )
                cornerRadius = ctx.dp(theme.generalStyle.candidateCornerRadius)
            }
        return scale
    }

    /**
     * 单行绑定：注解按剩余宽度手工截断，填满整行。
     *
     * 标签+候选词作为一个整体量宽：放得下时原样保留，注解用 [TextUtils.ellipsize]
     * 在剩余宽度里截断——截多少由剩余宽度决定，注解一直延伸到行尾，不再出现
     * 「候选词很短、注解只显示几个字、右侧大片空白」的形态。标签+候选词自己都
     * 放不下时，退回旧的整单元缩小路径（注解跟着一起缩，缩小到下限仍溢出才由
     * TextView 出省略号）。
     */
    private fun bindSingleLine(
        candidate: CandidateProto,
        highlighted: Boolean,
        maxWidth: Int,
    ): Float {
        if (maxWidth <= 0) {
            // 几何未知（窗口尚未量出宽度）：不做任何压缩，维持原始文本。
            root.text = textOf(candidate, highlighted, scale = 1f)
            return 1f
        }
        root.text = textOf(candidate, highlighted, scale = 1f, comment = "")
        val headWidth = measureUnconstrained()
        if (headWidth > maxWidth) {
            root.text = textOf(candidate, highlighted, scale = 1f)
            val scale = candidateItemScale(measureUnconstrained(), maxWidth)
            if (scale < 1f) root.text = textOf(candidate, highlighted, scale)
            return scale
        }
        val comment = candidate.comment
        if (comment.isNotBlank()) {
            // 分隔空格不在任何 span 里，按宿主 TextView 自身的画笔计量。
            val available = maxWidth - headWidth - Layout.getDesiredWidth(" ", root.paint).toInt()
            if (available <= 0) {
                // 候选词几乎占满整行，注解一个字都排不下：整段隐藏。
                lastBindTruncated = true
            } else {
                val cut = TextUtils.ellipsize(comment, commentPaint, available.toFloat(), TextUtils.TruncateAt.END)
                if (cut.length < comment.length) lastBindTruncated = true
                root.text = textOf(candidate, highlighted, scale = 1f, comment = cut)
            }
        }
        return 1f
    }

    /**
     * 完整显示模式（候选窗口长按菜单的「完整显示」）：把该候选的注解完整展开排版。
     *
     * 单行条目的注解在绑定时已被物理截断（见 [bindSingleLine]），单纯放开 maxLines
     * 看不到被剪掉的部分，因此展开/收起都通过按记录的绑定参数重跑 [update] 完成：
     * 展开按不限行重绑，收起按展开前记住的上限（[collapsedRowLimit]）重绑。
     * 横向与纵向同样适用；同时在窗口里最多展开一个条目，展开/收起都由持有者
     * （PagedCandidatesUi）以候选文本为 key 管理。
     */
    fun setExpanded(expanded: Boolean) {
        if (expanded == isExpanded) return
        val candidate = lastCandidate ?: return
        if (expanded) {
            collapsedRowLimit = boundRowLimit
            update(candidate, lastHighlighted, lastMaxWidth, rowLimit = 0)
            // update() 把展开态复位成了收起，这里补回；lastBindTruncated 同理被
            // 复位，持有者会在重绑后的最终形态上重新判定。
            isExpanded = true
        } else {
            update(candidate, lastHighlighted, lastMaxWidth, rowLimit = collapsedRowLimit)
        }
    }

    /**
     * The width [root] would take if nothing constrained it.
     *
     * [root] must already carry layout params: `TextView.setText` reads
     * `ViewGroup.LayoutParams.width` (via `checkForRelayout`) and throws a `NullPointerException`
     * when they are missing. The row assigns them in `onCreateViewHolder`; a standalone measuring
     * probe is never added to a parent, so it has to do the same before the first `setText`.
     */
    private fun measureUnconstrained(): Int {
        if (root.layoutParams == null) {
            root.layoutParams =
                FlexboxLayoutManager.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
        }
        root.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return root.measuredWidth
    }
}

/**
 * The fill behind one candidate item.
 *
 * The highlighted candidate always wins: its colour is what tells the user which candidate the
 * next space bar will commit to, so [fillUnhighlighted] must never take it over and [itemAlpha]
 * never touches it. Filling the rest is opt-in and deliberately uses a *different* colour
 * ([itemColor], the theme's separator colour) rather than a dimmed version of the highlight -- a
 * dimmed highlight would compete with the real one, which is the whole thing this is meant to
 * avoid.
 */
internal fun candidateItemBackColor(
    highlighted: Boolean,
    fillUnhighlighted: Boolean,
    highlightColor: Int,
    itemColor: Int,
    itemAlpha: Int = 255,
): Int = when {
    highlighted -> highlightColor
    fillUnhighlighted -> itemColor.withAlpha(itemAlpha)
    else -> Color.TRANSPARENT
}

/**
 * Ratio the text sizes of a shrunk item are multiplied by, alongside [candidateItemScale].
 *
 * A hair under the exact ratio: glyph advances do not scale perfectly linearly, so freeing exactly
 * as much width as the sum of the advances suggests can still round to a pixel over and bring back
 * the ellipsis this is meant to remove.
 */
internal const val CANDIDATE_SHRINK_MARGIN = 0.98f

/**
 * Floor for [candidateItemScale].
 *
 * Shrinking exists to save readable text, so it stops once the text stops being readable: past half
 * the theme's own size the ellipsis keeps more than a line of specks would.
 */
internal const val CANDIDATE_MIN_SCALE = 0.5f

/**
 * Share of the theme's own text sizes an item keeps when it is given [maxWidth].
 *
 * 1 means "leave it alone", which is both the answer for an item that already fits and the answer
 * before the row has been laid out at all ([maxWidth] of 0): a width that is not known is not a
 * reason to start shrinking text.
 */
internal fun candidateItemScale(
    naturalWidth: Int,
    maxWidth: Int,
): Float {
    if (naturalWidth <= 0 || maxWidth <= 0 || naturalWidth <= maxWidth) return 1f
    val exact = maxWidth.toFloat() / naturalWidth.toFloat()
    return (exact * CANDIDATE_SHRINK_MARGIN).coerceAtLeast(CANDIDATE_MIN_SCALE)
}

/**
 * [color] scaled by [alpha] (0..255), keeping the theme's own opacity as the ceiling.
 *
 * The fill comes from a theme colour, which carries whatever opacity the theme author chose -- it
 * may be deliberately translucent. The two have to *compose*: multiplying means 100% leaves the
 * theme's own translucency alone, while a lower setting dims it further. Replacing the alpha
 * outright would make 100% mean "fully opaque" and quietly override a theme that meant to be
 * see-through, and would make a lower setting *raise* the alpha of a faint theme colour.
 */
private fun Int.withAlpha(alpha: Int): Int {
    val own = (this ushr 24 and 0xFF)
    val scaled = own * alpha.coerceIn(0, 255) / 255
    return (this and 0x00FFFFFF) or (scaled shl 24)
}
