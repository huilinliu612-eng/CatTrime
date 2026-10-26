/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.bar

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.ViewAnimator
import android.widget.inline.InlineContentView
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.core.Candidates
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.db.ClipboardHelper
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.KeyActionManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.bar.ui.AlwaysUi
import com.osfans.trime.ime.bar.ui.CandidateUi
import com.osfans.trime.ime.bar.ui.KeyboardNavBar
import com.osfans.trime.ime.bar.ui.TabUi
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.candidates.compact.CompactCandidateDelegate
import com.osfans.trime.ime.candidates.unrolled.window.FlexboxUnrolledCandidateWindow
import com.osfans.trime.ime.bar.ui.ToolButton
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.dependency.InputDependencyManager
import com.osfans.trime.ime.keyboard.CommonKeyboardActionListener
import com.osfans.trime.ime.keyboard.KeyBehavior
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.link.AsrkbSpeechClient
import com.osfans.trime.link.AsrkbVoiceHoldSessionController
import com.osfans.trime.ime.switches.SwitchOptionWindow
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.ui.main.ClipEditActivity
import com.osfans.trime.util.AppUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * 「隐藏工具栏」只作用于 Always（工具栏态）；Candidate（候选态）与 Tab 态是
 * 液态键盘等外挂窗口，都必须始终可见——候选栏藏了就无法选词，Tab 态的返回
 * 按钮/标题栏都在工具栏上，藏了就无法从外挂窗口退出（用户实测卡死）。
 */
internal fun shouldShowInputBar(
    hideToolbar: Boolean,
    state: QuickBarStateMachine.State,
): Boolean = !hideToolbar || state != QuickBarStateMachine.State.Always

class InputBarDelegate : InputBroadcastReceiver {
    private val di = InputDependencyManager.getInstance().di
    private val context: Context by di.instance()
    private val service: TrimeInputMethodService by di.instance()
    private val theme: Theme by di.instance()
    private val windowManager: BoardWindowManager by di.instance()
    private val commonKeyboardActionListener: CommonKeyboardActionListener by di.instance()
    private val candidate: CompactCandidateDelegate by di.instance()
    private val rime: RimeSession by di.instance()

    val themedHeight = theme.generalStyle.run { candidateViewHeight + commentHeight }

    private val prefs = AppPrefs.defaultInstance()

    private val hideQuickBar by prefs.keyboard.hideInputBar

    private val clipboardSuggestion by prefs.clipboard.clipboardSuggestion

    private val clipboardSuggestionTimeout by prefs.clipboard.clipboardSuggestionTimeout

    private val keyboardWindow: KeyboardWindow by di.instance()
    private val asrkbVoiceHoldController by lazy {
        AsrkbVoiceHoldSessionController(
            service = service,
            showOverlay = { keyboardWindow.showAsrkbVoiceOverlay() },
            startWave = { keyboardWindow.startAsrkbVoiceOverlayWave() },
            updateAmplitude = { amplitude -> keyboardWindow.updateAsrkbVoiceOverlayAmplitude(amplitude) },
            hideOverlay = { keyboardWindow.hideAsrkbVoiceOverlay() },
            onSessionFinished = {
                alwaysUi.asrkbVoiceButton.setIcon(R.drawable.ic_baseline_mic_24)
            },
        )
    }
    private var asrkbHoldingChangedListener: ((Boolean) -> Unit)? = null

    /** 语音工具栏按钮是否应该显示：两个开关都开启才显示。 */
    private fun isAsrkbVoiceButtonEnabled(): Boolean =
        prefs.general.asrkbAidlVoiceInputEnabled.getValue() &&
            prefs.general.asrkbAidlVoiceToolbarButtonEnabled.getValue()

    /**
     * 重新计算工具栏语音按钮的可见性。
     *
     * 注意：这段逻辑必须在“每次键盘显示”与“设置变更”时都调用，
     * 不能只在 AlwaysUi 初始化时计算一次，否则用户后开关设置时图标永远不会出现。
     */
    fun refreshAsrkbVoiceButton(button: ToolButton = alwaysUi.asrkbVoiceButton) {
        if (!isAsrkbVoiceButtonEnabled()) {
            // 功能关闭：GONE，FlexboxLayout 会把 GONE 的子视图移出流式布局，
            // 因此不占位，主题按钮恢复原样。
            button.visibility = View.GONE
            return
        }
        // 功能开启：正常显示。按钮已在 buttonsUi 的序列里，
        // 与主题按钮一起参与 flex 布局，空间不足时有横向滚动，不会重叠。
        button.visibility = View.VISIBLE
        button.setIcon(
            if (AsrkbSpeechClient.isHolding()) {
                R.drawable.ic_baseline_stop_24
            } else {
                R.drawable.ic_baseline_mic_24
            },
        )
    }

