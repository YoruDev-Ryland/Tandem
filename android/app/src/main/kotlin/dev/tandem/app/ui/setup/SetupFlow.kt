package dev.tandem.app.ui.setup

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tandem.app.api.ApiException
import dev.tandem.app.fs.ShizukuState
import dev.tandem.app.ui.feedback.Cue
import dev.tandem.app.ui.feedback.LocalCueSink
import dev.tandem.app.ui.input.Action
import dev.tandem.app.ui.input.OnAction
import dev.tandem.app.ui.input.textEntry
import dev.tandem.app.ui.kit.ConsoleScaffold
import dev.tandem.app.ui.kit.EmuRadius
import dev.tandem.app.ui.kit.Hint
import dev.tandem.app.ui.kit.LocalReadout
import dev.tandem.app.ui.kit.SystemReadout
import dev.tandem.app.ui.kit.Tone
import dev.tandem.app.ui.kit.describeError
import dev.tandem.app.ui.kit.noKeyFocus
import dev.tandem.app.ui.kit.serverCopy
import dev.tandem.app.ui.kit.tone
import dev.tandem.app.ui.setup.steps.BackgroundStep
import dev.tandem.app.ui.setup.steps.EmulatorsStep
import dev.tandem.app.ui.setup.steps.FirstSyncStep
import dev.tandem.app.ui.setup.steps.ServerStep
import dev.tandem.app.ui.setup.steps.ShizukuStep
import dev.tandem.app.ui.setup.steps.SyncChoiceStep
import dev.tandem.app.ui.state.TopStatus
import dev.tandem.app.ui.theme.EmuType
import dev.tandem.app.ui.theme.LocalEmu

/**
 * The first-run setup flow, in place of the tabs: the steps down the left, one question per screen on the right,
 * A to choose and B to go back. [openShizuku] launches Shizuku when it is installed; [onDone] runs when the flow
 * ends on its last screen (the app then shows This device). [now] is the clock of the first-sync settle guard.
 */
@Composable
fun SetupFlow(
    vm: SetupViewModel,
    status: TopStatus,
    openShizuku: (() -> Unit)?,
    onDone: () -> Unit,
    /** Opens the diagnostics screen; null in release builds. */
    diagnostics: (() -> Unit)?,
    now: () -> Long = { SystemClock.uptimeMillis() },
    /** Opens a web page in the browser (Shizuku's download page). */
    openUrl: (String) -> Unit = {},
) {
    val step by vm.step.collectAsState()
    val signedIn by vm.signedIn.collectAsState()
    // Signed in, every step after the first can be left for the tabs; Settings › Set up again comes back.
    val later: (() -> Unit)? = if (signedIn) ({ vm.finishLater(); onDone() }) else null
    when (step) {
        SetupStep.Server -> ServerStep(vm, status, diagnostics)
        SetupStep.Shizuku -> ShizukuStep(vm, status, openShizuku, later, openUrl)
        SetupStep.Background -> BackgroundStep(vm, status, later)
        SetupStep.Emulators -> EmulatorsStep(vm, status, later)
        SetupStep.SyncChoice -> SyncChoiceStep(vm, status, later, now)
        SetupStep.FirstSync -> FirstSyncStep(vm, status, onDone, later, now)
    }
}

/**
 * One setup screen: the top bar, the step list (or, in portrait, a step line) and [content], with [hints] in the hint
 * bar and [note] at its left. [back] adds the B hint; without it B does nothing (it never leaves the app mid-setup).
 * [side] is shown under the step list, such as which emulator of how many.
 */
@Composable
internal fun SetupFrame(
    status: TopStatus,
    step: SetupStep,
    hints: List<Hint>,
    back: (() -> Unit)?,
    note: String? = null,
    side: String? = null,
    /** Signed in, Y leaves setup for the tabs ("Finish later"). */
    later: (() -> Unit)? = null,
    onAction: (Action) -> Boolean = { false },
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalEmu.current
    ConsoleScaffold(
        top = { SetupTop(status) },
        hints = hints + listOfNotNull(later?.let { Hint(Action.Y, "Finish later", it) }, back?.let { Hint(Action.B, "Back", it) }),
        status = note,
        onAction = { a -> onAction(a) || a == Action.B },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth < 600.dp) {
                Column(Modifier.fillMaxSize()) {
                    BasicText(
                        "Step ${step.ordinal + 1} of ${SetupStep.entries.size} · ${step.label}",
                        Modifier.padding(bottom = 8.dp), style = EmuType.meta.copy(color = c.ink3),
                    )
                    Column(Modifier.weight(1f).fillMaxWidth(), content = content)
                }
            } else {
                Row(Modifier.fillMaxSize()) {
                    StepList(step, side, Modifier.width(200.dp).fillMaxHeight())
                    Column(Modifier.weight(1f).fillMaxHeight().padding(start = 28.dp), content = content)
                }
            }
        }
    }
}

