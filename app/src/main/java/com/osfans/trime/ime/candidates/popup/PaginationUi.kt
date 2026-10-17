/*
 * SPDX-FileCopyrightText: 2015 - 2024 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.popup

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import com.osfans.trime.R
import com.osfans.trime.core.Candidates
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.util.styledFloat
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.imageView
import splitties.views.imageDrawable

class PaginationUi(
    override val ctx: Context,
    val theme: Theme,
) : Ui {
    private fun createIcon(
        @DrawableRes icon: Int,
    ) = imageView {
        imageTintList = ColorStateList.valueOf(ColorManager.getColor("key_text_color"))
        imageDrawable = drawable(icon)
        // 居中不缩放：触摸区放大后图标视觉尺寸保持 24dp，不随按钮拉伸变形
        scaleType = ImageView.ScaleType.CENTER
    }

    val prevIcon = createIcon(R.drawable.ic_baseline_arrow_left_24)
    val nextIcon = createIcon(R.drawable.ic_baseline_arrow_right_24)

    private val disabledAlpha = ctx.styledFloat(android.R.attr.disabledAlpha)

    override val root =
        horizontalLayout {
            // splitties 本版本的 add 无默认 LayoutParams 重载，先给占位参数，
            // 真实尺寸由 setLayoutMode 按横/纵向在绑定时设置。
            add(
                prevIcon,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            add(
                nextIcon,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

    /**
     * 纵向模式下翻页条独占一行且占满窗口宽度，两个按钮各分一半、加高到
     * [PAGINATION_HEIGHT_DP]；横向模式翻页条跟在行尾（宽度预算 [WIDTH_DP]），
     * 靠右对齐。两种模式都使用完整图标尺寸作为触摸区——旧版横向按钮只有
     * 10dp×20dp，24dp 图标被裁掉大半，几乎点不中。
     */
    fun setLayoutMode(horizontal: Boolean) {
        // 模块未引入 splitties 的 linearlayout dsl 子包，weight 直接用
        // LinearLayout.LayoutParams 原生构造，不依赖 dsl 扩展。
        val paramsOf: (ImageView) -> LinearLayout.LayoutParams =
            if (horizontal) {
                { LinearLayout.LayoutParams(ctx.dp(ICON_WIDTH_DP), ctx.dp(COMPACT_HEIGHT_DP)) }
            } else {
                {
                    LinearLayout.LayoutParams(0, ctx.dp(PAGINATION_HEIGHT_DP)).apply {
                        weight = 1f
                    }
                }
            }
        prevIcon.layoutParams = paramsOf(prevIcon)
        nextIcon.layoutParams = paramsOf(nextIcon)
        root.gravity =
            if (horizontal) {
                // flexGrow 让翻页条吃掉行尾剩余宽度，图标靠右停在同一位置，而不是紧跟
                // 最后一个候选词左右游移。
                Gravity.END or Gravity.CENTER_VERTICAL
            } else {
                Gravity.CENTER
            }
    }

    fun update(paged: Candidates.Paged) {
        updateIcon(prevIcon, paged.hasPrevPage)
        updateIcon(nextIcon, paged.hasNextPage)
    }

    private fun updateIcon(
        icon: ImageView,
        enabled: Boolean,
    ) {
        icon.isEnabled = enabled
        icon.isClickable = enabled
        icon.alpha = if (enabled) 1f else disabledAlpha
    }

    companion object {
        /** Width of one arrow icon, in dp. */
        const val ICON_WIDTH_DP = 24

        /** 纵向模式翻页按钮的高度（也是翻页行高），dp。 */
        const val PAGINATION_HEIGHT_DP = 28

        /** 横向模式翻页按钮的高度，dp；28dp 足以容纳 24dp 图标并留出触摸余量。 */
        private const val COMPACT_HEIGHT_DP = 28

        /**
         * Width of the whole pagination item, in dp.
         *
         * The candidate row has a fixed width per candidate (see [PagedCandidatesUi]), so it has
         * to reserve this much for the arrows -- otherwise the last candidate would push them
         * over the window's limit and force a second line.
         */
        const val WIDTH_DP = 2 * ICON_WIDTH_DP
    }
}
