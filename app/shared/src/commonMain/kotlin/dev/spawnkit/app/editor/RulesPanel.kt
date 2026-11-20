package dev.spawnkit.app.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import dev.spawnkit.gameplay.Action
import dev.spawnkit.gameplay.BlockLocation
import dev.spawnkit.gameplay.BlockPart
import dev.spawnkit.gameplay.Comparison
import dev.spawnkit.gameplay.Condition
import dev.spawnkit.gameplay.Direction
import dev.spawnkit.gameplay.Rule
import dev.spawnkit.gameplay.RuleBlocks
import dev.spawnkit.gameplay.RuleItem
import dev.spawnkit.gameplay.StandardActions
import dev.spawnkit.gameplay.Target

/** Which block the dialog edits. [isNew] adds a block (or a whole rule when [rule] is past the last rule). */
private data class Editing(
    val rule: Int,
    val part: BlockPart,
    val index: Int,
    val isNew: Boolean,
)

/**
 * Visual When / If / Do rules for the selected object. Every change is one undoable `SetRules` step. Rules made by
 * other tools that are not blocks are shown as advanced rules and kept unchanged.
 */
@Composable
fun RulesPanel(
    state: EditorState,
    modifier: Modifier = Modifier,
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
        items.forEachIndexed { index, item ->
            when (item) {
                is RuleItem.Blocks -> {
                    RuleCard(index, item.rule, problems, onEdit = { editing = it }) {
                        state.setRuleItems(obj.id, items.filterIndexed { i, _ -> i != index })
                    }
                }

                is RuleItem.Advanced -> {
                    AdvancedCard(index, item.handler.source.label) {
                        state.setRuleItems(
                            obj.id,
                            items.filterIndexed {
                                i,
                                _,
                                ->
                                i != index
                            },
                        )
                    }
                }
            }
        }
        Button({ editing = Editing(items.size, BlockPart.Condition, 0, isNew = true) }, Modifier.testTag("add-rule")) {
            Text("+ Add rule")
        }
    }
    val current = editing ?: return
    val objectId = obj?.id ?: return
    BlockDialog(state, current, state.ruleItems(objectId), onClose = { editing = null }) { updated ->
        state.setRuleItems(objectId, updated)
        editing = null
    }
}

@Composable
private fun RuleCard(
    index: Int,
    rule: Rule,
    problems: List<Pair<BlockLocation?, String>>,
    onEdit: (Editing) -> Unit,
    onDelete: () -> Unit,
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Rule ${index + 1}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            TextButton(onDelete, Modifier.testTag("rule:$index:delete")) { Text("Delete") }
        }
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
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), MaterialTheme.shapes.medium)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Advanced rule: $label", Modifier.weight(1f).testTag("rule:$index:advanced"), style = MaterialTheme.typography.bodyMedium)
        TextButton(onDelete, Modifier.testTag("rule:$index:delete")) { Text("Delete") }
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

private val ConditionKinds: List<Pair<String, String>> =
    listOf(
        "Always" to "Every moment",
        "GameStarts" to "Game starts",
        "EverySeconds" to "Every … seconds",
        "Touching" to "Touching",
        "Tapped" to "Tapped",
        "KeyHeld" to "Holding a key",
        "KeyPressed" to "Pressing a key",
        "NumberIs" to "Number is",
        "CountIs" to "Count of objects",
    )

private val ActionKinds: List<Pair<String, String>> =
    listOf(
        "Move" to "Move",
        "ChangeNumber" to "Add to a number",
        "SetNumber" to "Set a number",
        "ShowNumber" to "Show a number",
        "RemoveSelf" to "Remove this object",
    )

/** Stable kind names (used in tags and pickers); explicit so they never depend on reflection or minification. */
private fun kindOf(block: Any): String =
    when (block) {
        Condition.Always -> "Always"
        Condition.GameStarts -> "GameStarts"
        is Condition.EverySeconds -> "EverySeconds"
        is Condition.Touching -> "Touching"
        Condition.Tapped -> "Tapped"
        is Condition.KeyHeld -> "KeyHeld"
        is Condition.KeyPressed -> "KeyPressed"
        is Condition.NumberIs -> "NumberIs"
        is Condition.CountIs -> "CountIs"
        is Action.Move -> "Move"
        is Action.ChangeNumber -> "ChangeNumber"
        is Action.SetNumber -> "SetNumber"
        is Action.ShowNumber -> "ShowNumber"
        Action.RemoveSelf -> "RemoveSelf"
        else -> ""
    }

