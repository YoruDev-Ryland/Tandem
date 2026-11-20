package dev.emusync.app.ui.kit

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.emusync.app.ui.input.Action
import dev.emusync.app.ui.input.OnAction

/**
 * Where the cursor goes when the list renders [keys]. If the rows changed since the last render ([prevKeys]), the
 * cursor follows its item ([focusKey]) to wherever it is now, so rows that arrive or leave above it do not move it to
 * another item. If that item is gone, or the rows did not change, [selected] stands, clamped to the list.
 */
internal fun reconcileCursor(prevKeys: List<Any>?, focusKey: Any?, keys: List<Any>, selected: Int): Int {
    if (keys.isEmpty()) return selected
    if (prevKeys != null && prevKeys != keys && focusKey != null) {
        val i = keys.indexOf(focusKey)
        if (i >= 0) return i
    }
    return selected.coerceIn(0, keys.lastIndex)
}

private class CursorMemory {
    var keys: List<Any>? = null
    var focusKey: Any? = null
}

/**
 * A list with a cursor. The cursor row is drawn inverted; up and down move it (a held D-pad repeats), A opens it.
 * Tapping a row moves the cursor there; tapping the cursor row opens it. The cursor follows its item by [key] when
 * the rows change. [selected] is hoisted: the list reports moves through [onSelect].
 *
 * Only an [active] list takes the cursor and the D-pad, so a screen with two lists marks the one being driven. The
 * list scrolls itself, so give it a bounded height (a weight) rather than putting it in another vertical scroll. An
 * empty list draws nothing; show an EmptyLine instead.
 */
@Composable
fun <T> CursorList(
    items: List<T>,
    key: (T) -> Any,
    selected: Int,
    onSelect: (Int) -> Unit,
    onOpen: (T) -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    row: @Composable RowScope.(T, focused: Boolean) -> Unit,
) {
    val memory = remember { CursorMemory() }
    val keys = items.map(key)
    val cursor = reconcileCursor(memory.keys, memory.focusKey, keys, selected)

    fun moveTo(i: Int) {
        memory.focusKey = keys[i]
        if (i != selected) onSelect(i)
    }

    SideEffect {
        memory.keys = keys
        if (keys.isNotEmpty()) {
            memory.focusKey = keys[cursor]
            if (cursor != selected) onSelect(cursor)
        }
    }

    OnAction(enabled = active && items.isNotEmpty()) { a ->
        when (a) {
            Action.Up, Action.Down -> {
                moveTo((cursor + if (a == Action.Down) 1 else -1).coerceIn(0, keys.lastIndex))
                true
            }
            Action.A -> {
                onOpen(items[cursor])
                true
            }
            else -> false
        }
    }

    val state = rememberLazyListState()
    if (active && items.isNotEmpty()) LaunchedEffect(cursor) { state.bringIntoView(cursor) }

    LazyColumn(modifier, state = state) {
        itemsIndexed(items, key = { i, _ -> keys[i] }) { i, item ->
            val focused = active && i == cursor
            CursorRow(
                focused = focused,
                onClick = { if (i == cursor) onOpen(item) else moveTo(i) },
                ruleAbove = !(active && i == cursor + 1),
            ) { row(item, focused) }
        }
    }
}

/**
 * Scrolls the least that shows row [index] whole: the cursor jumps, it never animates. Going down, the view then
 * starts on a whole row ([alignTop]), so the cut row is at the bottom edge and never under a header above the list.
 */
private suspend fun LazyListState.bringIntoView(index: Int) {
    val info = layoutInfo
    val start = info.viewportStartOffset
    val end = info.viewportEndOffset
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
    when {
        item == null -> {
            val first = info.visibleItemsInfo.firstOrNull()?.index ?: 0
            scrollToItem(index)
            // Coming from above, land it on the bottom edge rather than the top.
            if (index > first) {
                val placed = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
                scrollBy(-(end - start - placed.size).toFloat().coerceAtLeast(0f))
                alignTop(index)
            }
        }
        item.offset < start -> scrollBy((item.offset - start).toFloat())
        item.offset + item.size > end -> {
            scrollBy((item.offset + item.size - end).toFloat())
            alignTop(index)
        }
    }
}

/** If the first row in view is cut by the top edge, scrolls it out, as long as row [index] stays whole. */
private suspend fun LazyListState.alignTop(index: Int) {
    val info = layoutInfo
    val start = info.viewportStartOffset
    val first = info.visibleItemsInfo.firstOrNull() ?: return
    if (first.index == index || first.offset >= start) return
    val cursor = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val by = first.offset + first.size - start
    if (cursor.offset - by >= start) scrollBy(by.toFloat())
}
