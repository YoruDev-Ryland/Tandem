package dev.tandem.app.ui.screens

import dev.tandem.app.ui.theme.spineKind
import dev.tandem.app.ui.theme.SpineKind
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.derivedStateOf
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.tandem.app.ui.feedback.Cue
import dev.tandem.app.ui.feedback.LocalCueSink
import dev.tandem.app.ui.input.Action
import dev.tandem.app.ui.input.OnAction
import dev.tandem.app.ui.kit.ConsoleScaffold
import dev.tandem.app.ui.kit.EmptyLine
import dev.tandem.app.ui.kit.EmuButton
import dev.tandem.app.ui.kit.EmuRadius
import dev.tandem.app.ui.kit.ErrorLine
import dev.tandem.app.ui.kit.Glyph
import dev.tandem.app.ui.kit.Hint
import dev.tandem.app.ui.kit.LocalTabEntrance
import dev.tandem.app.ui.kit.PresenceMark
import dev.tandem.app.ui.kit.Tone
import dev.tandem.app.ui.kit.noKeyFocus
import dev.tandem.app.ui.kit.tone
import dev.tandem.app.ui.state.EmuShelf
import dev.tandem.app.ui.state.Notice
import dev.tandem.app.ui.state.RailRow
import dev.tandem.app.ui.state.ShelfCard
import dev.tandem.app.ui.state.ShelfCursor
import dev.tandem.app.ui.state.ShelfUi
import dev.tandem.app.ui.state.Spine
import dev.tandem.app.ui.kit.Presence
import dev.tandem.app.ui.theme.EmuFonts
import dev.tandem.app.ui.theme.EmuType
import dev.tandem.app.ui.theme.LocalEmu
import dev.tandem.app.ui.theme.Spines

/** The gap between spines and the pulled-out card's width (spec: 5 and 262 dp). A spine's width is its case's ratio. */
internal val SPINE_GAP = 5.dp
private const val PREFETCH_AROUND = 12   // spines either side of the view whose art is fetched ahead
internal val CARD_W = 262.dp

private val EDGE = 6.dp            // the shelf edge, in ink
private val RAIL_GAP = 10.dp       // from the edge to the first rail row
private val RAIL_ROW = 18.dp       // one device's row of marks
private val GUTTER = 44.dp         // the rail's labels, left of the shelf
private val COVER_W = 112.dp
private val COVER_H = 156.dp
private val SPINE_TOP = 30.dp      // how much shorter a spine stands than the card

private val spineTitle = TextStyle(fontFamily = EmuFonts.BarlowSemiCondensed, fontWeight = FontWeight.Bold, fontSize = 14.sp)
private val spineKey = TextStyle(fontFamily = EmuFonts.JetBrainsMono, fontSize = 12.sp)
private val spineCode = TextStyle(fontFamily = EmuFonts.BarlowSemiCondensed, fontWeight = FontWeight.Bold, fontSize = 9.sp, letterSpacing = 0.06.em)
private val railLabel = TextStyle(fontFamily = EmuFonts.JetBrainsMono, fontSize = 10.sp)

/**
 * The Library tab: one shelf per emulator. The strip names the emulators (Up and Down switch); the shelf holds a spine
 * per game, coloured by its system, and the game under the cursor pulled out as a card with its cover and its state
 * (Left and Right move). Under the shelf edge the status rail shows, for every game, a mark per device, so what is
 * synced where reads without moving the cursor. The notice line says what waits for the emulator to close, or what
 * just landed. A opens the game's versions on the web, X syncs now, Y opens the emulator's settings.
 *
 * [art] gives the cover for the focused game, or null while there is none (the card then shows the system's code).
 * [artNow] is the cover only if it is at hand already, without loading: the card folding back into its spine shows it.
 */
