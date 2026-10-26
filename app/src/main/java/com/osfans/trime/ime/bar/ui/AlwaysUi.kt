/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.bar.ui

import android.content.Context
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ViewAnimator
import androidx.annotation.DrawableRes
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.children
import com.osfans.trime.R
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.model.ToolBar
import splitties.views.dsl.constraintlayout.after
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.endToStartOf
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent
import timber.log.Timber

class AlwaysUi(
    override val ctx: Context,
    private val theme: Theme,
    private val onButtonClick: ((String) -> Unit)? = null,
) : Ui {
    enum class State {
        Toolbar,
        Clipboard,
        InlineSuggestion,
    }

    var currentState = State.Toolbar
        private set

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

    val buttonsUi = ButtonsBarUi(ctx, theme, onButtonClick)

    val clipboardUi = ClipboardSuggestionUi(ctx)

    val inlineSuggestionsUi = InlineSuggestionsUi(ctx)

    val hideKeyboardButton = ToolButton(ctx, R.drawable.ic_baseline_arrow_drop_down_24)

    /** 说点啥 AIDL 语音输入工具栏按钮：点按开始录音，再次点按停止并上屏。 */
    val asrkbVoiceButton =
        ToolButton(ctx, R.drawable.ic_baseline_mic_24).apply {
            visibility = View.GONE
        }
    private val rightMostButton =
        ViewAnimator(ctx).apply {
            add(hideKeyboardButton, lParams(matchParent, matchParent))
            buttonsUi.firstButton?.let { add(it, lParams(matchParent, matchParent)) }
        }

    private val leftMostButton = toolButton(
        theme.toolBar.primaryButton,
        R.drawable.ic_baseline_more_horiz_24,
    )

    /**
     * 工具栏按钮区的横向滚动容器：悬浮键盘等窄空间下按钮不再被压缩变形（按钮
     * flexShrink=0 + 不换行），放不下时滚动查看。左右两端的按钮保持固定，
     * 只有中间区域滚动。
     *
     * 中间按钮用 FlexDirection.ROW_REVERSE 贴右侧 firstButton 停靠。包进横向滚动后
     * 该停靠会失效：HorizontalScrollView 对滚动轴的测量恒为 UNSPECIFIED(0)（见其
     * measureChild），子视图 layoutParams 里的宽度被完全忽略——所以手动改宽度无效，
     * fillViewport 也不能用（它把子视图强制测成视口宽，内容溢出时滚动量归零、
     * 溢出按钮被裁掉且无法滚到）。改用平移：布局后若内容窄于视口，把内容整体右移
     * 补齐差值，恢复贴右停靠；内容溢出时平移归零，保持可滚动，并首次滑到最右端
     * 以还原原版的初始可见范围。
     */
    private var buttonsScrolledToStart = false

    private val buttonsScroller =
        HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            add(buttonsUi.root, lParams(wrapContent, matchParent))
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                // computeHorizontalScrollRange 是 protected，用子视图宽度求溢出。
                val overflow = buttonsUi.root.width - v.width
                buttonsUi.root.translationX = (-overflow).coerceAtLeast(0).toFloat()
                if (overflow > 0 && !buttonsScrolledToStart) {
                    buttonsScrolledToStart = true
                    scrollTo(overflow, 0)
                }
            }
        }

    private val animator =
        ViewAnimator(ctx).apply {
            add(buttonsScroller, lParams(matchParent, matchParent))
            add(clipboardUi.root, lParams(matchParent, matchParent))
            add(inlineSuggestionsUi.root, lParams(matchParent, matchParent))
        }

    override val root: ConstraintLayout = constraintLayout {
        val (leftWidth, leftHeight) = buttonsUi.getButtonSize(theme.toolBar.primaryButton)
        val (rightWidth, rightHeight) = buttonsUi.getButtonSize(theme.toolBar.buttons.firstOrNull())

        add(
            leftMostButton,
            lParams(leftWidth, leftHeight) {
                startOfParent()
                centerVertically()
            },
        )
        add(
            rightMostButton,
            lParams(rightWidth, rightHeight) {
                endOfParent()
                centerVertically()
            },
        )
        // 语音按钮排在 rightMostButton 左侧：endToStart=rightMostButton。
        // 它 GONE 时 ConstraintLayout 会忽略约束，不占位也不会影响 rightMostButton。
        add(
            asrkbVoiceButton,
            lParams(rightWidth, rightHeight) {
                endToStartOf(rightMostButton)
                centerVertically()
            },
        )
        add(
            animator,
            lParams(matchConstraints, matchParent) {
                after(leftMostButton)
                before(rightMostButton)
                centerVertically()
            },
        )
    }.apply {
        updateRightMostButton(State.Toolbar)
    }

    fun updateButtonsStyle(option: String, enabled: Boolean) {
        leftMostButton.updateStyle(option, enabled)
        buttonsUi.firstButton?.updateStyle(option, enabled)
        buttonsUi.updateStyle(option, enabled)
    }

    fun toggleOptions(): Set<String> = buildSet {
        leftMostButton.option?.let { add(it) }
        buttonsUi.firstButton?.option?.let { add(it) }
        buttonsUi.root.children.forEach { (it as ToolButton).option?.let { add(it) } }
    }

    fun updateState(state: State) {
        Timber.d("Switch always ui to $state")
        animator.displayedChild = state.ordinal
        currentState = state
        updateRightMostButton(state)
        updateLeftMostButton(state)
    }

    private fun updateRightMostButton(state: State) {
        val hasFirstButton = buttonsUi.firstButton != null
        val showFirst = hasFirstButton && (theme.toolBar.buttons.isNotEmpty() || state != State.Toolbar)
        rightMostButton.displayedChild = if (showFirst) 1 else 0
    }

    private fun updateLeftMostButton(state: State) {
        val buttonConfig =
            if (state == State.Toolbar) {
                theme.toolBar.primaryButton
            } else {
                theme.toolBar.buttons.firstOrNull()
            }

        val (buttonWidth, buttonHeight) = buttonsUi.getButtonSize(buttonConfig)
        leftMostButton.layoutParams = leftMostButton.layoutParams.apply {
            width = buttonWidth
            height = buttonHeight
        }
    }
}
