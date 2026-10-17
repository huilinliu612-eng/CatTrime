/*
 * SPDX-FileCopyrightText: 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.composition

import com.osfans.trime.core.Candidates

/**
 * Geometry of the popup candidate window -- one row of candidates, or one column when the layout
 * stacks them.
 *
 * The two layouts differ in what limits them:
 *
 * - A stacked column runs out of *height*. Rime pages the menu itself and the page size is a
 *   schema setting (`menu/page_size`), so a page regularly holds more candidates than the
 *   window may stack: the window shows [perLine] of them and its arrows step that window, only
 *   turning Rime's page once the window reaches the end of the page.
 * - A row runs out of *width*, and nothing caps the number of candidates it shows: the flex line
 *   wraps instead. Each candidate is limited on its own (the "hide overflowing candidate lines"
 *   setting), so the page is handed over whole and the arrows turn Rime's pages directly.
 *
 * Cutting the row down instead would be simpler, but the extra candidates would become
 * unreachable -- the arrows turn Rime's page, not the window, so they would be skipped over.
 */

/** First offset of the window that holds [highlighted], rounded down to a whole window. */
internal fun slotOffsetFor(
    highlighted: Int,
    perLine: Int,
): Int {
    if (perLine <= 0) return 0
    return highlighted.coerceAtLeast(0) / perLine * perLine
}

/** Offset of the last window of a page of [pageCount] candidates. */
internal fun lastSlotOffset(
    pageCount: Int,
    perLine: Int,
): Int {
    if (perLine <= 0 || pageCount <= 0) return 0
    return (pageCount - 1) / perLine * perLine
}

/**
 * Offset of the window one step away, or `null` when the step has to leave the page.
 *
 * A `null` means the caller should ask Rime for the neighbouring page and start over at the
 * window holding its highlighted candidate.
 */
internal fun steppedSlotOffset(
    offset: Int,
    perLine: Int,
    pageCount: Int,
    forward: Boolean,
): Int? {
    if (perLine <= 0) return null
    val next = if (forward) offset + perLine else offset - perLine
    if (next < 0 || next >= pageCount) return null
    return next
}

/** Whether anything is left before a window at [offset], in this page or an earlier one. */
internal fun slotHasPrev(
    offset: Int,
    hasPrevPage: Boolean,
): Boolean = offset > 0 || hasPrevPage

/** Whether anything is left after a window at [offset], in this page or a later one. */
internal fun slotHasNext(
    offset: Int,
    perLine: Int,
    pageCount: Int,
    hasNextPage: Boolean,
): Boolean = offset + perLine < pageCount || hasNextPage

/**
 * The window at [offset], with the paging flags remapped onto it.
 *
 * A page that already fits is returned untouched, so a window size that is at least the page size
 * behaves exactly like the page itself.
 */
internal fun windowOf(
    page: Candidates.Paged,
    offset: Int,
    perLine: Int,
): Candidates.Paged {
    if (perLine <= 0) return page
    val count = page.candidates.size
    if (count <= perLine) return page
    val from = offset.coerceIn(0, count - 1)
    val slice = page.candidates.drop(from).take(perLine)
    // -1 means "nothing on screen is highlighted", which is what browsing past the highlighted
    // candidate looks like; PagedCandidatesUi simply matches no position.
    val highlighted = (page.highlighted - from).takeIf { it in slice.indices } ?: -1
    return page.copy(
        candidates = slice.toTypedArray(),
        highlighted = highlighted,
        hasPrevPage = slotHasPrev(from, page.hasPrevPage),
        hasNextPage = slotHasNext(from, perLine, count, page.hasNextPage),
    )
}

/** The candidates a window shows. */
internal data class CandidateWindow(
    val page: Candidates.Paged,
    /**
     * How many candidates one window holds -- what the arrows have to step by.
     *
     * A row hands back the whole page, so its capacity is the page size and stepping leaves
     * Rime's page immediately.
     */
    val capacity: Int,
)

/**
 * The window to show for [page] at [offset].
 *
 * - A column is cut to [perLine], because an unbounded stack would cover the screen; [offset]
 *   selects which slice of the page it shows.
 * - A row shows the page whole: the line wraps when the candidates do not share one row, and
 *   [offset] is not used. The per-candidate line limit (and long-press expansion) lives in the
 *   item, not in the container.
 */
internal fun candidateWindow(
    page: Candidates.Paged,
    offset: Int,
    perLine: Int,
    horizontal: Boolean,
): CandidateWindow = if (horizontal) {
    CandidateWindow(
        page = page,
        capacity = page.candidates.size,
    )
} else {
    CandidateWindow(
        page = windowOf(page, offset, perLine),
        capacity = perLine.coerceAtLeast(1),
    )
}
