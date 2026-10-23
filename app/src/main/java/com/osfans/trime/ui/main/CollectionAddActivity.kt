/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.osfans.trime.R
import com.osfans.trime.data.db.CollectionHelper
import com.osfans.trime.ui.common.materialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import splitties.systemservices.inputMethodManager

class CollectionAddActivity : Activity() {
    private val scope: CoroutineScope = MainScope()
    private var dialog: AlertDialog? = null
    private lateinit var editText: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content =
            layoutInflater.inflate(
                androidx.preference.R.layout.preference_dialog_edittext,
                null,
            )
        content.findViewById<TextView>(android.R.id.message).visibility = View.GONE
        editText =
            content.findViewById<EditText>(android.R.id.edit).apply {
                setHint(R.string.add_to_collection_hint)
                requestFocus()
            }
        dialog =
            materialAlertDialogBuilder()
                .setTitle(R.string.add_to_collection)
                .setView(content)
                .setPositiveButton(android.R.string.ok) { _, _ -> finishAdding() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
        dialog?.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
        inputMethodManager.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun finishAdding() {
        val str = editText.editableText.toString().trim()
        if (str.isNotEmpty()) {
            scope.launch(NonCancellable) {
                CollectionHelper.addNewBean(str)
            }
        }
        finish()
    }

    override fun onStop() {
        super.onStop()
        dialog?.dismiss()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        dialog?.dismiss()
        scope.cancel()
    }
}
