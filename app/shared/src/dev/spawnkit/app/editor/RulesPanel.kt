package dev.spawnkit.app.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.spawnkit.app.assistant.AskAiButton
import dev.spawnkit.editing.Parameter
import dev.spawnkit.editing.ParameterType
import dev.spawnkit.editing.Picked
import dev.spawnkit.editing.Suggestions
import dev.spawnkit.editing.Variant
import dev.spawnkit.gameplay.Action
import dev.spawnkit.gameplay.BlockLocation
import dev.spawnkit.gameplay.BlockPart
import dev.spawnkit.gameplay.BlockResult
import dev.spawnkit.gameplay.BlockSpec
import dev.spawnkit.gameplay.Condition
import dev.spawnkit.gameplay.Rule
import dev.spawnkit.gameplay.RuleBlockCatalog
import dev.spawnkit.gameplay.RuleBlocks
import dev.spawnkit.gameplay.RuleItem
import dev.spawnkit.gameplay.RuleOperations
import dev.spawnkit.render.argb
import dev.spawnkit.runtime.ValueKind

/** Which block the dialog edits. [isNew] adds a block (or a whole rule when [rule] is past the last rule). */
private data class Editing(
    val rule: Int,
    val part: BlockPart,
    val index: Int,
    val isNew: Boolean,
)

/**
 * Visual When / If / Do rules for the selected object. Every change is one undoable step built by [RuleOperations],
 * the same operations agents use (ADR-0009). Block controls are generated from [RuleBlockCatalog]. Rules made by other
 * tools that are not blocks are shown as advanced rules and kept unchanged.
 */
