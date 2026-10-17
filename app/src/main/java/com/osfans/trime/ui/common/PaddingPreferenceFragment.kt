/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.common

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.updatePadding
import androidx.preference.EditTextPreference
import androidx.preference.EditTextPreferenceDialogFragmentCompat
import androidx.preference.ListPreference
import androidx.preference.ListPreferenceDialogFragmentCompat
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.osfans.trime.R
import com.osfans.trime.util.applyNavBarInsetsBottomPadding
import splitties.dimensions.dp

/**
 * A fragment template that apply navigation bar window insets bottom padding
 *
 * Taken from fcitx5-android project.
 * Source: https://github.com/fcitx5-android/fcitx5-android/blob/dedfc18/app/src/main/java/org/fcitx/fcitx5/android/ui/common/PaddingPreferenceFragment.kt
 */
abstract class PaddingPreferenceFragment : PreferenceFragmentCompat() {
    override fun onCreateAdapter(
        preferenceScreen: PreferenceScreen,
    ): RecyclerView.Adapter<*> {
        applyMaterial3Layouts(preferenceScreen)
        // 禁用项的淡化走布局文字色 selector（preference_item_title/summary_text，
        // 视图状态驱动、无代码遍历），这里只负责给条目套 MD3 布局。
        return super.onCreateAdapter(preferenceScreen)
    }

    /**
     * EditText 与 List 类偏好的默认弹框都是 appcompat AlertDialog（白色直角），
     * 换成 MD3 构建器与全应用弹框统一。其余类型维持库默认。
     */
    override fun onDisplayPreferenceDialog(preference: Preference) {
        val key = preference.key ?: return super.onDisplayPreferenceDialog(preference)
        val fragment =
            when (preference) {
                is EditTextPreference -> MaterialEditTextPreferenceDialogFragment.newInstance(key)
                is ListPreference -> MaterialListPreferenceDialogFragment.newInstance(key)
                else -> return super.onDisplayPreferenceDialog(preference)
            }
        if (parentFragmentManager.findFragmentByTag(DIALOG_FRAGMENT_TAG) != null) return
        fragment.setTargetFragment(this, 0)
        fragment.show(parentFragmentManager, DIALOG_FRAGMENT_TAG)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ) = super.onCreateView(inflater, container, savedInstanceState).apply {
        listView.apply {
            removePreferenceDividers()
            setBackgroundColor(
                MaterialColors.getColor(
                    this,
                    com.google.android.material.R.attr.colorSurface,
                ),
            )
            clipToPadding = false
            updatePadding(
                left = context.dp(8),
                top = context.dp(8),
                right = context.dp(8),
                bottom = context.dp(24),
            )
            applyNavBarInsetsBottomPadding()
        }
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        // 兜底：不同 preference 库版本里 divider 装饰器可能在 onCreateView 之后才注册
        listView.removePreferenceDividers()
    }

    /**
     * 移除 androidx.preference 画在分类之间的分隔线。
     *
     * Material3 卡片布局本身已有分组视觉，原生分隔线紧贴上一张卡片底部，观感很差。
     * DividerDecoration 是库的私有实现，只能按索引移除 RecyclerView 上的全部装饰器
     * （本界面不会有其他装饰器，重复调用无副作用）。
     */
    private fun RecyclerView.removePreferenceDividers() {
        while (itemDecorationCount > 0) {
            removeItemDecorationAt(0)
        }
    }

    private fun applyMaterial3Layouts(group: PreferenceGroup) {
        for (index in 0 until group.preferenceCount) {
            val preference = group.getPreference(index)
            preference.layoutResource =
                when {
                    preference is PreferenceCategory && preference.title.isNullOrBlank() ->
                        R.layout.preference_category_spacer_material3
                    preference is PreferenceCategory -> R.layout.preference_category_material3
                    else -> R.layout.preference_material3
                }
            if (preference is PreferenceGroup) {
                applyMaterial3Layouts(preference)
            }
        }
    }