@Composable
private fun BlockDialog(
    state: EditorState,
    editing: Editing,
    items: List<RuleItem>,
    onClose: () -> Unit,
    onApply: (List<RuleItem>) -> Unit,
) {
    val rule = (items.getOrNull(editing.rule) as? RuleItem.Blocks)?.rule
    val existing: Any? =
        when {
            editing.isNew || rule == null -> null
            editing.part == BlockPart.Action -> rule.actions.getOrNull(editing.index)
            editing.index == 0 -> rule.condition
            else -> rule.alsoIf.getOrNull(editing.index - 1)
        }
    var draft by remember(editing) { mutableStateOf(existing) }
    val tags = state.tags()
    val canRemove = !editing.isNew && !(editing.part == BlockPart.Condition && editing.index == 0)

    fun apply(block: Any?) {
        val updated =
            if (rule == null) {
                items + RuleItem.Blocks(Rule(block as Condition))
            } else {
                val changed =
                    when {
                        editing.part == BlockPart.Action -> {
                            rule.copy(
                                actions = rule.actions.edited(editing.index, editing.isNew, block as Action?),
                            )
                        }

                        editing.index == 0 -> {
                            rule.copy(condition = block as Condition)
                        }

                        else -> {
                            rule.copy(alsoIf = rule.alsoIf.edited(editing.index - 1, editing.isNew, block as Condition?))
                        }
                    }
                items.mapIndexed { i, item -> if (i == editing.rule) RuleItem.Blocks(changed) else item }
            }
        onApply(updated)
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Text(
                if (editing.part == BlockPart.Action) {
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
                val kinds = if (editing.part == BlockPart.Action) ActionKinds else ConditionKinds
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((kind, label) in kinds) {
                        FilterChip(
                            draft?.let(::kindOf) == kind,
                            { if (draft?.let(::kindOf) != kind) draft = defaultBlock(kind, tags) },
                            { Text(label) },
                            Modifier.testTag("kind:$kind"),
                        )
                    }
                }
                when (val block = draft) {
                    is Condition -> ConditionFields(block, tags) { draft = it }
                    is Action -> ActionFields(block, tags) { draft = it }
                }
                draft?.let {
                    Text(
                        if (it is Condition) RuleBlocks.describe(it) else RuleBlocks.describe(it as Action),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button({ apply(draft) }, Modifier.testTag("block-done"), enabled = draft != null) { Text("Done") }
        },
        dismissButton = {
            Row {
                if (canRemove) TextButton({ apply(null) }, Modifier.testTag("block-remove")) { Text("Remove") }
                TextButton(onClose, Modifier.testTag("block-cancel")) { Text("Cancel") }
            }
        },
    )
}

/** Replaces, appends ([isNew]) or removes (null [block]) the element at [index]. */
private fun <T> List<T>.edited(
    index: Int,
    isNew: Boolean,
    block: T?,
): List<T> =
    when {
        block == null -> filterIndexed { i, _ -> i != index }
        isNew -> this + block
        else -> mapIndexed { i, old -> if (i == index) block else old }
    }

private fun defaultBlock(
    kind: String,
    tags: List<String>,
): Any =
    when (kind) {
        "Always" -> Condition.Always
        "GameStarts" -> Condition.GameStarts
        "EverySeconds" -> Condition.EverySeconds(1)
        "Touching" -> Condition.Touching("")
        "Tapped" -> Condition.Tapped
        "KeyHeld" -> Condition.KeyHeld(StandardActions.RIGHT)
        "KeyPressed" -> Condition.KeyPressed(StandardActions.JUMP)
        "NumberIs" -> Condition.NumberIs(Target.Self, Comparison.Equal, 0)
        "CountIs" -> Condition.CountIs(tags.firstOrNull() ?: "", Comparison.Equal, 0)
        "Move" -> Action.Move(Direction.Right, 3.0)
        "ChangeNumber" -> Action.ChangeNumber(Target.Self, 1)
        "SetNumber" -> Action.SetNumber(Target.Self, 0)
        "ShowNumber" -> Action.ShowNumber(Target.Self, "Score: ")
        else -> Action.RemoveSelf
    }

@Composable
private fun ConditionFields(
    block: Condition,
    tags: List<String>,
    onChange: (Condition) -> Unit,
) {
    when (block) {
        is Condition.EverySeconds -> {
            Stepper("Seconds", block.seconds, "seconds", min = 1) { onChange(Condition.EverySeconds(it)) }
        }

        is Condition.Touching -> {
            TagPicker(block.tag, tags) { onChange(Condition.Touching(it)) }
        }

        is Condition.KeyHeld -> {
            ActionPicker(block.action) { onChange(Condition.KeyHeld(it)) }
        }

        is Condition.KeyPressed -> {
            ActionPicker(block.action) { onChange(Condition.KeyPressed(it)) }
        }

        is Condition.NumberIs -> {
            TargetPicker(block.target, tags) { onChange(block.copy(target = it)) }
            ComparisonPicker(block.comparison) { onChange(block.copy(comparison = it)) }
            Stepper("Value", block.value, "value") { onChange(block.copy(value = it)) }
        }

        is Condition.CountIs -> {
            TagPicker(block.tag, tags) { onChange(block.copy(tag = it)) }
            ComparisonPicker(block.comparison) { onChange(block.copy(comparison = it)) }
            Stepper("Value", block.value, "value", min = 0) { onChange(block.copy(value = it)) }
        }

        Condition.Always, Condition.GameStarts, Condition.Tapped -> {}
    }
}

@Composable
private fun ActionFields(
    block: Action,
    tags: List<String>,
    onChange: (Action) -> Unit,
) {
    when (block) {
        is Action.Move -> {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (direction in Direction.entries) {
                    FilterChip(
                        block.direction == direction,
                        { onChange(block.copy(direction = direction)) },
                        { Text(direction.label) },
                        Modifier.testTag("direction:${direction.name}"),
                    )
                }
            }
            Stepper(
                "Speed (per second)",
                block.speed.toLong(),
                "speed",
                min = 0,
                max = 1_000,
            ) { onChange(block.copy(speed = it.toDouble())) }
        }

        is Action.ChangeNumber -> {
            TargetPicker(block.target, tags) { onChange(block.copy(target = it)) }
            Stepper("Amount", block.by, "amount") { onChange(block.copy(by = it)) }
        }

        is Action.SetNumber -> {
            TargetPicker(block.target, tags) { onChange(block.copy(target = it)) }
            Stepper("Value", block.to, "value") { onChange(block.copy(to = it)) }
        }

        is Action.ShowNumber -> {
            TargetPicker(block.target, tags) { onChange(block.copy(target = it)) }
            OutlinedTextField(
                block.prefix,
                { onChange(block.copy(prefix = it.take(MAX_PREFIX))) },
                Modifier.fillMaxWidth().testTag("prefix"),
                label = { Text("Text before the number") },
                singleLine = true,
            )
        }

        Action.RemoveSelf -> {}
    }
}

/** Tags in the scene as large chips, plus a field for a tag no object has yet. */
@Composable
private fun TagPicker(
    selected: String,
    tags: List<String>,
    onChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Tag", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (tag in tags) FilterChip(selected == tag, { onChange(tag) }, { Text(tag) }, Modifier.testTag("tag:$tag"))
        }
        OutlinedTextField(
            selected,
            { onChange(it.take(MAX_TAG)) },
            Modifier.fillMaxWidth().testTag("tag-field"),
            label = { Text("Or type a tag") },
            singleLine = true,
        )
    }
}