@Composable
fun RulesPanel(
    state: EditorState,
    modifier: Modifier = Modifier,
    onAskAi: ((String) -> Unit)? = null,
) {
    val obj = state.selection?.let(state.scene::find)
    var editing by remember(obj?.id) { mutableStateOf<Editing?>(null) }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(12.dp).testTag("rules"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (obj == null) {
            Text("Select an object to give it rules.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        if (onAskAi != null) AskAiButton { onAskAi("Help me with the rules of ${obj.name}.") }
        val items = state.ruleItems(obj.id)
        val problems = remember(state.revision, obj.id) { state.ruleProblems(obj.id) }
        if (items.isEmpty()) {
            Text(
                "Rules make ${obj.name} do things while playing: When something happens, do something.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        problems.filter { it.first == null }.forEach { (_, message) -> Problem(message) }
        val actions =
            RuleActions(
                count = items.size,
                move = { from, to -> state.apply(RuleOperations.moveRule(state.project, state.sceneId, obj.id, from, to)) },
                duplicate = { state.apply(RuleOperations.duplicateRule(state.project, state.sceneId, obj.id, it)) },
                delete = { state.apply(RuleOperations.removeRule(state.project, state.sceneId, obj.id, it)) },
            )
        items.forEachIndexed { index, item ->
            when (item) {
                is RuleItem.Blocks -> RuleCard(index, item.rule, problems, actions, onEdit = { editing = it })
                is RuleItem.Advanced -> AdvancedCard(index, item.handler.source.label, actions)
            }
        }
        Button({ editing = Editing(items.size, BlockPart.Condition, 0, isNew = true) }, Modifier.testTag("add-rule")) {
            Text("+ Add rule")
        }
    }
    val current = editing ?: return
    val objectId = obj?.id ?: return
    val items = state.ruleItems(objectId)
    val newRule = current.isNew && current.rule >= items.size
    val rule = (items.getOrNull(current.rule) as? RuleItem.Blocks)?.rule
    if (rule == null && !newRule) {
        // The rule went away (for example after undo) while its dialog was being opened.
        editing = null
        return
    }
    BlockDialog(
        state,
        current,
        rule,
        onClose = { editing = null },
        onRemove = {
            state.apply(
                RuleOperations.removeBlock(
                    state.project,
                    state.sceneId,
                    objectId,
                    BlockLocation(current.rule, current.part, current.index),
                ),
            )
            editing = null
        },
    ) { block ->
        val location = BlockLocation(current.rule, current.part, current.index)
        val result =
            when {
                newRule -> RuleOperations.addRule(state.project, state.sceneId, objectId, Rule(block as Condition))
                current.isNew && block is Condition -> RuleOperations.addBlock(state.project, state.sceneId, objectId, current.rule, block)
                current.isNew -> RuleOperations.addBlock(state.project, state.sceneId, objectId, current.rule, block as Action)
                block is Condition -> RuleOperations.changeBlock(state.project, state.sceneId, objectId, location, block)
                else -> RuleOperations.changeBlock(state.project, state.sceneId, objectId, location, block as Action)
            }
        state.apply(result)
        editing = null
    }
}

/** Rule-level actions shared by block and advanced rule cards. */
private class RuleActions(
    val count: Int,
    val move: (from: Int, to: Int) -> Unit,
    val duplicate: (Int) -> Unit,
    val delete: (Int) -> Unit,
)

@Composable
private fun RuleHeader(
    index: Int,
    title: String,
    actions: RuleActions,
    titleTag: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            Modifier.weight(1f).then(if (titleTag != null) Modifier.testTag(titleTag) else Modifier),
            style = MaterialTheme.typography.titleSmall,
        )
        Box {
            TextButton(
                { open = true },
                Modifier.testTag("rule:$index:more").semantics { contentDescription = "More for rule ${index + 1}" },
            ) { Text("⋯") }
            DropdownMenu(open, { open = false }) {
                DropdownMenuItem(
                    { Text("Move up") },
                    {
                        open = false
                        actions.move(index, index - 1)
                    },
                    Modifier.testTag("rule:$index:up"),
                    enabled = index > 0,
                )
                DropdownMenuItem(
                    { Text("Move down") },
                    {
                        open = false
                        actions.move(index, index + 1)
                    },
                    Modifier.testTag("rule:$index:down"),
                    enabled = index < actions.count - 1,
                )
                DropdownMenuItem(
                    { Text("Duplicate") },
                    {
                        open = false
                        actions.duplicate(index)
                    },
                    Modifier.testTag("rule:$index:duplicate"),
                )
            }
        }
        TextButton({ actions.delete(index) }, Modifier.testTag("rule:$index:delete")) { Text("Delete") }
    }
}

@Composable
private fun RuleCard(
    index: Int,
    rule: Rule,
    problems: List<Pair<BlockLocation?, String>>,
    actions: RuleActions,
    onEdit: (Editing) -> Unit,
) {
    fun problem(
        part: BlockPart,
        k: Int,
    ) = problems.firstOrNull { it.first == BlockLocation(index, part, k) }?.second
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), MaterialTheme.shapes.medium)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RuleHeader(index, "Rule ${index + 1}", actions)
        PartLabel("When")
        Block(RuleBlocks.describe(rule.condition), "rule:$index:if:0", problem(BlockPart.Condition, 0)) {
            onEdit(Editing(index, BlockPart.Condition, 0, isNew = false))
        }
        rule.alsoIf.forEachIndexed { k, condition ->
            PartLabel("And if")
            Block(RuleBlocks.describe(condition), "rule:$index:if:${k + 1}", problem(BlockPart.Condition, k + 1)) {
                onEdit(Editing(index, BlockPart.Condition, k + 1, isNew = false))
            }
        }
        TextButton(
            { onEdit(Editing(index, BlockPart.Condition, rule.alsoIf.size + 1, isNew = true)) },
            Modifier.testTag("rule:$index:add-if"),
        ) {
            Text("+ And if")
        }
        PartLabel("Do")
        rule.actions.forEachIndexed { k, action ->
            Block(RuleBlocks.describe(action), "rule:$index:do:$k", problem(BlockPart.Action, k)) {
                onEdit(Editing(index, BlockPart.Action, k, isNew = false))
            }
        }
        TextButton({ onEdit(Editing(index, BlockPart.Action, rule.actions.size, isNew = true)) }, Modifier.testTag("rule:$index:add-do")) {
            Text("+ Do")
        }
    }
}