@Composable
fun ShelfScreen(
    ui: ShelfUi,
    top: @Composable RowScope.() -> Unit,
    actions: DeviceActions,
    art: @Composable (emulatorId: String, gameKey: String) -> ImageBitmap? = { _, _ -> null },
    artNow: (emulatorId: String, gameKey: String) -> ImageBitmap? = { _, _ -> null },
    spineArt: @Composable (Spine) -> SpineImages = { SpineImages() },
    prefetch: (spines: List<Spine>, first: Int, last: Int) -> Unit = { _, _, _ -> },
) {
    // -1: the user hasn't chosen yet, so the shelf follows the model's pick (the first emulator with games).
    var chosen by rememberSaveable { mutableIntStateOf(-1) }
    val emuAt = (if (chosen < 0) ui.emulator else chosen).coerceIn(0, (ui.shelves.size - 1).coerceAtLeast(0))
    // Once the games are in, the shelf stays on its emulator even when its games go.
    SideEffect { if (chosen < 0 && ui.loaded && ui.signedIn && ui.shelves.isNotEmpty()) chosen = emuAt }
    val shelf = ui.shelves.getOrNull(emuAt)
    val spines = if (ui.signedIn) shelf?.spines.orEmpty() else emptyList()
    val cursor = remember { ShelfCursor() }
    var moves by remember { mutableIntStateOf(0) }   // read below, so a cursor move recomposes
    val at = remember(moves, emuAt, spines) { shelf?.let { cursor.indexFor(it.tab.id, spines) } ?: 0 }
    val focused = spines.getOrNull(at)
    // The cursor stays on the game it was on when the list changes; when that game left, on the one it landed on.
    SideEffect { if (shelf != null && focused != null) cursor.moveTo(shelf.tab.id, focused.gameKey) }

    fun moveTo(i: Int) {
        val s = shelf ?: return
        cursor.moveTo(s.tab.id, spines[i].gameKey)
        moves++
    }
    fun switchTo(i: Int) { chosen = i; moves++ }
    // The hints outlive this composition's values (the scaffold keeps them), so they read the current ones.
    val focusedNow by rememberUpdatedState(focused)
    val shelfNow by rememberUpdatedState(shelf)
    val open: () -> Unit = { focusedNow?.let { actions.openGame(it.emulatorId, it.gameKey) } }

    val hints = when {
        ui.revoked -> listOf(Hint(Action.A, "Sign in again", actions.pairAgain), Hint(Action.B, "Back", actions.back))
        !ui.signedIn -> listOf(Hint(Action.A, "Sign in", actions.setup), Hint(Action.B, "Back", actions.back))
        else -> listOf(
            Hint(Action.A, "Open", open),
            Hint(Action.X, "Sync now", actions.syncNow),
            Hint(Action.Y, "Emulator settings") { shelfNow?.let { actions.settings(it.tab.id) } },
            Hint(Action.B, "Back", actions.back),
        )
    }

    ConsoleScaffold(top = top, hints = hints) {
        if (ui.revoked) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                BasicText(REVOKED_LINE, Modifier.padding(top = 4.dp, bottom = 8.dp), style = EmuType.title.copy(color = LocalEmu.current.fault))
                BasicText(REVOKED_REST, Modifier.widthIn(max = 560.dp).padding(bottom = 16.dp), style = EmuType.body.copy(color = LocalEmu.current.ink2))
                EmuButton("Sign in again", primary = true, glyph = Action.A, onClick = actions.pairAgain)
            }
            return@ConsoleScaffold
        }
        val cues = LocalCueSink.current
        OnAction(enabled = ui.signedIn) { a ->
            when (a) {
                Action.Left, Action.Right -> {
                    val to = at + if (a == Action.Right) 1 else -1
                    if (to in spines.indices) { cues?.cue(Cue.Move); moveTo(to) } else cues?.cue(Cue.Reject)   // no wrap
                    true
                }
                Action.Up, Action.Down -> {
                    val to = emuAt + if (a == Action.Down) 1 else -1
                    if (to in ui.shelves.indices) { cues?.cue(Cue.Move); switchTo(to) } else cues?.cue(Cue.Reject)
                    true
                }
                else -> false
            }
        }
        Column(Modifier.fillMaxSize()) {
            if (ui.shelves.isNotEmpty()) Strip(ui, emuAt, onTab = { i -> if (i != emuAt) { cues?.cue(Cue.Move); switchTo(i) } })
            Spacer(Modifier.height(10.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    !ui.signedIn -> EmptyLine("Sign in to see your games.")
                    shelf == null -> {}
                    spines.isNotEmpty() -> Shelf(
                        ui.rail, shelf, spines, at, art, artNow, spineArt, prefetch,
                        onSpine = { i -> cues?.cue(Cue.Move); moveTo(i) },
                        onCard = open,
                    )
                    ui.error != null && !ui.loaded -> ErrorLine(ui.error, Modifier.padding(horizontal = 12.dp))
                    shelf.emptyLine != null -> EmptyLine(shelf.emptyLine)
                }
            }
            NoticeLine(if (ui.signedIn) shelf?.notice else null)
        }
    }
}

