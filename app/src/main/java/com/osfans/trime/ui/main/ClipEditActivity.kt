/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.osfans.trime.R
import com.osfans.trime.data.db.ClipboardHelper
import com.osfans.trime.data.db.CollectionHelper
import com.osfans.trime.data.db.DatabaseBean
import com.osfans.trime.ui.common.materialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import splitties.systemservices.inputMethodManager
import timber.log.Timber

/**
 * 剪贴板条目编辑窗。宿主 Activity 完全透明（见 Theme.DialogTheme），真正的编辑窗
 * 是 [materialAlertDialogBuilder] 弹出的 MD3 对话框——与剪贴板过滤规则弹框完全同源：
 * 内容视图直接使用 androidx.preference 的 preference_dialog_edittext 布局（ScrollView
 * 上下 48dp 边距 + 20dp 边距的下划线 EditText），规则弹框正是 EditTextPreference
 * 默认样式（android:dialogLayout 指向该布局）弹出的。提示语以 placeholder（hint）
 * 形式放在输入框内，输入后自动消失。
 */
class ClipEditActivity : Activity() {
    private val scope: CoroutineScope = MainScope()
    private var beanId: Int = -1
    private var clipType: String? = null
    private lateinit var editText: EditText
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content =
            layoutInflater.inflate(
                androidx.preference.R.layout.preference_dialog_edittext,
                null,
            )
        // 「在此处编辑」以 placeholder 形式放在输入框内（EditText 原生 hint，输入后
        // 自动消失）；库布局的 message 说明行此场景没有内容，隐藏以免空行占位。
        content.findViewById<TextView>(android.R.id.message).visibility = View.GONE
        editText =
            content.findViewById<EditText>(android.R.id.edit).apply {
                setHint(R.string.clip_edit_text_hint)
                requestFocus()
            }
        dialog =
            materialAlertDialogBuilder()
                .setTitle(R.string.edit)
                .setView(content)
                .setPositiveButton(android.R.string.ok) { _, _ -> finishEditing() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
        // 从 IME 场景启动的透明宿主在 dialog 刚 show 时窗口焦点未就绪，裸 showSoftInput
        // 会被吞掉（用户需要再点一次输入框）。与 preference 库 needInputMethod 一致：
        // 在 dialog 窗口上声明 stateAlwaysVisible，窗口获得焦点时系统自动弹出键盘。
        dialog?.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
        inputMethodManager.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        processIntent(intent)
    }

    private fun finishEditing() {
        val str = editText.editableText.toString()
        scope.launch {
            when (clipType) {
                FROM_CLIPBOARD -> ClipboardHelper.updateText(beanId, str)
                FROM_COLLECTION -> CollectionHelper.updateText(beanId, str)
                else -> {}
            }
        }
        finish()
    }

    private fun setBean(bean: DatabaseBean) {
        beanId = bean.id
        editText.setText(bean.text)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        processIntent(intent)
    }

    private fun processIntent(intent: Intent) {
        scope.launch {
            intent.run {
                val clipType = intent.getStringExtra(CLIP_TYPE) ?: return@launch
                val beanId = getIntExtra(BEAN_ID, -1)
                Timber.d("processIntent: id=$beanId, type=$clipType")
                when (clipType) {
                    FROM_CLIPBOARD -> ClipboardHelper.get(beanId)
                    FROM_COLLECTION -> CollectionHelper.get(beanId)
                    else -> null
                }?.also {
                    this@ClipEditActivity.clipType = clipType
                    setBean(it)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // IME 场景：编辑期间收起键盘/切走即关闭，对话框随宿主一起消失。
        dialog?.dismiss()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        dialog?.dismiss()
        scope.cancel()
    }

    companion object {
        const val BEAN_ID = "id"
        const val CLIP_TYPE = "clip_type"
        const val FROM_CLIPBOARD = "from_clipboard"
        const val FROM_COLLECTION = "from_collection"
    }
}