@Composable
private fun RowScope.SetupTop(status: TopStatus) {
    val c = LocalEmu.current
    val meta = EmuType.meta.copy(fontWeight = FontWeight.Medium)
    BasicText("Tandem", style = EmuType.section.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = c.ink))
    BasicText("Set up this device", style = EmuType.tab.copy(color = c.ink2), maxLines = 1)
    Row(
        Modifier.weight(1f),
        horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Signed out is what setup is for: the server status shows once there is a server to be connected to.
        if (status.signedIn) {
            status.deviceName?.let {
                BasicText(it, Modifier.weight(1f, fill = false), style = meta.copy(fontWeight = FontWeight.SemiBold, color = c.ink),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            serverCopy(status)?.let { (server, tone) -> BasicText(server, style = meta.copy(color = c.tone(tone, false)), maxLines = 1) }
        }
        // Setup is where Shizuku gets started, so off is not a fault here; on is not labelled.
        if (status.shizuku != ShizukuState.Ready) BasicText("Shizuku off", style = meta.copy(color = c.ink2), maxLines = 1)
        LocalReadout.current?.let { SystemReadout(it) }
    }
}

@Composable
private fun StepList(step: SetupStep, side: String?, modifier: Modifier) {
    val c = LocalEmu.current
    Column(
        modifier
            .drawBehind { drawLine(c.rule2, Offset(size.width, 0f), Offset(size.width, size.height), strokeWidth = 1.dp.toPx()) }
            .padding(end = 20.dp),
    ) {
        for (s in SetupStep.entries) {
            val ink = when {
                s == step -> c.ink
                s.ordinal < step.ordinal -> c.ink2
                else -> c.ink3
            }
            val weight = if (s == step) FontWeight.SemiBold else FontWeight.Medium
            Row(
                Modifier.height(34.dp).semantics(mergeDescendants = true) { selected = s == step },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BasicText("${s.ordinal + 1}", Modifier.width(20.dp), style = EmuType.section.copy(fontSize = 13.sp, color = ink, textAlign = TextAlign.End))
                BasicText(s.label, style = EmuType.body.copy(fontWeight = weight, color = ink), maxLines = 1)
            }
        }
        if (side != null) BasicText(side, Modifier.padding(top = 24.dp), style = EmuType.meta.copy(color = c.ink3))
    }
}

/** The screen's one question. */
@Composable
internal fun StepTitle(text: String, fault: Boolean = false) {
    val c = LocalEmu.current
    BasicText(text, Modifier.padding(top = 4.dp, bottom = 6.dp), style = EmuType.title.copy(color = if (fault) c.fault else c.ink))
}

/** What the question is about, under the title. */
@Composable
internal fun StepSub(text: String) {
    BasicText(text, Modifier.padding(bottom = 14.dp).widthIn(max = 560.dp), style = EmuType.body.copy(color = LocalEmu.current.ink2))
}

/** A note under a list, in ink3. */
@Composable
internal fun StepNote(text: String, modifier: Modifier = Modifier) {
    BasicText(text, modifier.padding(top = 14.dp).widthIn(max = 560.dp), style = EmuType.meta.copy(color = LocalEmu.current.ink3))
}

/**
 * One setting as a hairline row: what it does, and On or Off at the right. A tap (or the hint bar's button the
 * caller gives it) flips it.
 */
@Composable
internal fun ToggleLine(text: String, on: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalEmu.current
    Row(
        modifier
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .drawBehind {
                drawLine(c.rule, Offset(0f, 0.5f), Offset(size.width, 0.5f), strokeWidth = 1.dp.toPx())
                drawLine(c.rule, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), strokeWidth = 1.dp.toPx())
            }
            .noKeyFocus()
            .clickable(interactionSource = null, indication = null, onClick = onToggle)
            .semantics(mergeDescendants = true) { selected = on }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        BasicText(text, Modifier.weight(1f), style = EmuType.body.copy(color = c.ink))
        BasicText(if (on) "On" else "Off", style = EmuType.meta.copy(color = c.ink2))
    }
}

/** Waiting for the server or the service, as the web shows it. */
@Composable
internal fun Loading() {
    BasicText("— — —", Modifier.padding(horizontal = 12.dp, vertical = 12.dp), style = EmuType.body.copy(color = LocalEmu.current.ink3))
}

