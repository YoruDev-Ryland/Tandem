package dev.tandem.app.ui.setup.steps

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.tandem.app.ui.input.Action
import dev.tandem.app.ui.kit.ErrorLine
import dev.tandem.app.ui.kit.Fact
import dev.tandem.app.ui.kit.Facts
import dev.tandem.app.ui.kit.Hint
import dev.tandem.app.ui.setup.OptionText
import dev.tandem.app.ui.setup.Options
import dev.tandem.app.ui.setup.SetupField
import dev.tandem.app.ui.setup.SetupFrame
import dev.tandem.app.ui.setup.SetupStep
import dev.tandem.app.ui.setup.SetupViewModel
import dev.tandem.app.ui.setup.SignInMethod
import dev.tandem.app.ui.setup.SignInPart
import dev.tandem.app.ui.setup.StepSub
import dev.tandem.app.ui.setup.StepTitle
import dev.tandem.app.ui.setup.describeSetupError
import dev.tandem.app.ui.state.TopStatus

/**
 * Step 1, server and sign-in: the server's address (checked with the server, which names itself), then how to sign
 * in (a pairing code first; registration only where the server takes new accounts), then the code or the email and
 * password. A device that is already signed in shows where, and moves on.
 */
@Composable
internal fun ServerStep(vm: SetupViewModel, status: TopStatus, diagnostics: (() -> Unit)?) {
    val signedIn by vm.signedIn.collectAsState()
    val part by vm.part.collectAsState()
    val exitable by vm.exitable.collectAsState()
    val error by vm.error.collectAsState()
    val busy by vm.busy.collectAsState()
    // On the first screen B leaves only when the flow was opened from somewhere.
    // Signed in, B leaves for the tabs.
    val back: (() -> Unit)? = if (signedIn || part != SignInPart.Server || exitable) vm::back else null
    // Y: the diagnostics screen, so a connection that won't work can be looked into before signing in.
    val diag = diagnostics?.let { Hint(Action.Y, "Diagnostics", it) }
    val note = if (busy) "Talking to the server" else null

    when {
        signedIn -> {
            val url by vm.url.collectAsState()
            SetupFrame(status, SetupStep.Server, listOfNotNull(Hint(Action.A, "Continue", vm::continueSignedIn), diag), back) {
                StepTitle("Signed in")
                StepSub("This device is signed in. To use another account or server, sign out in Settings first.")
                Facts(listOf(Fact("Server", url, mono = true), Fact("This device", status.deviceName ?: "Signed in")))
            }
        }
        part == SignInPart.Server -> {
            val url by vm.url.collectAsState()
            val focus = remember { FocusRequester() }
            SetupFrame(status, SetupStep.Server, listOfNotNull(Hint(Action.A, "Connect", vm::connect), diag), back, note) {
                StepTitle("Which server?")
                StepSub("The address of your Tandem server, as you open it in a browser.")
                SetupField(
                    "Server address", url, vm::setUrl, "setup-url",
                    KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go), vm::connect,
                    mono = true, focus = focus,
                )
                error?.let { ErrorLine(it, text = ::describeSetupError) }
            }
            LaunchedEffect(Unit) { focus.requestFocus() }
        }
        part == SignInPart.Method -> {
            val name by vm.serverName.collectAsState()
            val methods by vm.methods.collectAsState()
            val at by vm.methodAt.collectAsState()
            val m = methods.getOrNull(at.coerceIn(0, methods.lastIndex))
            SetupFrame(status, SetupStep.Server, listOfNotNull(m?.let { Hint(Action.A, "Choose") { vm.chooseMethod(it) } }, diag), back) {
                StepTitle("Sign in to ${name ?: "the server"}")
                StepSub("This device gets its own key. Your password is never kept on it.")
                Options(methods, at, vm::pointMethod, vm::chooseMethod, { OptionText(it.label, it.detail) })
            }
        }
        else -> Entry(vm, status, back, note, diag)
    }
}

@Composable
private fun Entry(vm: SetupViewModel, status: TopStatus, back: (() -> Unit)?, note: String?, diag: Hint?) {
    val method by vm.method.collectAsState()
    val error by vm.error.collectAsState()
    val name by vm.deviceName.collectAsState()
    val focus = remember { FocusRequester() }
    val confirm = if (method == SignInMethod.Register) "Create account" else "Sign in"
    SetupFrame(status, SetupStep.Server, listOfNotNull(Hint(Action.A, confirm, vm::signIn), diag), back, note) {
        StepTitle(method.label)
        when (method) {
            SignInMethod.Code -> {
                val code by vm.code.collectAsState()
                StepSub("${SignInMethod.Code.detail} It has 8 characters.")
                SetupField(
                    "Code", code, vm::setCode, "setup-code",
                    KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                    vm::signIn, mono = true, focus = focus,
                )
            }
            else -> {
                val email by vm.email.collectAsState()
                val password by vm.password.collectAsState()
                StepSub(
                    if (method == SignInMethod.Register) "Your email, and a password of at least 8 characters."
                    else SignInMethod.Password.detail,
                )
                SetupField(
                    "Email", email, vm::setEmail, "setup-email",
                    KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next), null, focus = focus,
                )
                SetupField(
                    "Password", password, vm::setPassword, "setup-password",
                    KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done), vm::signIn, secret = true,
                )
            }
        }
        SetupField(
            "This device's name", name, vm::setDeviceName, "setup-name",
            KeyboardOptions(imeAction = ImeAction.Done), vm::signIn,
        )
        error?.let { ErrorLine(it, Modifier.padding(top = 2.dp), text = ::describeSetupError) }
    }
    LaunchedEffect(method) { focus.requestFocus() }
}