@Composable
private fun TargetPicker(
    selected: Target,
    tags: List<String>,
    onChange: (Target) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Which object", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected == Target.Self, { onChange(Target.Self) }, { Text("This object") }, Modifier.testTag("target:self"))
            for (tag in tags) {
                FilterChip(selected == Target.Tagged(tag), { onChange(Target.Tagged(tag)) }, { Text(tag) }, Modifier.testTag("target:$tag"))
            }
        }
    }
}

@Composable
private fun ActionPicker(
    selected: String,
    onChange: (String) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((id, name) in StandardActions.ALL) {
            FilterChip(selected == id, { onChange(id) }, { Text(name) }, Modifier.testTag("action:$id"))
        }
    }
}

@Composable
private fun ComparisonPicker(
    selected: Comparison,
    onChange: (Comparison) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (comparison in Comparison.entries) {
            FilterChip(
                selected == comparison,
                { onChange(comparison) },
                { Text(comparison.symbol) },
                Modifier.testTag("compare:${comparison.name}"),
            )
        }
    }
}

/** Large −/+ buttons around a whole number, so values can be chosen without a keyboard. */
@Composable
private fun Stepper(
    label: String,
    value: Long,
    tag: String,
    min: Long = -1_000_000,
    max: Long = 1_000_000,
    onChange: (Long) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f))
        OutlinedButton({ onChange((value - 1).coerceIn(min, max)) }, Modifier.testTag("dec:$tag")) { Text("−") }
        Text(value.toString(), Modifier.width(48.dp).testTag("value:$tag"), style = MaterialTheme.typography.titleMedium)
        OutlinedButton({ onChange((value + 1).coerceIn(min, max)) }, Modifier.testTag("inc:$tag")) { Text("+") }
    }
}

private const val MAX_TAG = 100
private const val MAX_PREFIX = 200