/** An answer as two lines: what it does and what follows from it. */
data class OptionText(val title: String, val detail: String, val marker: String? = null)

/**
 * A choice of answers, drawn as two-line rows with the cursor row inverted (the mockup's option rows). Up and down
 * move the cursor, A chooses it; tapping a row moves the cursor there and tapping the cursor row chooses it. Under a
 * row, [below] can show what went wrong with it.
 */
@Composable
internal fun <T> Options(
    items: List<T>,
    at: Int,
    onSelect: (Int) -> Unit,
    onChoose: (T) -> Unit,
    text: (T) -> OptionText,
    enabled: Boolean = true,
    below: @Composable (T) -> Unit = {},
) {
    val c = LocalEmu.current
    val cursor = at.coerceIn(0, maxOf(0, items.lastIndex))
    val cues = LocalCueSink.current
    OnAction(enabled = enabled && items.isNotEmpty()) { a ->
        when (a) {
            Action.Up, Action.Down -> {
                val to = (cursor + if (a == Action.Down) 1 else -1).coerceIn(0, items.lastIndex)
                cues?.cue(if (to == cursor) Cue.Reject else Cue.Move)
                onSelect(to)
                true
            }
            Action.A -> { onChoose(items[cursor]); true }
            else -> false
        }
    }
    Column(Modifier.widthIn(max = 640.dp)) {
        items.forEachIndexed { i, item ->
            val focused = i == cursor
            val t = text(item)
            val look = when {
                focused -> Modifier.background(c.focusBg, EmuRadius)
                i == cursor + 1 -> Modifier
                else -> Modifier.drawBehind { drawLine(c.rule, Offset(0f, 0.5f), Offset(size.width, 0.5f), strokeWidth = 1.dp.toPx()) }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .then(look)
                    .noKeyFocus()
                    .clickable(interactionSource = null, indication = null, enabled = enabled) {
                        if (focused) onChoose(item) else onSelect(i)
                    }
                    .semantics(mergeDescendants = true) { selected = focused }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    BasicText(t.title, style = EmuType.rowPrimary.copy(fontSize = 16.sp, color = if (focused) c.focusInk else c.ink))
                    BasicText(t.detail, Modifier.padding(top = 2.dp), style = EmuType.meta.copy(color = c.tone(Tone.Ink2, focused)))
                }
                if (t.marker != null) BasicText(t.marker, style = EmuType.meta.copy(color = c.tone(Tone.Ink2, focused)))
            }
            below(item)
        }
    }
}

/**
 * A labelled text field. While it has focus the scaffold leaves keys to it (the D-pad moves the caret); its IME
 * action ([onIme]) does what the hint bar's A does.
 */
@Composable
internal fun SetupField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    tag: String,
    keyboard: KeyboardOptions,
    /** The IME action; null keeps the default (Next moves to the next field). */
    onIme: (() -> Unit)?,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    secret: Boolean = false,
    focus: FocusRequester? = null,
) {
    val c = LocalEmu.current
    Column(modifier.padding(bottom = 12.dp)) {
        BasicText(label, Modifier.padding(bottom = 6.dp), style = EmuType.meta.copy(color = c.ink2))
        BasicTextField(
            value, onChange,
            Modifier
                .testTag(tag)
                .semantics { contentDescription = label }
                .textEntry()
                .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .height(40.dp)
                .border(1.5.dp, c.rule2, EmuRadius)
                .padding(horizontal = 12.dp),
            textStyle = (if (mono) EmuType.mono else EmuType.body).copy(color = c.ink),
            singleLine = true,
            keyboardOptions = keyboard.copy(autoCorrectEnabled = false),
            keyboardActions = if (onIme != null) KeyboardActions { onIme() } else KeyboardActions.Default,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            cursorBrush = SolidColor(c.ink),
            decorationBox = { inner -> Box(contentAlignment = Alignment.CenterStart) { inner() } },
        )
    }
}

// The web sign-in page's copy for what signing in can be refused with.
private val SIGN_IN_TEXT = mapOf(
    "invalid_credentials" to "That email or password is not right. Check them and try again.",
    "code_not_found" to "That code was not found. Check it and try again.",
    "code_used" to "That code was already used. Get a new one.",
    "code_expired" to "That code has expired. Get a new one.",
    "registration_closed" to "This server is not taking new accounts. Sign in, or use a pairing code.",
)

/** Step 1's words for a failure: a refusal before sending as it is, sign-in codes as the web says them. */
internal fun describeSetupError(e: Throwable): String = when (e) {
    is InputError -> e.message.orEmpty()
    is ApiException -> e.code?.let { SIGN_IN_TEXT[it] } ?: describeError(e)
    else -> describeError(e)
}
