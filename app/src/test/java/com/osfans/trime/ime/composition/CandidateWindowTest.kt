/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.composition

import com.osfans.trime.core.CandidateProto
import com.osfans.trime.core.Candidates
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class CandidateWindowTest :
    BehaviorSpec({
        fun page(
            count: Int,
            highlighted: Int = 0,
            hasPrevPage: Boolean = false,
            hasNextPage: Boolean = false,
        ) = Candidates.Paged(
            hasPrevPage = hasPrevPage,
            hasNextPage = hasNextPage,
            highlighted = highlighted,
            candidates =
            Array(count) {
                CandidateProto(text = "c$it", comment = "", label = "${it + 1}")
            },
        )

        fun vertical(
            page: Candidates.Paged,
            offset: Int = 0,
            perLine: Int,
        ) = candidateWindow(page, offset, perLine, horizontal = false)

        fun horizontal(
            page: Candidates.Paged,
            offset: Int = 0,
            perLine: Int,
        ) = candidateWindow(page, offset, perLine, horizontal = true)

        Given("a vertical layout and a page longer than the setting allows") {
            Then("the stack is cut to the setting, and the rest waits behind the arrows") {
                val window = vertical(page(count = 9), perLine = 4)
                window.page.candidates.map { it.text } shouldBe listOf("c0", "c1", "c2", "c3")
                window.capacity shouldBe 4
                window.page.hasNextPage shouldBe true
                window.page.hasPrevPage shouldBe false
            }

            Then("the arrows step the stack, exactly as they step a row") {
                vertical(page(count = 9), offset = 4, perLine = 4).page.candidates.map { it.text } shouldBe
                    listOf("c4", "c5", "c6", "c7")
                val last = vertical(page(count = 9), offset = 8, perLine = 4)
                last.page.candidates.map { it.text } shouldBe listOf("c8")
                last.page.hasNextPage shouldBe false
                last.page.hasPrevPage shouldBe true
            }

            Then("the highlight is reported against the slice, not the page") {
                vertical(page(count = 9, highlighted = 5), offset = 4, perLine = 4).page.highlighted shouldBe 1
                vertical(page(count = 9, highlighted = 0), offset = 4, perLine = 4).page.highlighted shouldBe -1
            }
        }

        Given("a vertical layout and a page that the setting already covers") {
            Then("the whole page is shown, unchanged") {
                val whole = page(count = 3, highlighted = 2, hasPrevPage = true, hasNextPage = true)
                vertical(whole, perLine = 4).page shouldBe whole
            }
        }

        Given("a horizontal layout and a page longer than the setting allows") {
            Then("the page is handed over whole, because the row wraps instead of cutting") {
                // A setting of 1 must not shrink the page to one candidate: per-candidate hiding
                // happens in the item, never by dropping candidates from the container.
                val whole = page(count = 6)
                val window = horizontal(whole, perLine = 1)
                window.page.candidates.map { it.text } shouldBe (0 until 6).map { "c$it" }
            }

            Then("the window's capacity is the whole page, so the arrows turn Rime's pages") {
                val window = horizontal(page(count = 6, hasPrevPage = true, hasNextPage = true), perLine = 1)
                window.capacity shouldBe 6
                steppedSlotOffset(0, window.capacity, pageCount = 6, forward = true) shouldBe null
                steppedSlotOffset(0, window.capacity, pageCount = 6, forward = false) shouldBe null
                window.page.hasPrevPage shouldBe true
                window.page.hasNextPage shouldBe true
            }

            Then("the row offset is not used, and the highlight stays with its candidate") {
                val window = horizontal(page(count = 6, highlighted = 4), offset = 3, perLine = 2)
                window.page.candidates.map { it.text } shouldBe (0 until 6).map { "c$it" }
                window.page.highlighted shouldBe 4
            }
        }

        Given("a page that fits one row") {
            Then("the page itself is shown, unchanged") {
                val whole = page(count = 3, highlighted = 2, hasPrevPage = true, hasNextPage = true)
                horizontal(whole, perLine = 3).page shouldBe whole
                horizontal(whole, perLine = 0).page shouldBe whole
            }
        }
    })