/**
 * The emulators, in module order, between the Up and Down glyphs. The current one is inverted like the cursor, with
 * its state after it in its tone; one not set up here is dimmed. The current shelf's game count sits at the right.
 */
@Composable
private fun Strip(ui: ShelfUi, at: Int, onTab: (Int) -> Unit) {
    val c = LocalEmu.current
    val current = ui.shelves[at]
    Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Glyph(Action.Up)
            ui.tabs.forEachIndexed { i, t ->
                val on = i == at
                Row(
                    Modifier
                        .height(32.dp)
                        .noKeyFocus()
                        .clickable(interactionSource = null, indication = null, role = Role.Tab) { onTab(i) }
                        .semantics { selected = on },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.height(26.dp).then(if (on) Modifier.background(c.focusBg, EmuRadius) else Modifier).padding(horizontal = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val ink = when {
                            on -> c.focusInk
                            t.dimmed -> c.ink3.copy(alpha = 0.6f)
                            else -> c.ink2
                        }
                        BasicText(t.name, style = EmuType.tab.copy(color = ink), maxLines = 1)
                    }
                    if (on && t.state != null) {
                        BasicText(
                            "· ${t.state}", Modifier.padding(start = 7.dp, end = 4.dp),
                            style = EmuType.tab.copy(
                                color = c.tone(t.tone, false),
                                fontWeight = if (t.tone == Tone.Wait || t.tone == Tone.Fault) FontWeight.Bold else FontWeight.SemiBold,
                            ),
                            maxLines = 1,
                        )
                    }
                }
            }
            Glyph(Action.Down)
        }
        if (ui.signedIn && ui.loaded && current.count > 0) {
            BasicText(
                if (current.count == 1) "1 game" else "${current.count} games", Modifier.padding(start = 16.dp),
                style = EmuType.time.copy(color = c.ink3), maxLines = 1,
            )
        }
    }
}

/**
 * The shelf and its rail. The spines stand on the edge; the card for the cursor game stands as tall as the shelf.
 * Under the edge, one row per [rail] device, with each game's mark under its spine (centred in the card's width under
 * the card) and the device's label in the gutter on the left. The rail rows scroll with the spines.
 */