    private var clipboardTimeoutJob: Job? = null

    private var isClipboardFresh: Boolean = false
    private var isInlineSuggestionPresent: Boolean = false

    @Keep
    private val onClipboardUpdateListener = ClipboardHelper.OnClipboardUpdateListener {
        if (!clipboardSuggestion) return@OnClipboardUpdateListener
        service.lifecycleScope.launch {
            if (it.text.isNullOrEmpty()) {
                isClipboardFresh = false
            } else {
                alwaysUi.clipboardUi.text.text = it.text.take(42)
                isClipboardFresh = true
                launchClipboardTimeoutJob()
            }
            evalAlwaysUiState()
        }
    }

    private fun launchClipboardTimeoutJob() {
        clipboardTimeoutJob?.cancel()
        val timeout = clipboardSuggestionTimeout * 1000L
        if (timeout < 0L) return
        clipboardTimeoutJob = service.lifecycleScope.launch {
            delay(timeout)
            isClipboardFresh = false
            clipboardTimeoutJob = null
            evalAlwaysUiState()
        }
    }

    private fun evalAlwaysUiState() {
        val newState =
            when {
                isClipboardFresh -> AlwaysUi.State.Clipboard
                isInlineSuggestionPresent -> AlwaysUi.State.InlineSuggestion
                else -> AlwaysUi.State.Toolbar
            }
        if (newState == alwaysUi.currentState) return
        alwaysUi.updateState(newState)
    }

    private val swipeDownHideKeyboardCallback: ((KeyBehavior) -> Unit) = { d ->
        if (d == KeyBehavior.SWIPE_DOWN) {
            service.requestHideSelf(0)
        }
    }

    /** 语音按钮在工具栏序列中的插入位置，0 = 最右（所有主题按钮之后）。 */
    private fun asrkbVoiceButtonPosition(): Int {
        val raw = runCatching {
            prefs.general.asrkbVoiceButtonPosition.getValue()
        }.getOrDefault("0")
        return raw.toIntOrNull()?.coerceAtLeast(0) ?: 0
    }
    private val alwaysUi: AlwaysUi by lazy {
        AlwaysUi(context, theme, asrkbVoicePosition = asrkbVoiceButtonPosition()) { action ->
            if (action.isNotEmpty()) {
                commonKeyboardActionListener.listener.onAction(KeyActionManager.getAction(action))
            } else {
                windowManager.attachWindow(SwitchOptionWindow())
            }
        }.apply {
            hideKeyboardButton.apply {
                setOnClickListener { service.requestHideSelf(0) }
                onSwipe = swipeDownHideKeyboardCallback
            }
            asrkbVoiceButton.apply {
                refreshAsrkbVoiceButton(this)
                setOnClickListener {
                    if (AsrkbSpeechClient.isHolding()) {
                        stopAsrkbVoiceFromToolbar()
                        return@setOnClickListener
                    }
                    if (!isAsrkbVoiceButtonEnabled()) {
                        return@setOnClickListener
                    }
                    startAsrkbVoiceFromToolbar()
                }
            }
            val executor = ContextCompat.getMainExecutor(service)
            val holdingListener: (Boolean) -> Unit = { holding ->
                executor.execute {
                    if (!asrkbVoiceButton.isAttachedToWindow) return@execute
                    if (asrkbVoiceButton.visibility != View.VISIBLE) return@execute
                    asrkbVoiceButton.setIcon(
                        if (holding) R.drawable.ic_baseline_stop_24 else R.drawable.ic_baseline_mic_24,
                    )
                }
            }
            asrkbHoldingChangedListener = holdingListener
            AsrkbSpeechClient.onHoldingChanged = holdingListener
            clipboardUi.suggestionView.apply {
                setOnClickListener {
                    val content = ClipboardHelper.lastBean?.text
                    content?.let { service.commitText(it) }
                    dismissClipboardSuggestion()
                }
                setOnLongClickListener {
                    ClipboardHelper.lastBean?.let {
                        AppUtils.launchClipEdit(context, it.id, ClipEditActivity.FROM_CLIPBOARD)
                    }
                    true
                }
            }
            clipboardUi.dismiss.setOnClickListener {
                dismissClipboardSuggestion()
            }
        }
    }

