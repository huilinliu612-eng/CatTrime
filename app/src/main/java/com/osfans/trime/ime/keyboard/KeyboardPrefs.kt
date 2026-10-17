/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.content.Context
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.util.isLandscape

object KeyboardPrefs {
    private val prefs = AppPrefs.defaultInstance()

    private const val WIDE_SCREEN_WIDTH_DP = 600

    fun Context.isLandscapeMode(): Boolean = when (prefs.keyboard.landscapeMode.getValue()) {
        AppPrefs.Keyboard.LandscapeMode.WIDE -> resources.configuration.isLandscape() || isWideScreen()
        AppPrefs.Keyboard.LandscapeMode.LANDSCAPE -> resources.configuration.isLandscape()
        AppPrefs.Keyboard.LandscapeMode.ALWAYS -> true
        else -> false
    }

    /**
     * 「有效悬浮」：当前方向是否应显示悬浮键盘。
     *
     * [AppPrefs.Keyboard.floatingKeyboard] 管理竖屏和横屏的常态悬浮；横屏自动悬浮
     * 只在实际横屏时额外启用悬浮，不修改前者——回到竖屏或重启后，竖屏是否悬浮
     * 仍严格按用户自己的开关。两者取或。为 false 时键盘回落普通（非悬浮）形态，
     * 悬浮宽高档位、keyboard_padding* 与系统底部边距忽略等悬浮专属逻辑都不再生效。
     * 注意与 [isLandscapeMode]（用户偏好判断，宽屏也算）区分。
     */
    fun Context.isFloatingEffective(): Boolean {
        val keyboard = prefs.keyboard
        return keyboard.floatingKeyboard.getValue() ||
            (
                resources.configuration.isLandscape() &&
                    keyboard.floatingKeyboardAutoLandscape.getValue()
                )
    }

    private fun Context.isWideScreen(): Boolean {
        val metrics = resources.displayMetrics
        return metrics.widthPixels / metrics.density > WIDE_SCREEN_WIDTH_DP
    }
}
