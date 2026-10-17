/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.core

import android.annotation.SuppressLint
import android.content.res.Resources
import android.os.Build
import android.view.View
import android.view.WindowInsets
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.theme.ThemePrefs
import com.osfans.trime.ime.keyboard.InputFeedbackManager
import com.osfans.trime.util.ImePopupMenu
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import splitties.dimensions.dp
import splitties.views.dsl.core.withTheme
import kotlin.math.max

abstract class BaseInputView(
    val service: TrimeInputMethodService,
    val rime: RimeSession,
    val theme: Theme,
) : ConstraintLayout(service) {
    protected abstract fun handleRimeMessage(it: RimeMessage<*>)

    private var messageHandlerJob: Job? = null

    private fun setupRimeMessageHandler() {
        messageHandlerJob =
            service.lifecycleScope.launch {
                rime.run { messageFlow }.collect {
                    handleRimeMessage(it)
                }
            }
    }

    var handleMessages = false
        set(value) {
            field = value
            if (field) {
                if (messageHandlerJob == null) {
                    setupRimeMessageHandler()
                }
            } else {
                messageHandlerJob?.cancel()
                messageHandlerJob = null
            }
        }

    init {
        // 程序化创建的 View/ViewGroup 默认可聚焦：屏幕旋转重建输入视图后，框架会把
        // 窗口焦点分给第一个可聚焦视图，而它的默认焦点高亮（defaultFocusHighlight）
        // 渲染出来就是一层暗色遮罩——此前候选栏（993629e）、键盘两侧留白（9e32da21）、
        // 悬浮键盘表面都各自出现过，逐个补 isFocusable=false 是打地鼠。根视图直接
        // 屏蔽整个子树的焦点分配：任何视图都不再持有焦点，遮罩无从产生，触摸输入
        // 不依赖 view focus，不受影响。
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        isFocusable = false
        isFocusableInTouchMode = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            defaultFocusHighlightEnabled = false
        }
    }

    val themedContext = context.withTheme(android.R.style.Theme_DeviceDefault_Settings)

    private var candidateActionMenu: ImePopupMenu? = null

    fun showCandidateActionMenu(
        idx: Int,
        text: String,
        view: View,
        global: Boolean,
        onToggleFullDisplay: (() -> Unit)? = null,
    ) {
        candidateActionMenu?.dismiss()
        candidateActionMenu = null
        // 标题是 disabled 菜单项，文字色由菜单的系统主题提供（与原始 PopupMenu 行为一致）。
        // 不用 rime 主题色：tongwenfeng 的高亮候选是白字，而菜单底是系统浅色，白对白等于隐形；
        // rime 的配色是为候选栏的深浅背景设计的，套到系统菜单上没有可靠对比。
        val title = buildSpannedString {
            bold { append(text) }
        }
        service.lifecycleScope.launch {
            InputFeedbackManager.keyPressVibrate(view, longPress = true)
            candidateActionMenu =
                ImePopupMenu(view)
                    .item(title, enabled = false)
                    .item(R.string.forget_this_word) {
                        rime.runIfReady { deleteCandidate(idx, global) }
                    }
                    .apply {
                        // 「完整显示」只在候选窗口的长按菜单里出现（候选栏单行放不下
                        // 展开内容），且仅当注解确实被截断且尚未展开时由调用方传入，
                        // 见 PagedCandidatesUi.canToggleFullDisplay。
                        if (onToggleFullDisplay != null) {
                            item(R.string.show_full_comment) { onToggleFullDisplay.invoke() }
                        }
                    }
                    .apply {
                        onDismiss = { if (this === candidateActionMenu) candidateActionMenu = null }
                        show()
                    }
        }
    }

    private val navBarBackground by ThemeManager.prefs.navbarBackground

    private val navBarFrameHeight: Int
        get() {
            @SuppressLint("DiscouragedApi")
            val resId = resources.getIdentifier("navigation_bar_frame_height", "dimen", "android")
            return try {
                resources.getDimensionPixelSize(resId)
            } catch (_: Resources.NotFoundException) {
                dp(FALLBACK_NAVBAR_HEIGHT)
            }
        }

    private val ignoreSystemGestureInsets by AppPrefs.defaultInstance().advanced.ignoreSystemGestureInsets

    protected fun getNavBarBottomInset(windowInsets: WindowInsets): Int {
        if (navBarBackground != ThemePrefs.NavbarBackground.FULL) {
            return 0
        }
        val insets = WindowInsetsCompat.toWindowInsetsCompat(windowInsets)
        val navBars = WindowInsetsCompat.Type.navigationBars()
        val gestures = WindowInsetsCompat.Type.systemGestures() or
            WindowInsetsCompat.Type.mandatorySystemGestures()
        return if (ignoreSystemGestureInsets) {
            // check gestures insets and ignore them when it greater than zero
            val gesturesBottom = insets.getInsets(gestures).bottom
            val navBarsBottom = insets.getInsets(navBars).bottom
            if (gesturesBottom > 0) 0 else navBarsBottom
        } else {
            val insetsBottom = insets.getInsets(navBars or gestures).bottom
            if (insetsBottom > 0) max(insetsBottom, navBarFrameHeight) else insetsBottom
        }
    }

    fun dismissCandidateActionMenu() {
        candidateActionMenu?.dismiss()
        candidateActionMenu = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // on API 35+, we must call requestApplyInsets() manually after replacing views,
        // otherwise View#onApplyWindowInsets won't be called. ¯\_(ツ)_/¯
        requestApplyInsets()
    }

    override fun onDetachedFromWindow() {
        handleMessages = false
        dismissCandidateActionMenu()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val FALLBACK_NAVBAR_HEIGHT = 16
    }
}
