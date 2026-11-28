package dev.spawnkit.app.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.spawnkit.ai.AgentOutcome
import dev.spawnkit.ai.DemoModel
import dev.spawnkit.app.images.rememberImagePicker

/** Starting points shown in an empty conversation with SpawnKit AI (C13b). */
internal val Suggestions =
    listOf(
        "Make a complete game: a space shooter with a title screen, two levels, a shop and a game over screen.",
        "Add a second level that is harder than the first.",
        "Add a shop that sells an extra life as a test purchase.",
    )

/**
 * The assistant as a conversation (C7c, C13b): what the creator asked and what the assistant did, with a composer at the
 * bottom. Changes are applied right away, each with Revert; with Review first, they wait for Accept or Reject.
 */
@Composable
fun AssistantPanel(
    assistant: AssistantState,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(assistant.gameKey) { assistant.refresh() }
    var settings by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    // Keep the newest message, step, question or notice in view, as chats do.
    LaunchedEffect(assistant.chat.size, assistant.outcome, assistant.progress.size, assistant.notice, assistant.running) {
        withFrameNanos { } // let the new content be measured first
        scroll.animateScrollTo(scroll.maxValue)
    }
    Column(modifier.testTag("assistant")) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                assistant.modelLabel,
                Modifier.weight(1f).testTag("assistant-model"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 2,
            )
            TextButton({ settings = !settings }, Modifier.heightIn(min = 48.dp).testTag("assistant-settings")) {
                Text(if (settings) "Close settings" else "Settings")
            }
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (settings) Settings(assistant)
            if (assistant.chat.isEmpty() && !assistant.running && assistant.outcome == null) Welcome(assistant)
            assistant.chat.forEachIndexed { index, entry -> Entry(assistant, entry, index) }
            Progress(assistant)
            when (val outcome = assistant.outcome) {
                is AgentOutcome.Proposal -> Proposal(assistant, outcome)
                is AgentOutcome.Question -> Question(assistant, outcome)
                is AgentOutcome.Confirmation -> Confirmation(assistant, outcome)
                is AgentOutcome.Answer, is AgentOutcome.Failed, null -> Unit
            }
            assistant.notice?.let { Text(it, Modifier.testTag("assistant-notice"), color = MaterialTheme.colorScheme.tertiary) }
            Text(
                assistant.costText,
                Modifier.padding(bottom = 4.dp).testTag("assistant-cost"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
        Composer(assistant)
    }
}

/** Opens the assistant with a prompt about the selected object. */
@Composable
fun AskAiButton(onClick: () -> Unit) {
    OutlinedButton(onClick, Modifier.heightIn(min = 48.dp).testTag("ask-ai")) { Text("✨ Ask AI") }
}

/** An empty conversation: what the assistant can do, how to get SpawnKit AI, and prompts to start from. */
@Composable
private fun Welcome(assistant: AssistantState) {
    Text(
        "Describe what to build or change. The assistant uses the builder's own tools, applies its changes right away, " +
            "and you can revert them or edit everything by hand.",
        style = MaterialTheme.typography.bodyMedium,
    )
    if (assistant.needsSignIn) {
        Text(
            "Sign in with Project → Account to build with SpawnKit AI for free. Until then, the scripted demo answers its examples.",
            Modifier.testTag("assistant-sign-in-hint"),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    val examples = if (assistant.isDemo) DemoModel.PROMPTS else Suggestions
    Text("Try an example:", style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        examples.forEachIndexed { index, example ->
            OutlinedButton({ assistant.prompt = example }, Modifier.heightIn(min = 48.dp).testTag("assistant-example-$index")) {
                Text(example, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Entry(
    assistant: AssistantState,
    entry: ChatEntry,
    index: Int,
) {
    when (entry) {
        is ChatEntry.Request -> {
            Bubble(entry.text, mine = true, Modifier.testTag("chat-request-$index"))
        }

        is ChatEntry.Reply -> {
            Bubble(entry.text, mine = false, Modifier.testTag("chat-reply-$index"))
        }

        is ChatEntry.Applied -> {
            Applied(assistant, entry, index)
        }

        is ChatEntry.Note -> {
            Text(
                entry.text,
                Modifier.fillMaxWidth().testTag("chat-note-$index"),
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A message: the creator's on the right, the assistant's on the left. */
@Composable
private fun Bubble(
    text: String,
    mine: Boolean,
    modifier: Modifier,
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            Modifier.widthIn(max = 520.dp),
            color = if (mine) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(text, modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Changes applied as one undo step: the summary, the steps on request, and Revert while it is still the latest step. */
@Composable
private fun Applied(
    assistant: AssistantState,
    entry: ChatEntry.Applied,
    index: Int,
) {
    var open by remember(entry) { mutableStateOf(false) }
    val revertible = assistant.canRevert(entry)
    val reverted = assistant.isReverted(entry)
    Surface(
        Modifier.fillMaxWidth().testTag("chat-applied-$index"),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                when {
                    reverted -> "↶ Reverted"
                    entry.replay -> "✓ Applied to your game · from the replay (no model was called)"
                    else -> "✓ Applied to your game"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (reverted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary,
            )
            Text(entry.text, style = MaterialTheme.typography.bodyMedium)
            val count = entry.steps.size
            TextButton({ open = !open }, Modifier.heightIn(min = 48.dp).testTag("chat-steps-$index")) {
                Text(if (open) "Hide changes" else "Show $count ${if (count == 1) "change" else "changes"}")
            }
            if (open) entry.steps.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            if (!reverted) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        { assistant.revert(entry) },
                        Modifier.heightIn(min = 48.dp).testTag("chat-revert-$index"),
                        enabled = revertible,
                    ) { Text("Revert") }
                    if (!revertible) {
                        Text(
                            if (assistant.playing) "Stop the game to revert." else "Changed since. Use Undo (↶) to step back.",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** What the assistant is doing for the current request. */
@Composable
private fun Progress(assistant: AssistantState) {
    if (assistant.running) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("Working…", Modifier.weight(1f))
            OutlinedButton(assistant::cancel, Modifier.heightIn(min = 48.dp).testTag("assistant-cancel")) { Text("Cancel") }
        }
    }
    // A long build shows its latest steps; the full list is in the Applied card.
    val lines = assistant.progress
    if (lines.size > MAX_PROGRESS) {
        Text(
            "… ${lines.size - MAX_PROGRESS} earlier steps",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    lines.takeLast(MAX_PROGRESS).forEach { line ->
        Text("• $line", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The prompt, Send and Attach photo, always at the bottom like a chat. */
@Composable
private fun Composer(assistant: AssistantState) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                assistant.prompt,
                { assistant.prompt = it },
                Modifier.weight(1f).testTag("assistant-prompt"),
                placeholder = { Text("Describe what to build or change…") },
                maxLines = 4,
                enabled = !assistant.running,
            )
            Button(assistant::ask, Modifier.heightIn(min = 48.dp).testTag("assistant-ask"), enabled = assistant.canAsk()) { Text("Send") }
        }
        if (assistant.playing) Text("Stop the game to ask.", style = MaterialTheme.typography.bodySmall)
        AttachPhoto(assistant)
    }
}

@Composable
private fun Proposal(
    assistant: AssistantState,
    proposal: AgentOutcome.Proposal,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(proposal.summary, fontWeight = FontWeight.SemiBold)
            Text("Changes to review:", style = MaterialTheme.typography.bodySmall)
            proposal.steps.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            Text(
                "Nothing in your game has changed yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(assistant::accept, Modifier.heightIn(min = 48.dp).testTag("assistant-accept")) { Text("Accept") }
                OutlinedButton(assistant::reject, Modifier.heightIn(min = 48.dp).testTag("assistant-reject")) { Text("Reject") }
            }
        }
    }
}

/** The question itself is in the conversation; this offers its answers. */
@Composable
private fun Question(
    assistant: AssistantState,
    question: AgentOutcome.Question,
) {
    var reply by remember(question) { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        question.options.forEachIndexed { index, option ->
            OutlinedButton({ assistant.answer(option) }, Modifier.heightIn(min = 48.dp).testTag("assistant-option-$index")) { Text(option) }
        }
        OutlinedTextField(reply, { reply = it }, Modifier.fillMaxWidth().testTag("assistant-reply"), label = { Text("Or type an answer") })
        Button({ assistant.answer(reply) }, Modifier.heightIn(min = 48.dp).testTag("assistant-reply-send"), enabled = reply.isNotBlank()) {
            Text("Reply")
        }
    }
}

/** Attach a photo for Character Forge (C16b); the model is only told that one is attached. */
@Composable
private fun AttachPhoto(assistant: AssistantState) {
    val picker = rememberImagePicker() ?: return
    var problem by remember { mutableStateOf<String?>(null) }
    val photo = assistant.photo
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (photo == null) {
            TextButton(
                {
                    problem = null
                    picker.pick { result ->
                        result
                            .onSuccess { picked -> picked?.let(assistant::attach) }
                            .onFailure { problem = it.message ?: "This photo could not be read" }
                    }
                },
                Modifier.heightIn(min = 48.dp).testTag("assistant-attach").semantics { contentDescription = "Attach photo" },
                enabled = !assistant.running,
            ) { Text("📎 Attach photo") }
        } else {
            Text(
                "Photo attached: ${photo.name}",
                Modifier.weight(1f).testTag("assistant-photo"),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(
                assistant::removePhoto,
                Modifier.heightIn(min = 48.dp).testTag("assistant-photo-remove"),
                enabled = !assistant.running,
            ) {
                Text("Remove")
            }
        }
    }
    problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

/** The assistant asks before the photo leaves the device (ADR-0009, ADR-0013); consent is required to send it. */
@Composable
private fun Confirmation(
    assistant: AssistantState,
    confirmation: AgentOutcome.Confirmation,
) {
    var consent by remember(confirmation) { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp).testTag("assistant-confirm"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(confirmation.message, fontWeight = FontWeight.SemiBold)
            Text(
                "Your photo leaves this device and goes only to the image model. It is not saved on the server or in your " +
                    "game. Each style uses part of your SpawnKit AI allowance.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(consent, { consent = it }, Modifier.testTag("assistant-consent"))
                Text("This is me, or this person agreed")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    { assistant.confirm(consent) },
                    Modifier.heightIn(min = 48.dp).testTag("assistant-send-photo"),
                    enabled = consent,
                ) { Text("Send photo") }
                OutlinedButton(assistant::decline, Modifier.heightIn(min = 48.dp).testTag("assistant-dont-send")) { Text("Don't send") }
            }
        }
    }
}

/** How changes arrive (right away or after review), then the developer-only key. */
@Composable
private fun Settings(assistant: AssistantState) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Review changes first", fontWeight = FontWeight.SemiBold)
                Text(
                    if (assistant.reviewFirst) {
                        "Changes wait for you to accept or reject them."
                    } else {
                        "Changes are applied right away; each one can be reverted."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(assistant.reviewFirst, { assistant.reviewFirst = it }, Modifier.testTag("assistant-review-first"))
        }
    }
    KeySettings(assistant)
}

@Composable
private fun KeySettings(assistant: AssistantState) {
    var draft by remember { mutableStateOf("") }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Developer settings: your own Anthropic API key", fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    !assistant.hasKey -> "SpawnKit AI needs no key: sign in instead. A key of your own is for testing SpawnKit itself."
                    assistant.keyPersists -> "A key is saved in ${assistant.keyLocation}."
                    else -> "A key is saved and ${assistant.keyLocation}."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                draft,
                { draft = it },
                Modifier.fillMaxWidth().testTag("assistant-key"),
                label = { Text(if (assistant.hasKey) "Replace the key" else "Paste a key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    {
                        assistant.saveKey(draft)
                        draft = ""
                    },
                    Modifier.heightIn(min = 48.dp).testTag("assistant-key-save"),
                    enabled = draft.isNotBlank(),
                ) { Text("Save key") }
                if (assistant.hasKey) {
                    OutlinedButton(
                        assistant::removeKey,
                        Modifier.heightIn(min = 48.dp).testTag("assistant-key-remove"),
                    ) { Text("Remove key") }
                }
            }
            if (assistant.hasKey) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(assistant.useLive, { assistant.useLive = it }, Modifier.testTag("assistant-live"))
                    Spacer(Modifier.width(8.dp))
                    Text("Use the live model (uses tokens on your account)", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                "The key goes only to the model provider. It is never put in your game, exports or logs, and the assistant cannot read it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Progress lines shown while a long request runs. */
private const val MAX_PROGRESS = 8