@Composable
private fun AdvancedCard(
    index: Int,
    label: String,
    actions: RuleActions,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), MaterialTheme.shapes.medium)
            .padding(10.dp),
    ) {
        RuleHeader(index, "Advanced rule: $label", actions, titleTag = "rule:$index:advanced")
    }
}

@Composable
private fun PartLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
}

/** A tappable block. A problem is shown in red under it, in plain language. */
@Composable
private fun Block(
    text: String,
    tag: String,
    problem: String?,
    onClick: () -> Unit,
) {
    Column {
        Text(
            text,
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.small)
                .then(if (problem != null) Modifier.border(2.dp, MaterialTheme.colorScheme.error, MaterialTheme.shapes.small) else Modifier)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .testTag(tag),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        if (problem != null) Problem(problem)
    }
}

@Composable
private fun Problem(message: String) {
    Text("⚠ $message", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

/**
 * Chooses or edits one block. The block kinds, their names, help, settings, limits and starting values all come from
 * [RuleBlockCatalog], so a new block appears here, and in agent tools, without new screen code.
 */
@Composable
private fun BlockDialog(
    state: EditorState,
    editing: Editing,
    rule: Rule?,
    onClose: () -> Unit,
    onRemove: () -> Unit,
    onDone: (Any) -> Unit,
) {
    val actionPart = editing.part == BlockPart.Action
    val specs: List<BlockSpec<*>> = if (actionPart) RuleBlockCatalog.actions else RuleBlockCatalog.conditions
    val existing: Picked? =
        when {
            editing.isNew || rule == null -> null
            actionPart -> rule.actions.getOrNull(editing.index)?.let(RuleBlockCatalog::picked)
            editing.index == 0 -> RuleBlockCatalog.picked(rule.condition)
            else -> rule.alsoIf.getOrNull(editing.index - 1)?.let(RuleBlockCatalog::picked)
        }
    var draft by remember(editing) { mutableStateOf(existing) }
    val tags = state.tags()
    val canRemove = !editing.isNew && !(editing.part == BlockPart.Condition && editing.index == 0)
    val built: BlockResult<Any>? = draft?.let { if (actionPart) RuleBlockCatalog.action(it) else RuleBlockCatalog.condition(it) }
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Text(
                if (actionPart) {
                    "Do"
                } else if (editing.index == 0) {
                    "When"
                } else {
                    "And if"
                },
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (spec in specs) {
                        FilterChip(
                            draft?.variant == spec.id,
                            { if (draft?.variant != spec.id) draft = Picked(spec.id, spec.defaults()) },
                            { Text(spec.authoring.name) },
                            Modifier.testTag("kind:${spec.id}"),
                        )
                    }
                }
                val picked = draft
                val spec = specs.firstOrNull { it.id == picked?.variant }
                if (picked != null && spec != null) {
                    Text(
                        spec.authoring.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    for (parameter in spec.parameters) {
                        ParameterField(parameter, picked.arguments[parameter.id] ?: parameter.default, tags) { value ->
                            draft = picked.copy(arguments = picked.arguments + (parameter.id to value))
                        }
                    }
                }
                when (built) {
                    is BlockResult.Created -> {
                        Text(
                            describe(built.block),
                            Modifier.testTag("block-preview"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    is BlockResult.Invalid -> {
                        Problem(built.message)
                    }

                    null -> {}
                }
            }
        },
        confirmButton = {
            Button(
                { (built as? BlockResult.Created)?.let { onDone(it.block) } },
                Modifier.testTag("block-done"),
                enabled = built is BlockResult.Created,
            ) { Text("Done") }
        },
        dismissButton = {
            Row {
                if (canRemove) TextButton(onRemove, Modifier.testTag("block-remove")) { Text("Remove") }
                TextButton(onClose, Modifier.testTag("block-cancel")) { Text("Cancel") }
            }
        },
    )
}

private fun describe(block: Any): String = if (block is Condition) RuleBlocks.describe(block) else RuleBlocks.describe(block as Action)

/**
 * The control generated for one catalog [parameter]. Test tags use the parameter ID, so generated controls can be
 * driven by tests and assistive tools alike.
 */
@Composable
private fun ParameterField(
    parameter: Parameter,
    value: Any?,
    tags: List<String>,
    onChange: (Any) -> Unit,
) {
    when (val type = parameter.type) {
        is ParameterType.Value -> ValueField(parameter, type, value, tags, onChange)
        is ParameterType.OneOf -> VariantField(parameter, type, value as? Picked, tags, onChange)
        else -> Text("${parameter.name} is chosen elsewhere", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ValueField(
    parameter: Parameter,
    type: ParameterType.Value,
    value: Any?,
    tags: List<String>,
    onChange: (Any) -> Unit,
) {
    val id = parameter.id
    when (val kind = type.kind) {
        is ValueKind.WholeNumber -> {
            val label = if (kind.unit.isEmpty()) parameter.name else "${parameter.name} (${kind.unit})"
            Stepper(label, value as? Long ?: 0L, id, kind.min, kind.max, kind.step) { onChange(it) }
        }

        is ValueKind.Decimal -> {
            val label = if (kind.unit.isEmpty()) parameter.name else "${parameter.name} (${kind.unit})"
            DecimalStepper(label, value as? Double ?: 0.0, id, kind.min, kind.max) { onChange(it) }
        }

        is ValueKind.Text -> {
            val text = value as? String ?: ""
            if (type.suggestions == Suggestions.TAGS) {
                TagPicker(id, parameter.name, text, tags, kind.maxLength) { onChange(it) }
            } else {
                OutlinedTextField(
                    text,
                    { onChange(it.take(kind.maxLength)) },
                    Modifier.fillMaxWidth().testTag(id),
                    label = { Text(parameter.name) },
                    singleLine = !kind.multiline,
                )
            }
        }

        is ValueKind.Choice -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(parameter.name, style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (option in kind.options) {
                        FilterChip(
                            value == option.id,
                            { onChange(option.id) },
                            { Text(option.label) },
                            Modifier.testTag("$id:${option.id}"),
                        )
                    }
                }
            }
        }

        ValueKind.YesNo -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(parameter.name, Modifier.weight(1f))
                Switch(value as? Boolean ?: false, { onChange(it) }, Modifier.testTag(id))
            }
        }

        ValueKind.Color -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(parameter.name, style = MaterialTheme.typography.labelMedium)
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
                                ).clickable { onChange(swatch) }
                                .testTag("$id:${swatch.toString(16)}"),
                        )
                    }
                }
            }
        }

        is ValueKind.ObjectReference -> {
            Text("${parameter.name}: choosing objects arrives in a later version", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Chips for each alternative. An alternative whose only setting is a tag offers the scene's tags directly, so "which
 * object" reads "This object · coin · score" rather than taking two steps.
 */
@Composable
private fun VariantField(
    parameter: Parameter,
    type: ParameterType.OneOf,
    value: Picked?,
    tags: List<String>,
    onChange: (Any) -> Unit,
) {
    val id = parameter.id
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(parameter.name, style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (variant in type.variants) {
                val tagParameter = variant.tagParameter()
                if (tagParameter != null) {
                    for (tag in tags) {
                        val choice = Picked(variant.id, mapOf(tagParameter.id to tag))
                        FilterChip(value == choice, { onChange(choice) }, { Text(tag) }, Modifier.testTag("$id:$tag"))
                    }
                }
                FilterChip(
                    value?.variant == variant.id && (tagParameter == null || value.arguments[tagParameter.id] !in tags),
                    { if (value?.variant != variant.id) onChange(Picked(variant.id, variant.defaults())) },
                    { Text(if (tagParameter != null) "${variant.authoring.name}…" else variant.authoring.name) },
                    Modifier.testTag("$id:${variant.id}"),
                )
            }
        }
        val chosen = type.variants.firstOrNull { it.id == value?.variant }
        if (value != null && chosen != null) {
            for (nested in chosen.parameters) {
                val nestedValue = value.arguments[nested.id] ?: nested.default
                val tagParameter = chosen.tagParameter()
                if (nested == tagParameter) {
                    OutlinedTextField(
                        nestedValue as? String ?: "",
                        { onChange(value.copy(arguments = value.arguments + (nested.id to it.take(nested.maxLength())))) },
                        Modifier.fillMaxWidth().testTag("$id-field"),
                        label = { Text("Or type a tag") },
                        singleLine = true,
                    )
                } else {
                    ParameterField(nested, nestedValue, tags) { onChange(value.copy(arguments = value.arguments + (nested.id to it))) }
                }
            }
        }
    }
}

/** The single tag setting of [this] variant, or `null` when it has other settings. */
private fun Variant.tagParameter(): Parameter? =
    parameters.singleOrNull()?.takeIf { (it.type as? ParameterType.Value)?.suggestions == Suggestions.TAGS }

private fun Variant.defaults(): Map<String, Any?> = parameters.mapNotNull { p -> p.default?.let { p.id to it } }.toMap()

private fun Parameter.maxLength(): Int = ((type as? ParameterType.Value)?.kind as? ValueKind.Text)?.maxLength ?: Int.MAX_VALUE

/** Tags in the scene as large chips, plus a field for a tag no object has yet. */
@Composable
private fun TagPicker(
    id: String,
    name: String,
    selected: String,
    tags: List<String>,
    maxLength: Int,
    onChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(name, style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (tag in tags) FilterChip(selected == tag, { onChange(tag) }, { Text(tag) }, Modifier.testTag("$id:$tag"))
        }
        OutlinedTextField(
            selected,
            { onChange(it.take(maxLength)) },
            Modifier.fillMaxWidth().testTag("$id-field"),
            label = { Text("Or type a tag") },
            singleLine = true,
        )
    }
}