    private fun dismissClipboardSuggestion() {
        clipboardTimeoutJob?.cancel()
        clipboardTimeoutJob = null
        isClipboardFresh = false
        evalAlwaysUiState()
    }

    private val candidateUi by lazy {
        CandidateUi(context, theme, candidate.view).apply {
            unrollButton.apply {
                onSwipe = swipeDownHideKeyboardCallback
            }
        }
    }

    private val tabUi by lazy {
        TabUi(context, theme)
    }

    val navBar by lazy {
        KeyboardNavBar(
            context,
            theme,
            tabUi,
            onAttach = { barStateMachine.push(QuickBarStateMachine.TransitionEvent.BarBoardWindowAttached) },
            onDetach = { barStateMachine.push(QuickBarStateMachine.TransitionEvent.WindowDetached) },
        )
    }

    private val barStateMachine =
        QuickBarStateMachine.new {
            switchUiByState(it)
        }

    val unrollButtonStateMachine =
        UnrollButtonStateMachine.new {
            when (it) {
                UnrollButtonStateMachine.State.ClickToAttachWindow -> {
                    setUnrollButtonToAttach()
                    setUnrollButtonEnabled(true)
                }
                UnrollButtonStateMachine.State.ClickToDetachWindow -> {
                    setUnrollButtonToDetach()
                    setUnrollButtonEnabled(true)
                }
                UnrollButtonStateMachine.State.Hidden -> {
                    setUnrollButtonEnabled(false)
                }
            }
        }

    private fun setUnrollButtonToAttach() {
        candidateUi.unrollButton.setOnClickListener {
            windowManager.attachWindow(FlexboxUnrolledCandidateWindow())
        }
        candidateUi.unrollButton.setIcon(R.drawable.ic_baseline_expand_more_24)
    }

    private fun setUnrollButtonToDetach() {
        candidateUi.unrollButton.setOnClickListener {
            windowManager.attachWindow(KeyboardWindow)
        }
        candidateUi.unrollButton.setIcon(R.drawable.ic_baseline_expand_less_24)
    }

    private fun setUnrollButtonEnabled(enabled: Boolean) {
        candidateUi.unrollButton.visibility = if (enabled) View.VISIBLE else View.INVISIBLE
    }

    override fun onCandidateListUpdate(data: Candidates.Bulk) {
        barStateMachine.push(
            QuickBarStateMachine.TransitionEvent.CandidatesUpdated,
            QuickBarStateMachine.BooleanKey.CandidateEmpty to data.candidates.isEmpty(),
        )
    }

    private fun switchUiByState(state: QuickBarStateMachine.State) {
        val index = state.ordinal
        view.visibility =
            if (shouldShowInputBar(hideQuickBar, state)) View.VISIBLE else View.GONE
        view.background = barBackground(state)
        if (view.displayedChild == index) return
        val new = view.getChildAt(index)
        if (new != tabUi.root) {
            tabUi.setBackButtonOnClickListener { }
            tabUi.setTitle("")
            tabUi.removeExternal()
        }
        view.displayedChild = index
    }

    fun onHideInputBarChanged() {
        switchUiByState(barStateMachine.currentState)
    }

