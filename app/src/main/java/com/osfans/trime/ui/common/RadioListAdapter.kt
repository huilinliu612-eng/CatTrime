/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.common

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import com.osfans.trime.R

/**
 * 单选弹框的自定义条目适配器。
 *
 * material 的单选列表条目（mtrl_alert_select_dialog_singlechoice）用 CheckedTextView +
 * `app:drawableLeftCompat` 画圆点，而 drawableLeftCompat 依赖 appcompat 注入的
 * LayoutInflater factory——只有 Activity 有，输入法路径弹框没有，圆点因此完全不绘制
 * （设置页有 factory 才正常）。主题层面对 singleChoiceItemLayout 的替换又会被
 * MaterialAlertDialogBuilder 后套的主题遮蔽，不可控。这里绕开所有路径：直接用自绘
 * ImageView + TextView 的条目布局传进 [androidx.appcompat.app.AlertDialog.Builder
 * .setSingleChoiceItems]，圆点按选中态切图，与 factory/主题遮蔽均无关。
 */
class RadioListAdapter(
    context: Context,
    private val items: List<String>,
    private var checkedPosition: Int,
) : ArrayAdapter<String>(context, 0, items) {
    fun setCheckedPosition(position: Int) {
        checkedPosition = position
        notifyDataSetChanged()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView
            ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.ime_dialog_radio_item, parent, false)
        view.findViewById<TextView>(R.id.radio_text).text = items[position]
        view.findViewById<ImageView>(R.id.radio_icon).setImageResource(
            if (position == checkedPosition) {
                R.drawable.static_radio_checked
            } else {
                R.drawable.static_radio_unchecked
            },
        )
        return view
    }
}