    companion object {
        /** Same tag string as the library uses, so the dedup check stays compatible. */
        private const val DIALOG_FRAGMENT_TAG = "androidx.preference.PreferenceFragmentCompat.DIALOG"
    }
}

/**
 * [EditTextPreferenceDialogFragmentCompat] 的 MD3 版本。
 *
 * androidx.preference 1.2.1 在 onCreateDialog 里内联 `new AlertDialog.Builder(context)`，
 * 没有 builder 钩子，只能整段复刻其构建流程（标题/图标/按钮/内容视图/输入法请求），
 * 仅把构建器换成 [materialAlertDialogBuilder]。
 */
class MaterialEditTextPreferenceDialogFragment : EditTextPreferenceDialogFragmentCompat() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val preference = getPreference()
        val builder = requireContext().materialAlertDialogBuilder()
            .setTitle(preference.dialogTitle ?: preference.title)
            .setIcon(preference.dialogIcon)
            .setPositiveButton(preference.positiveButtonText, this)
            .setNegativeButton(preference.negativeButtonText, this)
        val contentView = onCreateDialogView(requireContext())
        if (contentView != null) {
            onBindDialogView(contentView)
            builder.setView(contentView)
        } else {
            builder.setMessage(preference.dialogMessage)
        }
        onPrepareDialogBuilder(builder)
        val dialog = builder.create()
        if (needInputMethod()) {
            dialog.window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            )
        }
        return dialog
    }

    companion object {
        /**
         * 库的 [EditTextPreferenceDialogFragmentCompat.newInstance] 返回的是基类实例，
         * 这里必须自建，否则 show 出来的仍是原版弹框。参数键对应库的
         * `PreferenceDialogFragmentCompat.ARG_KEY`（protected，跨类引用不便，直接用其值）。
         */
        private const val ARG_KEY = "key"

        fun newInstance(key: String): MaterialEditTextPreferenceDialogFragment = MaterialEditTextPreferenceDialogFragment().apply {
            arguments = Bundle(1).apply { putString(ARG_KEY, key) }
        }
    }
}

/**
 * [ListPreferenceDialogFragmentCompat] 的 MD3 版本，构建方式同
 * [MaterialEditTextPreferenceDialogFragment]：1.2.1 的 onCreateDialog 内联
 * appcompat builder，没有钩子，只能复刻。列表弹框的库实现是「点选即应用并关闭」，
 * 无确认按钮监听，这里按同样语义复刻（记录点击项 → 标记为 BUTTON_POSITIVE → 关闭）。
 */
class MaterialListPreferenceDialogFragment : ListPreferenceDialogFragmentCompat() {
    private var clickedIndex = -1

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val preference = getPreference() as ListPreference
        val builder = requireContext().materialAlertDialogBuilder()
            .setTitle(preference.dialogTitle ?: preference.title)
            .setIcon(preference.dialogIcon)
            .setNegativeButton(preference.negativeButtonText, null)
        clickedIndex = preference.findIndexOfValue(preference.value)
        builder.setSingleChoiceItems(preference.entries, clickedIndex) { dialog, which ->
            clickedIndex = which
            // 库实现点击项后以 BUTTON_POSITIVE 结束流程让 onDialogClosed 应用所选值，
            // 随后必须手动 dismiss——单选列表弹框没有确认按钮，点击即完成。
            onClick(dialog, DialogInterface.BUTTON_POSITIVE)
            dialog.dismiss()
        }
        return builder.create()
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        val preference = getPreference() as ListPreference
        if (positiveResult && clickedIndex >= 0 && clickedIndex < preference.entryValues.size) {
            val value = preference.entryValues[clickedIndex].toString()
            if (preference.callChangeListener(value)) {
                preference.setValue(value)
            }
        }
    }

    companion object {
        private const val ARG_KEY = "key"

        fun newInstance(key: String): MaterialListPreferenceDialogFragment = MaterialListPreferenceDialogFragment().apply {
            arguments = Bundle(1).apply { putString(ARG_KEY, key) }
        }
    }
}