    val view by lazy {
        ViewAnimator(context).apply {
            visibility =
                if (shouldShowInputBar(hideQuickBar, QuickBarStateMachine.State.Always)) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            background = barBackground(QuickBarStateMachine.State.Always)
            add(alwaysUi.root, lParams(matchParent, matchParent))
            add(candidateUi.root, lParams(matchParent, matchParent))
            add(tabUi.root, lParams(matchParent, matchParent))

            evalAlwaysUiState()
            ClipboardHelper.addOnUpdateListener(onClipboardUpdateListener)
            // The helper keeps listeners in a static set, so it has to be
            // released when this view leaves the window -- otherwise it keeps
            // notifying a detached view after an input view rebuild.
            addOnAttachStateChangeListener(
                object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) = Unit

                    override fun onViewDetachedFromWindow(v: View) {
                        ClipboardHelper.removeOnUpdateListener(onClipboardUpdateListener)
                    }
                },
            )
            syncToolbarOptionStates()
        }
    }

    private fun barBackground(@Suppress("UNUSED_PARAMETER") state: QuickBarStateMachine.State): Drawable? = // 候选行是 IME 窗口里独立于悬浮卡片的一块视图——返回 null 不会让卡片背景
        // “透过来”，只会整条透明。任何模式（含悬浮+隐藏工具栏）都必须给出背景。
        ColorManager.getDecorDrawable(
            "candidate_background",
            "candidate_border_color",
            context.dp(theme.generalStyle.candidateBorder),
            context.dp(theme.generalStyle.candidateBorderRound),
        )

    override fun onStartInput(info: EditorInfo) {
        evalAlwaysUiState()
        // 每次开始输入时同步语音按钮可见性：用户从设置页返回后会立即生效。
        refreshAsrkbVoiceButton()
    }

    override fun onWindowAttached(window: BoardWindow) {
        // 每次键盘窗口附着到屏幕时重新计算语音按钮可见性，
        // 保证用户在设置里开关后切回键盘即可立即生效。
        refreshAsrkbVoiceButton()
        if (window is BoardWindow.BarBoardWindow) {
            tabUi.setTitle(window.title)
            window.onCreateBarView()?.let { tabUi.addExternal(it, window.showTitle) }
            tabUi.setBackButtonOnClickListener {
                windowManager.attachWindow(KeyboardWindow)
            }
            barStateMachine.push(QuickBarStateMachine.TransitionEvent.BarBoardWindowAttached)
        }
    }

    override fun onWindowDetached(window: BoardWindow) {
        barStateMachine.push(QuickBarStateMachine.TransitionEvent.WindowDetached)
    }

    private val suggestionSize by lazy {
        Size(ViewGroup.LayoutParams.WRAP_CONTENT, context.dp(themedHeight))
    }

    private val directExecutor by lazy {
        Executor { it.run() }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean {
        val suggestions = response.inlineSuggestions
        if (suggestions.isEmpty()) {
            isInlineSuggestionPresent = false
            return true
        }
        var pinned: InlineSuggestion? = null
        val scrollable = mutableListOf<InlineSuggestion>()
        var extraPinnedCount = 0
        suggestions.forEach {
            if (it.info.isPinned) {
                if (pinned == null) {
                    pinned = it
                } else {
                    scrollable.add(extraPinnedCount++, it)
                }
            } else {
                scrollable.add(it)
            }
        }
        service.lifecycleScope.launch {
            alwaysUi.inlineSuggestionsUi.setPinnedView(
                pinned?.let { inflateInlineContentView(it) },
            )
        }
        service.lifecycleScope.launch {
            val views = scrollable.map { s ->
                service.lifecycleScope.async {
                    inflateInlineContentView(s)
                }
            }.awaitAll()
            alwaysUi.inlineSuggestionsUi.setScrollableViews(views)
        }
        isInlineSuggestionPresent = true
        evalAlwaysUiState()
        return true
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun inflateInlineContentView(suggestion: InlineSuggestion): InlineContentView? = suspendCancellableCoroutine { c ->
        // callback view might be null
        suggestion.inflate(context, suggestionSize, directExecutor) { v ->
            c.resume(v)
        }
    }

    /**
     * Seed the toolbar toggle buttons with the current value of their rime
     * options. Rime access stays here in the delegate: the buttons themselves
     * are pure views and only react to [updateButtonsStyle].
     */
    private fun syncToolbarOptionStates() {
        val options = alwaysUi.toggleOptions()
        if (options.isEmpty()) return
        rime.launchOnReady { api ->
            val states = options.associateWith { api.getRuntimeOption(it) }
            ContextCompat.getMainExecutor(context).execute {
                states.forEach { (option, enabled) ->
                    alwaysUi.updateButtonsStyle(option, enabled)
                }
            }
        }
    }

    override fun onRimeOptionUpdated(value: RimeMessage.OptionMessage.Data) {
        alwaysUi.updateButtonsStyle(value.option, value.value)
    }

    private fun startAsrkbVoiceFromToolbar() {
        alwaysUi.asrkbVoiceButton.setIcon(R.drawable.ic_baseline_stop_24)
        asrkbVoiceHoldController.start()
    }

    private fun stopAsrkbVoiceFromToolbar() {
        asrkbVoiceHoldController.stop()
    }
}
