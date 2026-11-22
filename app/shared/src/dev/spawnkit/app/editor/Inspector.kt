package dev.spawnkit.app.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.spawnkit.app.assistant.AskAiButton
import dev.spawnkit.editing.EditOperation
import dev.spawnkit.editing.ObjectDoc
import dev.spawnkit.editing.OperationResult
import dev.spawnkit.render.argb
import dev.spawnkit.runtime.ComponentDescriptor
import dev.spawnkit.runtime.PropertyDescriptor
import dev.spawnkit.runtime.ValueKind
import dev.spawnkit.runtime.Visibility

/**
 * Colors offered by color controls, in the inspector and in generated rule-block controls: large touch targets
 * rather than a fiddly picker.
 */
internal val Swatches =
    listOf(
        0xFFF44336,
        0xFFFF9800,
        0xFFFFC107,
        0xFF4CAF50,
        0xFF00BCD4,
        0xFF2196F3,
        0xFF3F51B5,
        0xFF9C27B0,
        0xFF8D6E63,
        0xFF607D8B,
        0xFFFFFFFF,
        0xFF212121,
    )

/**
 * Property inspector generated from component descriptors: every installed component, including extension
 * components, gets usable controls without custom inspector code.
 */
@Composable
fun Inspector(
    state: EditorState,
    modifier: Modifier = Modifier,
    onAskAi: ((String) -> Unit)? = null,
) {
    val obj = state.selection?.let(state.scene::find)
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(12.dp).testTag("inspector"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (obj == null) {
            Text(
                "Select an object to change it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        NameField(state, obj)
        if (onAskAi != null) AskAiButton { onAskAi("Help me change ${obj.name}.") }
        for (doc in obj.components) {
            val descriptor =
                state.engine.runtime.catalog
                    .firstOrNull { it.component.id == doc.componentId }
            if (descriptor == null) {
                Text(
                    "${doc.componentId} is not installed. Its data is kept; install its extension to play.",
                    color = MaterialTheme.colorScheme.error,
                )
                continue
            }
            if (descriptor.visibility == Visibility.Advanced && !state.showAdvanced) continue
            ComponentSection(state, obj, descriptor)
        }
        AddComponentRow(state, obj)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(state.showAdvanced, { state.showAdvanced = it }, Modifier.testTag("advanced"))
            Spacer(Modifier.width(8.dp))
            Text("Show advanced options", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun NameField(
    state: EditorState,
    obj: ObjectDoc,
) {
    CommitTextField(
        label = "Name",
        value = obj.name,
        key = obj.id + "/name",
        tag = "field:name",
        numeric = false,
    ) { text -> state.apply(EditOperation.RenameObject(state.sceneId, obj.id, text)).rejection() }
}

@Composable
private fun ComponentSection(
    state: EditorState,
    obj: ObjectDoc,
    descriptor: ComponentDescriptor<*>,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                MaterialTheme.shapes.medium,
            ).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(descriptor.component.authoring.name, style = MaterialTheme.typography.titleSmall)
                if (descriptor.component.authoring.description
                        .isNotEmpty()
                ) {
                    Text(
                        descriptor.component.authoring.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton({
                state.apply(
                    EditOperation.RemoveComponent(
                        state.sceneId,
                        obj.id,
                        descriptor.component.id,
                        "Remove ${descriptor.component.authoring.name}",
                    ),
                )
            }, Modifier.testTag("remove-component:${descriptor.component.id}")) {
                Text("Remove")
            }
        }
        val doc = obj.component(descriptor.component.id) ?: return@Column
        for (property in descriptor.properties) {
            if (property.visibility == Visibility.Advanced && !state.showAdvanced) continue
            val value = doc.values[property.id] ?: property.default
            PropertyControl(state, obj, descriptor.component.id, property, value)
        }
    }
}

@Composable
private fun PropertyControl(
    state: EditorState,
    obj: ObjectDoc,
    componentId: String,
    property: PropertyDescriptor<*, *>,
    value: Any,
) {
    val name = property.authoring.name
    val key = "${obj.id}/$componentId/${property.id}"

    fun set(newValue: Any): String? =
        state
            .apply(
                EditOperation.SetProperty(state.sceneId, obj.id, componentId, property.id, newValue, "Change $name of ${obj.name}"),
            ).rejection()
    when (val kind = property.kind) {
        is ValueKind.WholeNumber -> {
            NumberRow(name, (value as Long).toString(), key, kind.unit, onStep = { delta ->
                set((value + delta * kind.step).coerceIn(kind.min, kind.max))
            }) { text ->
                text.trim().toLongOrNull()?.let(::set) ?: "Enter a whole number, for example 3."
            }
        }

        is ValueKind.Decimal -> {
            NumberRow(name, formatDecimal(value as Double), key, kind.unit, onStep = { delta ->
                set((value + delta * 0.5).coerceIn(kind.min, kind.max))
            }) { text ->
                text
                    .trim()
                    .replace(',', '.')
                    .toDoubleOrNull()
                    ?.let(::set) ?: "Enter a number, for example 2.5."
            }
        }

        ValueKind.YesNo -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f))
                Switch(value as Boolean, { set(it) }, Modifier.testTag("field:$key"))
            }
        }

        is ValueKind.Text -> {
            CommitTextField(name, value as String, key, "field:$key", numeric = false) { set(it) }
        }

        is ValueKind.Choice -> {
            Column {
                Text(name, style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (option in kind.options) {
                        FilterChip(
                            value == option.id,
                            { set(option.id) },
                            { Text(option.label) },
                            Modifier.testTag("choice:$key:${option.id}"),
                        )
                    }
                }
            }
        }

        ValueKind.Color -> {
            Column {
                Text(name, style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (swatch in Swatches) {
                        val chosen = value == swatch
                        Box(
                            Modifier
                                .size(32.dp)
                                .background(argb(swatch), CircleShape)
                                .border(
                                    if (chosen) 3.dp else 1.dp,
                                    if (chosen) MaterialTheme.colorScheme.primary else Color(0x44FFFFFF),
                                    CircleShape,
                                ).clickable { set(swatch) }
                                .testTag("swatch:$key:${swatch.toString(16)}"),
                        )
                    }
                }
            }
        }

        is ValueKind.ObjectReference -> {
            Text("$name: choosing objects arrives in a later version", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** A number field with large −/+ step buttons for touch. Typed values apply on Done or when focus leaves. */
@Composable
private fun NumberRow(
    name: String,
    value: String,
    key: String,
    unit: String,
    onStep: (Int) -> Unit,
    onCommit: (String) -> String?,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CommitTextField(
            if (unit.isEmpty()) name else "$name ($unit)",
            value,
            key,
            "field:$key",
            numeric = true,
            modifier = Modifier.weight(1f),
            onCommit = onCommit,
        )
        OutlinedButton({ onStep(-1) }, Modifier.testTag("dec:$key")) { Text("−") }
        OutlinedButton({ onStep(1) }, Modifier.testTag("inc:$key")) { Text("+") }
    }
}

/**
 * A text field that edits a local draft and commits it on Done or focus loss, so typing does not create one undo
 * step per keystroke. [onCommit] returns an explanation when the draft is rejected; the draft then stays visible with
 * that explanation shown under the field until the creator changes it.
 */
@Composable
private fun CommitTextField(
    label: String,
    value: String,
    key: String,
    tag: String,
    numeric: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onCommit: (String) -> String?,
) {
    var draft by remember(key, value) { mutableStateOf(value) }
    var error by remember(key, value) { mutableStateOf<String?>(null) }
    var focused by remember(key) { mutableStateOf(false) }
    val commit = { if (draft != value) error = onCommit(draft) }
    OutlinedTextField(
        value = draft,
        onValueChange = {
            draft = it
            error = null
        },
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it, Modifier.testTag("$tag:error")) } },
        keyboardOptions =
            KeyboardOptions(
                keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text,
                imeAction = ImeAction.Done,
            ),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier =
            modifier.testTag(tag).onFocusChanged {
                if (focused && !it.isFocused) commit()
                focused = it.isFocused
            },
    )
}

@Composable
private fun AddComponentRow(
    state: EditorState,
    obj: ObjectDoc,
) {
    val missing =
        state.engine.runtime.catalog.filter { d ->
            obj.component(d.component.id) == null &&
                (d.visibility == Visibility.Basic || state.showAdvanced)
        }
    if (missing.isEmpty()) return
    Column {
        Text("Add a component", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (descriptor in missing) {
                OutlinedButton(
                    {
                        state.apply(
                            EditOperation.AddComponent(
                                state.sceneId,
                                obj.id,
                                descriptor.component.id,
                                "Add ${descriptor.component.authoring.name}",
                            ),
                        )
                    },
                    Modifier.padding(vertical = 2.dp).testTag("add-component:${descriptor.component.id}"),
                ) { Text("+ ${descriptor.component.authoring.name}") }
            }
        }
    }
}

private fun formatDecimal(value: Double): String =
    if (value == kotlin.math.floor(value) &&
        kotlin.math.abs(value) < 1e9
    ) {
        value.toLong().toString()
    } else {
        value.toString()
    }

/** The creator-facing explanation of a rejected operation, or null when it was applied. */
private fun OperationResult.rejection(): String? = (this as? OperationResult.Rejected)?.message
