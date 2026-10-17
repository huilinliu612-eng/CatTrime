/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.res.use
import com.osfans.trime.R
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.resources.styledDrawable
import splitties.views.dsl.core.withTheme
import kotlin.math.max
import kotlin.math.min

/**
 * A drop-down menu that is safe to show from inside the input method window.
 *
 * `android.widget.PopupMenu` cannot be used here. It is built on `ListPopupWindow`, whose
 * constructor calls `setInputMethodMode(INPUT_METHOD_NEEDED)` on its internal `PopupWindow`.
 * Because that popup is also not focusable, `PopupWindow.computeFlags()` then adds
 * `FLAG_ALT_FOCUSABLE_IM` — the flag documented to block *every view below it* from connecting
 * to the input method. With it set the editor stops talking to the IME: the framework drops the
 * input session (`onStartInput(restarting = true)`), the input method falls back to its main
 * keyboard and detaches whatever board window the menu was anchored in, which dismisses the
 * menu. The user sees the panel they were in flash away the moment they long press — the
 * clipboard list, say. (`FLAG_ALT_FOCUSABLE_IM` is also what used to break the theme pickers;
 * see `TrimeInputMethodService.showDialog`.)
 *
 * This popup keeps that flag off: it is not focusable, and `INPUT_METHOD_NOT_NEEDED` only adds
 * the flag above when the popup *is* focusable. The editor therefore keeps its input
 * connection and the panel stays put. Anchoring to a view in the input method makes it a child
 * window of the input method, so it is drawn just above the keyboard and goes away with it.
 */
class ImePopupMenu(anchor: View) {
    private val anchorView = anchor

    /**
     * 菜单的取色基座：应用自己的 MD3 主题（含动态色），而不是 `Theme_DeviceDefault`。
     * 之前用系统主题解析 `textColorPrimary` 等属性，得到的是 ROM 的设备默认灰阶，
     * 与应用内其余界面（Material 弹框、设置页）的动态色脱节；换到 MD3 主题后，同一组
     * framework 属性都会解析为应用配色（Android 12+ 为壁纸动态色，低版本为 M3 基线）。
     */
    private val ctx: Context =
        anchor.context.withTheme(R.style.Theme_TrimeAppTheme)

    private val panelBackground =
        GradientDrawable().apply {
            setColor(colorOf(android.R.attr.colorBackgroundFloating, Color.DKGRAY))
            cornerRadius = ctx.dp(12).toFloat()
        }

    /**
     * The panel background: it paints nothing (the visible panel is [panelBackground] on [rows]),
     * but it hands the panel an outline, which is what the elevation shadow needs — a view only
     * casts a shadow from its outline, and a plain transparent background has an empty one.
     * Reporting the panel's rounded rect here gives the menu a shadow that follows its corners.
     * Reporting `TRANSLUCENT` keeps the window format translucent: the popup's pixel format is
     * taken from the background's opacity, and an opaque one would fill the area outside the
     * panel with black. This drawable lives on [scroller] (not on the popup window) because the
     * window is stretched to the whole screen (see [popup]) — an outline there would describe
     * the screen, not the menu.
     */
    private val panelOutline =
        object : Drawable() {
            override fun draw(canvas: Canvas) = Unit
            override fun setAlpha(alpha: Int) = Unit
            override fun setColorFilter(colorFilter: ColorFilter?) = Unit

            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
            override fun getOutline(outline: Outline) {
                outline.setRoundRect(bounds, ctx.dp(12).toFloat())
            }
        }