/** Large −/+ buttons around a whole number, so values can be chosen without a keyboard. */
@Composable
private fun Stepper(
    label: String,
    value: Long,
    tag: String,
    min: Long,
    max: Long,
    step: Long = 1,
    onChange: (Long) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f))
        OutlinedButton({ onChange((value - step).coerceIn(min, max)) }, Modifier.testTag("dec:$tag"), enabled = value > min) { Text("−") }
        Text(value.toString(), Modifier.width(48.dp).testTag("value:$tag"), style = MaterialTheme.typography.titleMedium)
        OutlinedButton({ onChange((value + step).coerceIn(min, max)) }, Modifier.testTag("inc:$tag"), enabled = value < max) { Text("+") }
    }
}

/** A decimal with −/+ buttons that change it by one, keeping any fraction. */
@Composable
private fun DecimalStepper(
    label: String,
    value: Double,
    tag: String,
    min: Double,
    max: Double,
    onChange: (Double) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f))
        OutlinedButton({ onChange((value - 1.0).coerceIn(min, max)) }, Modifier.testTag("dec:$tag"), enabled = value > min) { Text("−") }
        Text(formatNumber(value), Modifier.width(56.dp).testTag("value:$tag"), style = MaterialTheme.typography.titleMedium)
        OutlinedButton({ onChange((value + 1.0).coerceIn(min, max)) }, Modifier.testTag("inc:$tag"), enabled = value < max) { Text("+") }
    }
}

private fun formatNumber(value: Double): String =
    if (value == kotlin.math.floor(value) && kotlin.math.abs(value) < 1e9) value.toLong().toString() else value.toString()
