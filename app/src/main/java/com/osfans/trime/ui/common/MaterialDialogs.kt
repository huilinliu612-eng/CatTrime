/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.common

import android.content.Context
import android.util.TypedValue
import android.view.ContextThemeWrapper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.osfans.trime.R

/** Creates an MD3 dialog without assuming that the caller already has a Material-themed context. */
fun Context.materialAlertDialogBuilder(): MaterialAlertDialogBuilder {
    val value = TypedValue()
    val isMaterial3 =
        theme.resolveAttribute(
            com.google.android.material.R.attr.isMaterial3Theme,
            value,
            true,
        ) && value.data != 0
    val dialogContext =
        if (isMaterial3) {
            this
        } else {
            ContextThemeWrapper(this, R.style.Theme_TrimeAppTheme)
        }
    return MaterialAlertDialogBuilder(dialogContext)
}

/**
 * 强制套上应用 MD3 主题的 context。
 *
 * 输入法服务注入的 context 主题不可控（di 绑定、manifest 声明都可能变化），而 MD3 弹框的
 * 次级样式（按钮色、列表样式）从 **builder 的 context** 解析。单选条目的圆点不在这里处理：
 * 主题层替换条目布局会被 MaterialAlertDialogBuilder 后套的主题遮蔽，mtrl 条目的
 * `drawableLeftCompat` 又依赖 appcompat 的 LayoutInflater factory（只有 Activity 有），
 * 两条路都走不通，统一改由 [RadioListAdapter] 自绘条目。键盘路径打开弹框前先经此包装。
 */
fun Context.withMaterialTheme(): Context = ContextThemeWrapper(this, R.style.Theme_TrimeAppTheme)