    private val rows =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ctx.dp(4), 0, ctx.dp(4))
            background = panelBackground
        }

    /**
     * Menus are short, but the panel is capped to whatever room is actually left (see [show]), so
     * anything that does not fit still has to be reachable.
     */
    private val scroller =
        ScrollView(ctx).apply {
            addView(rows, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            isFillViewport = true
            // The elevation shadow follows this view's outline (see [panelOutline]); it renders
            // inside the popup's window surface, which is bigger than the panel, so nothing is
            // clipped away.
            background = panelOutline
            elevation = ctx.dp(8).toFloat()
        }

    /** The panel's slot inside the full-screen [backdrop]; [show] rewrites it before showing. */
    private val panelParams =
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START,
        )

    /**
     * 全屏透明背板：popup window 拉伸到整屏后，菜单外面的触摸全部落在背板上——点击即
     * 关闭菜单并就地消费，不再像小窗那样抄送 dismiss 后继续把事件传给下层键盘。
     * 面板自身背景不透明、容器可滚动，触摸不会落到背板，面板内的滚动与点击不受影响。
     */
    private val backdrop =
        FrameLayout(ctx).apply {
            addView(scroller, panelParams)
            setOnClickListener { dismiss() }
        }

    private val popup =
        PopupWindow(ctx).apply {
            contentView = backdrop
            // 全屏化：菜单外的点击由 [backdrop] 消费并关闭，且不会穿透到下层键盘。
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = ViewGroup.LayoutParams.MATCH_PARENT
            // Both of these matter, see the class comment.
            isFocusable = false
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            // 关闭逻辑已由背板接管，窗口不再依赖 outside-touch 抄送。
            isOutsideTouchable = false
            // 窗口背景仅用于把窗口格式上报为 TRANSLUCENT（outline 与阴影都已移到
            // [scroller] 上，见 [panelOutline] 注释）。
            setBackgroundDrawable(panelOutline)
            setOnDismissListener { onDismiss?.invoke() }
        }

    /** Called when the menu goes away, however it goes away. */
    var onDismiss: (() -> Unit)? = null

    val isShowing: Boolean get() = popup.isShowing

    /**
     * Resolves a themed colour attribute to a concrete colour int.
     *
     * Two traps live here. `splitties`' [styledColor] reads the attribute with
     * `TypedArray.getColor`, which only handles direct colour ints — an attribute backed by a
     * colour *state list* (as most framework text colours are) silently comes back as the
     * fallback, i.e. transparent. And `TypedArray.getColor` throws for references it cannot
     * coerce. Going through [getColorStateList] handles both: state lists resolve to their
     * default colour, direct colours still work, and anything unresolved falls back.
     */
    private fun colorOf(
        @AttrRes attr: Int,
        fallback: Int,
    ): Int = runCatching {
        ctx.obtainStyledAttributes(intArrayOf(attr)).use { ta ->
            ta.getColorStateList(0)?.defaultColor ?: fallback
        }
    }.getOrDefault(fallback)

    private fun addRow(
        title: CharSequence,
        @DrawableRes icon: Int,
        @ColorInt iconTint: Int,
        enabled: Boolean,
        iconAtEnd: Boolean,
        onClick: Function0<Any?>?,
    ) {
        val color =
            if (enabled) {
                colorOf(android.R.attr.textColorPrimary, Color.WHITE)
            } else {
                colorOf(android.R.attr.textColorTertiary, Color.GRAY)
            }
        val row =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.dp(20), ctx.dp(12), ctx.dp(20), ctx.dp(12))
                minimumWidth = ctx.dp(180)
                // IME controls must not take focus away from the active editor.
                isFocusable = false
            }
        if (enabled && onClick != null) {
            row.isClickable = true
            row.background = ctx.styledDrawable(android.R.attr.selectableItemBackground)
            row.setOnClickListener {
                dismiss()
                onClick.invoke()
            }
        }
        val label =
            TextView(ctx).apply {
                text = title
                textSize = 16f
                setTextColor(color)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }
        if (icon != 0 && iconAtEnd) {
            // 选中标记等“状态图标”放行尾：行首图标留给动作，行尾勾选是各平台
            // 选择列表的惯例，且不会被左侧图标列挤压文字。
            row.addView(
                label,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    gravity = Gravity.CENTER_VERTICAL
                },
            )
            val iconView =
                ImageView(ctx).apply {
                    setImageDrawable(
                        ctx.drawable(icon)?.apply {
                            setTint(if (iconTint != 0) iconTint else color)
                        },
                    )
                }
            row.addView(
                iconView,
                LinearLayout.LayoutParams(ctx.dp(20), ctx.dp(20)).apply { marginStart = ctx.dp(16) },
            )
        } else {
            if (icon != 0) {
                val iconView =
                    ImageView(ctx).apply {
                        setImageDrawable(
                            ctx.drawable(icon)?.apply {
                                setTint(if (iconTint != 0) iconTint else color)
                            },
                        )
                    }
                row.addView(
                    iconView,
                    LinearLayout.LayoutParams(ctx.dp(20), ctx.dp(20)).apply { marginEnd = ctx.dp(16) },
                )
            }
            row.addView(
                label,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { gravity = Gravity.CENTER_VERTICAL },
            )
        }
        rows.addView(row)
    }

    fun item(
        @StringRes title: Int,
        @DrawableRes icon: Int = 0,
        @ColorInt iconTint: Int = 0,
        enabled: Boolean = true,
        iconAtEnd: Boolean = false,
        onClick: Function0<Any?>? = null,
    ): ImePopupMenu = item(ctx.getString(title), icon, iconTint, enabled, iconAtEnd, onClick)

    fun item(
        title: CharSequence,
        @DrawableRes icon: Int = 0,
        @ColorInt iconTint: Int = 0,
        enabled: Boolean = true,
        iconAtEnd: Boolean = false,
        onClick: Function0<Any?>? = null,
    ): ImePopupMenu {
        addRow(title, icon, iconTint, enabled, iconAtEnd, onClick)
        return this
    }

    /**
     * Shows the menu next to the anchor, flipping it to whichever side has more room and capping
     * its height to that room.
     *
     * `PopupMenu` gets this for free from `ListPopupWindow`, which measures the space left and
     * shrinks the list to fit. A plain `showAsDropDown` does not: a menu anchored near the bottom
     * of the keyboard runs off the bottom of the screen and its last entries become unreachable,
     * which is exactly what it looks like when the clipboard list only shows its first few
     * actions.
     *
     * The popup window itself is full-screen (see [popup]), so the menu can no longer be placed
     * with `showAsDropDown`; instead the window goes up at the host window's origin and the panel
     * is positioned inside it through [panelParams] margins.
     */
    fun show() {
        if (popup.isShowing) popup.dismiss()

        rows.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val menuWidth = rows.measuredWidth
        val menuHeight = rows.measuredHeight

        val anchorLocation = IntArray(2)
        anchorView.getLocationOnScreen(anchorLocation)
        val anchorLeft = anchorLocation[0]
        val anchorTop = anchorLocation[1]
        val anchorBottom = anchorTop + anchorView.height

        // A panel window is a child of the window it is anchored in, and it is also clipped by the
        // screen. Which of the two bounds wins is not something worth relying on, so take the
        // tighter one — the menu then fits under either.
        val visibleFrame = Rect()
        anchorView.getWindowVisibleDisplayFrame(visibleFrame)
        val rootLocation = IntArray(2)
        val root = anchorView.rootView
        root.getLocationOnScreen(rootLocation)
        val availableTop = max(visibleFrame.top, rootLocation[1])
        val availableBottom = min(visibleFrame.bottom, rootLocation[1] + root.height)

        // 水平位置：默认贴齐锚点左边，超出宿主窗口右缘时往回收。面板保持
        // WRAP_CONTENT，不会被全屏窗口拉伸。
        val windowRight = rootLocation[0] + root.width
        val x = min(anchorLeft, windowRight - menuWidth).coerceAtLeast(rootLocation[0]) - rootLocation[0]

        if (menuHeight <= 0) {
            // Nothing meaningful to measure against; drop the panel below the anchor as-is.
            showPanel(x, anchorBottom - rootLocation[1], ViewGroup.LayoutParams.WRAP_CONTENT)
            return
        }

        val spaceBelow = availableBottom - anchorBottom
        val spaceAbove = anchorTop - availableTop
        val dropDown = spaceBelow >= menuHeight || spaceBelow >= spaceAbove
        val space = if (dropDown) spaceBelow else spaceAbove

        if (space <= 0) {
            showPanel(x, anchorBottom - rootLocation[1], ViewGroup.LayoutParams.WRAP_CONTENT)
            return
        }

        val shownHeight = min(menuHeight, space)
        // 向下弹出时面板顶在锚点底部；向上翻转时面板底贴在锚点顶部（与原
        // showAsDropDown 的翻转偏移等价：面板顶 = 锚点顶 - 面板高）。
        val y = if (dropDown) anchorBottom else anchorTop - shownHeight
        showPanel(x, y - rootLocation[1], shownHeight)
    }

    /** Places the panel inside the full-screen window at window coordinates ([x], [y]). */
    private fun showPanel(
        x: Int,
        y: Int,
        height: Int,
    ) {
        panelParams.leftMargin = x
        panelParams.topMargin = y
        panelParams.height = height
        popup.showAtLocation(anchorView.rootView, Gravity.TOP or Gravity.START, 0, 0)
    }

    fun dismiss() {
        popup.dismiss()
    }
}
