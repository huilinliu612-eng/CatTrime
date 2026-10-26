/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.bar.ui

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.core.view.children
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.JustifyContent
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.model.ToolBar
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.view

class ButtonsBarUi(
    override val ctx: Context,
    private val theme: Theme,
    private val onButtonClick: ((String) -> Unit)? = null,
) : Ui {
    override val root = view(::FlexboxLayout) {
        alignItems = AlignItems.CENTER
        justifyContent = JustifyContent.FLEX_START
        flexDirection = FlexDirection.ROW_REVERSE
        // 按钮不换行：空间不足时交给外层横向滚动，而不是压缩按钮（图标变形）
        // 或折行成两行（挤压键盘高度）。
        flexWrap = FlexWrap.NOWRAP
    }

    val firstButton: ToolButton?

    private val defaultButtonSize = theme.generalStyle.run { candidateViewHeight + commentHeight }

    private fun toolButton(
        buttonConfig: ToolBar.Button?,
        @DrawableRes icon: Int = 0,
    ): ToolButton = if (buttonConfig != null) {
        ToolButton(ctx, buttonConfig).apply {
            setOnClickListener { onButtonClick?.invoke(buttonConfig.action) }
            val longPressAction = buttonConfig.longPressAction
            if (longPressAction.isNotEmpty()) {
                setOnLongClickListener {
                    onButtonClick?.invoke(longPressAction)
                    true
                }
            }
        }
    } else {
        ToolButton(ctx, icon).apply {
            setOnClickListener { onButtonClick?.invoke("") }
        }
    }

    /**
     * 语音按钮插入位置（视觉顺序）：0 = 所有主题按钮之后（最右），N = 第 N 个主题按钮之前。
     */
    private var asrkbVoiceIndex: Int = 0

    private var asrkbVoiceButtonView: ToolButton? = null

    /**
     * 注册语音按钮实例。真正的插入由 [insertAsrkbVoiceButton] 完成。
     *
     * 复用 [AlwaysUi] 里的同一个 [ToolButton] 实例（点击回调与图标状态都绑在它上面），
     * 避免出现两份状态导致图标不同步。
     */
    fun attachAsrkbVoiceButton(button: ToolButton, visualIndex: Int) {
        asrkbVoiceButtonView = button
        asrkbVoiceIndex = visualIndex
    }

    init {
        val buttons = theme.toolBar.buttons
        firstButton = buttons.firstOrNull()?.let { toolButton(it) }
        buttons.drop(1).forEach { config ->
            val button = toolButton(config)
            val size = getButtonSize(config)
            val lParams = FlexboxLayout.LayoutParams(size.first, size.second).apply {
                marginEnd = ctx.dp(theme.toolBar.buttonSpacing)
                // flexShrink 默认 1，空间不足会把按钮压变形；禁掉后按钮保持原尺寸，
                // 溢出部分由 AlwaysUi 里的横向滚动条接管。
                flexShrink = 0f
            }
            root.addView(button, lParams)
        }
        insertAsrkbVoiceButton()
    }

    /**
     * 把语音按钮插入到主题按钮序列中。
     *
     * ### 坐标系换算（关键）
     * `root` 是 `ROW_REVERSE` 的 FlexboxLayout，视觉上从右往左排：
     * - 视觉第 1 个主题按钮由 `firstButton` 承载（在 AlwaysUi 的 rightMostButton 里），位于最右；
     * - `root` 内 addView 索引 0、1、2… 依次对应视觉上的第 2、3、4 个主题按钮。
     *
     * 因此视觉插入位 `v` 与 addView 索引的关系为 `addViewIndex = v - 1`：
     * - v = 0（放在最右）：addView 索引 0，即紧贴 firstButton 左侧；
     * - v = 1（放在第 1 个之前）：同样落在最右，与 v=0 等价；
     * - v = 2：插在视觉第 1、2 个之间 → addView 索引 1；
     * - v 超过总数：钳制到末尾，即最左端。
     *
     * 语音按钮参与 flex 布局（flexShrink=0），因此不会与任何主题按钮重叠，
     * 空间不足时由外层横向滚动条接管。
     */
    private fun insertAsrkbVoiceButton() {
        val button = asrkbVoiceButtonView ?: return
        if (button.parent != null) return
        val total = theme.toolBar.buttons.size
        val visualIndex = asrkbVoiceIndex.coerceIn(0, total)
        val addIndex = if (visualIndex <= 1) {
            0
        } else {
            (visualIndex - 1).coerceIn(0, root.childCount)
        }
        val config = theme.toolBar.buttons.firstOrNull()
        val size = getButtonSize(config)
        val lParams = FlexboxLayout.LayoutParams(size.first, size.second).apply {
            marginEnd = ctx.dp(theme.toolBar.buttonSpacing)
            flexShrink = 0f
        }
        root.addView(button, addIndex, lParams)
    }

    internal fun getButtonSize(config: ToolBar.Button?, customDefaultSize: Int = defaultButtonSize): Pair<Int, Int> {
        val sizeList = config?.size?.takeIf { it.size == 2 } ?: List(2) { customDefaultSize }
        val (width, height) = sizeList

        val finalWidth = if (width >= 0) ctx.dp(width) else width
        val finalHeight = if (height >= 0) ctx.dp(height) else height

        return finalWidth to finalHeight
    }

    fun updateStyle(option: String, enabled: Boolean) {
        root.children.forEach { (it as ToolButton).updateStyle(option, enabled) }
    }
}