@Composable
private fun Shelf(
    rail: List<RailRow>,
    shelf: EmuShelf,
    spines: List<Spine>,
    at: Int,
    art: @Composable (String, String) -> ImageBitmap?,
    artNow: (String, String) -> ImageBitmap?,
    spineArt: @Composable (Spine) -> SpineImages,
    prefetch: (List<Spine>, Int, Int) -> Unit,
    onSpine: (Int) -> Unit,
    onCard: () -> Unit,
) {
    val c = LocalEmu.current
    val motion = LocalTabEntrance.current.slide
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val railH = RAIL_ROW * rail.size
        val shelfH = (maxHeight - EDGE - RAIL_GAP - railH).coerceAtLeast(120.dp)
        val viewportW = maxWidth - GUTTER
        // The gutter: each device's label level with its row of marks.
        Column(Modifier.width(GUTTER).padding(top = shelfH + EDGE + RAIL_GAP)) {
            for (r in rail) {
                Box(Modifier.height(RAIL_ROW).semantics { contentDescription = r.name }, contentAlignment = Alignment.CenterStart) {
                    BasicText(r.label, style = railLabel.copy(color = c.ink3), maxLines = 1)
                }
            }
        }
        Box(Modifier.padding(start = GUTTER).fillMaxSize()) {
            Box(Modifier.offset(y = shelfH).fillMaxWidth().height(EDGE).background(c.shelfEdge))
            val state = rememberLazyListState()
            val viewport = with(LocalDensity.current) { viewportW.roundToPx() }
            val spineH = shelfH - SPINE_TOP
            // Each spine is as wide as its console's case is, for this height.
            val widths = spines.map { spineH * Spines.ratio(it.system) }
            val density = LocalDensity.current
            val widthsPx = with(density) { widths.map { it.roundToPx() } }
            val px = with(density) { SPINE_GAP.roundToPx() to CARD_W.roundToPx() }
            LaunchedEffect(shelf.tab.id, at, spines.size, viewport, widthsPx) { state.keepInView(at, viewport, widthsPx, px.first, px.second) }
            // Art for what is in view and a dozen either side, a moment after the shelf settles.
            val first by remember { derivedStateOf { state.firstVisibleItemIndex } }
            val shown by remember { derivedStateOf { state.layoutInfo.visibleItemsInfo.size } }
            LaunchedEffect(shelf.tab.id, first, shown, spines) {
                kotlinx.coroutines.delay(dev.tandem.app.ui.art.SETTLE_MS)
                prefetch(spines, (first - PREFETCH_AROUND).coerceAtLeast(0), (first + shown + PREFETCH_AROUND).coerceAtMost(spines.size - 1))
            }
            LazyRow(Modifier.fillMaxSize().testTag("shelf"), state = state, horizontalArrangement = Arrangement.spacedBy(SPINE_GAP)) {
                itemsIndexed(spines, key = { _, s -> s.gameKey }) { i, s ->
                    val isCursor = i == at
                    val spineW = widths[i]
                    val w by animateDpAsState(
                        if (isCursor) CARD_W else spineW,
                        if (motion) tween(120, easing = LinearOutSlowInEasing) else snap(),
                        label = "width",
                    )
                    Column(Modifier.width(w)) {
                        Box(Modifier.height(shelfH).fillMaxWidth().clipToBounds(), contentAlignment = Alignment.BottomStart) {
                            // The new card grows out of its spine; the old one folds back into its spine.
                            if (isCursor || w > spineW + 1.dp) {
                                Card(s, shelfH, if (isCursor) art(s.emulatorId, s.gameKey) else artNow(s.emulatorId, s.gameKey), onCard)
                            } else {
                                SpineView(s, spineW, spineH, spineArt(s), motion) { onSpine(i) }
                            }
                        }
                        Spacer(Modifier.height(EDGE + RAIL_GAP))
                        Column(Modifier.fillMaxWidth().testTag("rail:${s.gameKey}")) {
                            for (m in s.marks) {
                                Box(Modifier.fillMaxWidth().height(RAIL_ROW), contentAlignment = Alignment.Center) {
                                    PresenceMark(m.presence, m.description, compact = true)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Scrolls the least that shows the card at [at] whole with a spine either side where there is room. It jumps; it never
 * animates. Positions are worked out for the settled layout: spine i is [spines][i] px wide, [gap] apart, the card [card].
 */
private suspend fun LazyListState.keepInView(at: Int, viewport: Int, spines: List<Int>, gap: Int, card: Int) {
    val n = spines.size
    if (n == 0 || viewport <= 0) return
    fun width(i: Int): Int = if (i == at) card else spines[i]
    val lefts = IntArray(n).also { l -> for (i in 1 until n) l[i] = l[i - 1] + width(i - 1) + gap }
    fun left(i: Int): Int = lefts[i]
    val total = left(n - 1) + width(n - 1)
    val first = firstVisibleItemIndex.coerceIn(0, n - 1)
    var x = left(first) + firstVisibleItemScrollOffset
    val before = (at - 1).coerceAtLeast(0)
    val after = (at + 1).coerceAtMost(n - 1)
    if (left(after) + width(after) - x > viewport) x = left(after) + width(after) - viewport
    if (left(before) < x) x = left(before)
    // Not enough room for both neighbours: the card itself, whole, comes first.
    if (left(at) < x) x = left(at)
    if (left(at) + card - x > viewport) x = left(at) + card - viewport
    x = x.coerceIn(0, (total - viewport).coerceAtLeast(0))
    var i = 0
    while (i < n - 1 && left(i) + width(i) + gap <= x) i++
    scrollToItem(i, x - left(i))
}

/**
 * The card title's size: two lines from 26 sp down to 18, and a name that fits two lines at none of those takes a third
 * line from 20 sp down to 17. Only past that is it cut with an ellipsis.
 */
internal object CardTitleSize : TextAutoSize {
    val steps: List<Pair<Int, Int>> = (26 downTo 18).map { it to 2 } + (20 downTo 17).map { it to 3 }

    override fun TextAutoSizeLayoutScope.getFontSize(constraints: Constraints, text: AnnotatedString): TextUnit {
        for ((sp, lines) in steps) {
            val l = performLayout(constraints, text, sp.sp)
            if (l.lineCount <= lines && !l.hasVisualOverflow) return sp.sp
        }
        return 17.sp
    }

    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = 0x7a11e
}

/** The images a spine draws from: its real case spine, and its front cover. Either may be missing. */
data class SpineImages(val spine: ImageBitmap? = null, val cover: ImageBitmap? = null)

/**
 * A game's spine, [width] × [height], drawn by [spineKind]: the real case spine as scanned; Switch's plain red; built
 * from the middle of the cover under a flat dark layer; or the system's colour. Built, plain and fallback spines carry
 * the console band at the top and the title reading top to bottom.
 */
@Composable
private fun SpineView(s: Spine, width: Dp, height: Dp, images: SpineImages, motion: Boolean, onClick: () -> Unit) {
    val kind = spineKind(s.system, s.hasSpine, images.spine != null, images.cover != null)
    Box(
        Modifier
            .testTag("spine:${s.gameKey}")
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
            .noKeyFocus()
            .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onClick),
    ) {
        Crossfade(kind, animationSpec = if (motion) tween(120) else snap(), label = "spine") { k ->
            when (k) {
                SpineKind.Real -> Image(
                    images.spine!!, contentDescription = null, contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize().testTag("spine-real:${s.gameKey}"),
                )
                SpineKind.Plain -> SpineFace(s, height, Spines.SWITCH_RED, Color.White, band = Spines.band("SWITCH"))
                SpineKind.Built -> Box(Modifier.fillMaxSize()) {
                    // The middle of the front cover: scaled to the spine's height, centred, clipped.
                    Image(
                        images.cover!!, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize().testTag("spine-cover:${s.gameKey}"),
                    )
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f)))
                    SpineFace(s, height, Color.Transparent, Color.White, band = Spines.band(s.system))
                }
                SpineKind.Fallback -> SpineFace(s, height, Spines.colour(s.system), Spines.ink(s.system), band = Spines.band(s.system))
            }
        }
    }
}

/** A spine's band (when the system has one) and its title, reading top to bottom, over [fill]. */
@Composable
private fun SpineFace(s: Spine, height: Dp, fill: Color, ink: Color, band: dev.tandem.app.ui.theme.Band?) {
    val d = LocalDensity.current
    Box(Modifier.fillMaxSize().background(fill)) {
        if (band != null) {
            Box(
                Modifier.fillMaxWidth().height(height * band.heightFraction).background(band.colour).testTag("spine-band:${s.gameKey}"),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    band.name,
                    Modifier.verticalTopToBottom(maxLength = height * band.heightFraction - 6.dp).testTag("spine-band-name:${s.gameKey}"),
                    style = spineCode.copy(color = band.ink, fontSize = with(d) { (height * if (band.heightFraction < 0.25f) 0.018f else 0.022f).toSp() }),
                    maxLines = 1, overflow = TextOverflow.Clip,
                )
            }
        }
        val top = height * when {
            band == null -> 0.06f
            band.heightFraction < 0.25f -> 0.23f
            else -> 0.30f
        }
        Box(Modifier.align(Alignment.TopCenter).padding(top = top).testTag("spine-title-ttb:${s.gameKey}")) {
            BasicText(
                s.title,
                Modifier.verticalTopToBottom(maxLength = height * 0.97f - top).testTag("spine-title:${s.gameKey}"),
                style = (if (s.unnamed) spineKey else spineTitle).copy(color = ink, fontSize = with(d) { (height * 0.05f).toSp() }),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Lays the content out on its side, reading top to bottom (turned 90° clockwise), at most [maxLength] long. */
private fun Modifier.verticalTopToBottom(maxLength: Dp): Modifier = this
    .layout { m, constraints ->
        val p = m.measure(Constraints(maxWidth = maxLength.roundToPx().coerceAtLeast(0), maxHeight = constraints.maxWidth))
        layout(p.height, p.width) { p.place((p.height - p.width) / 2, (p.width - p.height) / 2) }
    }
    .rotate(90f)

/**
 * The pulled-out card: the cover (or, without art, the system's code on its colour), the kicker, the title, this
 * device's state in a sentence, then a line per device. The cover shrinks to leave the device lines room.
 */
@Composable
private fun Card(s: Spine, height: Dp, cover: ImageBitmap?, onClick: () -> Unit) {
    val c = LocalEmu.current
    val card = s.card
    val lines = card.lines.size
    val linesH = 10.dp + 1.dp + 7.dp + 16.dp * lines + 5.dp * (lines - 1).coerceAtLeast(0)
    val coverH = (height - 24.dp - linesH).coerceIn(56.dp, COVER_H)
    val coverW = COVER_W * (coverH / COVER_H)
    Column(
        Modifier
            .wrapContentWidth(Alignment.Start, unbounded = true)
            .width(CARD_W)
            .fillMaxHeight()
            .testTag("card")
            .background(c.raised, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
            .border(1.dp, c.rule2, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
            .noKeyFocus()
            .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onClick)
            .padding(12.dp),
    ) {
        Row(Modifier.height(coverH)) {
            Cover(s, cover, coverW, coverH)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                BasicText(
                    card.kicker,
                    style = EmuType.mark.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.12.em, color = c.ink2),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                BasicText(
                    card.title,
                    Modifier.padding(top = 4.dp, bottom = 8.dp).testTag("card-title"),
                    style = if (card.unnamed) EmuType.mono.copy(fontSize = 17.sp, lineHeight = 20.sp, color = c.ink)
                    else EmuType.section.copy(fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 1.em, color = c.ink),
                    maxLines = if (card.unnamed) 2 else 3, overflow = TextOverflow.Ellipsis,
                    autoSize = if (card.unnamed) null else CardTitleSize,
                )
                val status = card.tone == Tone.Wait || card.tone == Tone.Fault
                BasicText(
                    card.sentence,
                    Modifier.weight(1f, fill = false),
                    style = EmuType.meta.copy(
                        fontWeight = if (status) FontWeight.SemiBold else FontWeight.Medium, fontSize = 12.5.sp, lineHeight = 17.sp,
                        color = c.tone(card.tone, false),
                    ),
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.rule))
        Spacer(Modifier.height(7.dp))
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            for (l in card.lines) DeviceLine(l.name, l.presence, l.words)
        }
    }
}

@Composable
private fun DeviceLine(name: String, p: Presence, words: String) {
    val c = LocalEmu.current
    val tone = when (p) {
        Presence.Waiting -> Tone.Wait
        Presence.Conflict -> Tone.Fault
        else -> Tone.Ink3
    }
    Row(Modifier.fillMaxWidth().height(16.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        PresenceMark(p, null, compact = true)
        Spacer(Modifier.width(5.dp))
        BasicText(name, Modifier.weight(1f).padding(end = 8.dp), style = EmuType.meta.copy(fontWeight = FontWeight.Medium, fontSize = 12.5.sp, color = c.ink),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicText(words, style = EmuType.time.copy(fontSize = 11.5.sp, color = c.tone(tone, false)), maxLines = 1)
    }
}

/** The cover, or without art the system's code large on the system's colour. Never a stand-in picture. */
@Composable
private fun Cover(s: Spine, cover: ImageBitmap?, w: Dp, h: Dp) {
    val shape = RoundedCornerShape(2.dp)
    if (cover != null) {
        // The whole cover, in its own shape (a square GBA box, a tall DVD case), fitted into the slot and set at its top.
        // The slot keeps its size so the title beside it doesn't move from game to game.
        val (iw, ih) = coverFit(cover.width, cover.height, w, h)
        Box(Modifier.size(w, h), contentAlignment = Alignment.TopCenter) {
            Image(cover, contentDescription = "Cover of ${s.title}", Modifier.size(iw, ih).clip(shape).testTag("cover"), contentScale = ContentScale.FillBounds)
        }
        return
    }
    Box(
        Modifier.size(w, h).background(Spines.colour(s.system), shape).testTag("no-art").clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            s.system ?: "—",
            style = EmuType.section.copy(fontWeight = FontWeight.Bold, fontSize = (if ((s.system?.length ?: 1) > 4) 22 else 32).sp,
                letterSpacing = 0.04.em, color = Spines.ink(s.system).copy(alpha = 0.85f)),
            maxLines = 1,
        )
    }
}

/** The size an image of [pxW]×[pxH] takes fitted whole inside [w]×[h]. */
internal fun coverFit(pxW: Int, pxH: Int, w: Dp, h: Dp): Pair<Dp, Dp> {
    if (pxW <= 0 || pxH <= 0) return w to h
    val aspect = pxW.toFloat() / pxH
    return if (aspect >= w / h) w to w / aspect else h * aspect to h
}

/**
 * The line over the hint bar: what waits for this emulator to close (the close sentence in amber, then the rest), or
 * what just landed, in ink; "+N more" in ink3 when there are several. Empty when nothing is pending.
 */
@Composable
private fun NoticeLine(n: Notice?) {
    val c = LocalEmu.current
    Row(Modifier.fillMaxWidth().heightIn(min = 30.dp).padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (n == null) return@Row
        BasicText(
            buildAnnotatedString {
                withStyle(SpanStyle(color = c.tone(n.tone, false), fontWeight = if (n.tone == Tone.Wait) FontWeight.SemiBold else FontWeight.Medium)) { append(n.text) }
                if (n.rest.isNotEmpty()) { append(" "); append(n.rest) }
            },
            Modifier.weight(1f, fill = false),
            style = EmuType.meta.copy(color = c.ink2), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (n.more > 0) BasicText("+${n.more} more", Modifier.padding(start = 12.dp), style = EmuType.meta.copy(color = c.ink3), maxLines = 1)
    }
}
