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
