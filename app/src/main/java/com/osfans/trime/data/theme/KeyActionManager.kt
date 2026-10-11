// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme

import com.osfans.trime.data.theme.model.KeyActionToken
import com.osfans.trime.data.theme.model.PresetKey
import com.osfans.trime.ime.keyboard.KeyAction

object KeyActionManager {
    private val actionCache = mutableMapOf<KeyActionToken, KeyAction>()

    fun getAction(token: String) = getAction(KeyActionToken.Plain(token))

    fun getAction(token: KeyActionToken): KeyAction = actionCache.getOrPut(token) { KeyAction(token) }

    /** Build an explicit function-key command; a plain token would be treated as text. */
    fun getCommandAction(command: String): KeyAction = KeyAction(
        KeyActionToken.Plain(command),
        mapOf(command to PresetKey(command = command)),
    )

    fun resetCache() = actionCache.clear()
}
