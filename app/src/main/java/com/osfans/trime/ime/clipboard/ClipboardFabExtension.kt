/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.clipboard

import android.content.Context
import android.content.Intent
import androidx.core.view.isVisible
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.osfans.trime.R
import com.osfans.trime.ui.common.withMaterialTheme
import com.osfans.trime.ui.main.CollectionAddActivity
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.core.add

fun ClipboardLayout.setupCollectionFab(
    viewPager: ViewPager2,
    context: Context,
) {
    val fab =
        FloatingActionButton(context.withMaterialTheme()).apply {
            size = FloatingActionButton.SIZE_MINI
            setImageResource(R.drawable.ic_baseline_add_24)
            contentDescription = context.getString(R.string.add_to_collection)
        }

    add(
        fab,
        lParams(dp(40), dp(40)) {
            bottomOfParent(dp(12))
            endOfParent(dp(12))
        },
    )

    fab.setOnClickListener {
        context.startActivity(
            Intent(context, CollectionAddActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    viewPager.registerOnPageChangeCallback(
        object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                fab.isVisible = position == 1
            }
        },
    )

    fab.isVisible = viewPager.currentItem == 1
}
