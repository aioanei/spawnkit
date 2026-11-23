package dev.spawnkit.app.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private enum class AccountTab(
    val label: String,
    val tag: String,
) {
    SignIn("Sign in", "account-tab-sign-in"),
    Create("Create account", "account-tab-create"),
    Reset("Forgot password", "account-tab-reset"),
}

/**
 * Project → Account (C11b): choose a SpawnKit server, then sign in, create an account with an emailed code, or reset a
 * password. Signed in, it shows the account with Sign out and Delete account. Full screen on phones; a centred column
 * on wider screens.
 */
@Composable
fun AccountScreen(
    accounts: AccountState,
    storageWhere: String,
    close: () -> Unit,
) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("account-screen"), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(close, Modifier.heightIn(min = 48.dp).testTag("account-back")) { Text("Back to editor") }
                    Text("SpawnKit account", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
                    val account = accounts.account
                    if (account != null) {
                        SignedIn(accounts)
                    } else {
                        SignedOut(accounts)
                    }
                    if (accounts.busy) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text("Talking to the server…")
                        }
                    }
                    accounts.notice?.let {
                        Text(
                            it,
                            Modifier.testTag("account-notice").semantics { liveRegion = LiveRegionMode.Polite },
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    Text(
                        "Your session is kept in $storageWhere. It never goes into your games, exports or the assistant.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SignedIn(accounts: AccountState) {
    val account = accounts.account ?: return
    var deleting by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Signed in as ${account.displayName}", fontWeight = FontWeight.SemiBold)
            account.email?.let { Text(it) }
            Text("Server: ${accounts.server.orEmpty()}", style = MaterialTheme.typography.bodySmall)
            accounts.status?.let { Text(it.ai, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    accounts::signOut,
                    Modifier.heightIn(min = 48.dp).testTag("account-sign-out"),
                    enabled = !accounts.busy,
                ) { Text("Sign out") }
                OutlinedButton({ deleting = true }, Modifier.heightIn(min = 48.dp).testTag("account-delete"), enabled = !accounts.busy) {
                    Text("Delete account")
                }
            }
        }
    }
    // Deleting is permanent, so it always asks first.
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete your account?") },
            text = {
                Text(
                    "This deletes your account and signs you out everywhere. Games you published stay playable, but you can no longer change them.",
                )
            },
            confirmButton = {
                Button(
                    {
                        deleting = false
                        accounts.deleteAccount()
                    },
                    Modifier.testTag("account-delete-yes"),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton({ deleting = false }, Modifier.testTag("account-delete-no")) { Text("Keep my account") } },
        )
    }
}

@Composable
private fun SignedOut(accounts: AccountState) {
    var tab by remember { mutableStateOf(AccountTab.SignIn) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val idle = !accounts.busy
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            accounts.address,
            { accounts.address = it },
            Modifier.weight(1f).testTag("account-address"),
            label = { Text("Server address") },
            placeholder = { Text("https://arcade.example.org") },
            singleLine = true,
            enabled = idle,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedButton(accounts::checkServer, Modifier.heightIn(min = 48.dp).testTag("account-check"), enabled = idle) { Text("Check") }
    }
    accounts.status?.let { status ->
        Text(
            (if (status.emailSignIn) "Email sign-in is available. " else "This server cannot send email, so email sign-in is off. ") +
                status.ai,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        AccountTab.entries.forEach { entry ->
            TextButton({ tab = entry }, Modifier.heightIn(min = 48.dp).testTag(entry.tag)) {
                Text(entry.label, fontWeight = if (tab == entry) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
    val emailField = @Composable {
        OutlinedTextField(
            email,
            { email = it },
            Modifier.fillMaxWidth().testTag("account-email"),
            label = { Text("Email") },
            singleLine = true,
            enabled = idle,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )
    }
    when (tab) {
        AccountTab.SignIn -> {
            emailField()
            PasswordField(password, { password = it }, "Password", "account-password", idle)
            Button(
                { accounts.signIn(email, password) },
                Modifier.heightIn(min = 48.dp).testTag("account-sign-in"),
                enabled = idle,
            ) { Text("Sign in") }
        }

        AccountTab.Create -> {
            if (accounts.awaitingCode == null) {
                OutlinedTextField(
                    name,
                    { name = it },
                    Modifier.fillMaxWidth().testTag("account-name"),
                    label = { Text("Your name in the Arcade") },
                    singleLine = true,
                    enabled = idle,
                )
                emailField()
                PasswordField(password, { password = it }, "Password (at least 10 characters)", "account-password", idle)
                Button(
                    { accounts.register(email, password, name) },
                    Modifier.heightIn(min = 48.dp).testTag("account-create"),
                    enabled = idle,
                ) {
                    Text("Create account")
                }
            } else {
                CodeField(code, { code = it }, idle)
                Button(
                    { accounts.verify(code) },
                    Modifier.heightIn(min = 48.dp).testTag("account-verify"),
                    enabled = idle,
                ) { Text("Confirm") }
            }
        }

        AccountTab.Reset -> {
            if (accounts.awaitingReset == null) {
                emailField()
                Button({ accounts.requestReset(email) }, Modifier.heightIn(min = 48.dp).testTag("account-send-code"), enabled = idle) {
                    Text("Email me a code")
                }
            } else {
                CodeField(code, { code = it }, idle)
                PasswordField(password, { password = it }, "New password", "account-new-password", idle)
                Button({ accounts.reset(code, password) }, Modifier.heightIn(min = 48.dp).testTag("account-set-password"), enabled = idle) {
                    Text("Set password and sign in")
                }
            }
        }
    }
}

@Composable
private fun PasswordField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    tag: String,
    enabled: Boolean,
) {
    OutlinedTextField(
        value,
        onChange,
        Modifier.fillMaxWidth().testTag(tag),
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
}

@Composable
private fun CodeField(
    value: String,
    onChange: (String) -> Unit,
    enabled: Boolean,
) {
    OutlinedTextField(
        value,
        onChange,
        Modifier.fillMaxWidth().testTag("account-code"),
        label = { Text("6-digit code from the email") },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    )
}
