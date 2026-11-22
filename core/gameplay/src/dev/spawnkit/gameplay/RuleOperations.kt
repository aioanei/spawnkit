package dev.spawnkit.gameplay

import dev.spawnkit.editing.BuildResult
import dev.spawnkit.editing.EditOperation
import dev.spawnkit.editing.OperationCatalog
import dev.spawnkit.editing.OperationRequest
import dev.spawnkit.editing.OperationSpec
import dev.spawnkit.editing.Parameter
import dev.spawnkit.editing.ParameterType
import dev.spawnkit.editing.Picked
import dev.spawnkit.editing.Project
import dev.spawnkit.editing.objectParameter
import dev.spawnkit.editing.sceneParameter
import dev.spawnkit.runtime.AuthoringInfo
import dev.spawnkit.runtime.ChoiceOption
import dev.spawnkit.runtime.ValueKind

/**
 * Rule edits on one object, shared by the builder's Rules panel and by agents (ADR-0009). Each builds one
 * [EditOperation.SetRules] step that keeps advanced rules unchanged and renumbers block rules by position, so
 * problems keep pointing at the right blocks. Indices are zero-based here; the [catalog] uses the rule and block
 * numbers the builder shows, starting at 1.
 */
public object RuleOperations {
    /** Most "And if" blocks in one rule. */
    public const val MAX_AND_IF: Int = 19

    /** Most "Do" blocks in one rule. */
    public const val MAX_ACTIONS: Int = 50

    /** Appends [rule] to the object's rules. */
    public fun addRule(
        project: Project,
        sceneId: String,
        objectId: String,
        rule: Rule,
    ): BuildResult =
        edit(project, sceneId, objectId, { "Add a rule to $it" }) { items ->
            checkSize(rule)
            items += RuleItem.Blocks(rule)
        }

    /** Deletes rule [index], whether it is made of blocks or advanced. */
    public fun removeRule(
        project: Project,
        sceneId: String,
        objectId: String,
        index: Int,
    ): BuildResult =
        edit(project, sceneId, objectId, { "Delete rule ${index + 1} of $it" }) { items ->
            requireRule(items, index)
            items.removeAt(index)
        }

    /** Moves rule [from] to position [to]; rules run in order, so this changes which rule acts first. */
    public fun moveRule(
        project: Project,
        sceneId: String,
        objectId: String,
        from: Int,
        to: Int,
    ): BuildResult =
        edit(project, sceneId, objectId, { "Move rule ${from + 1} of $it to ${to + 1}" }) { items ->
            requireRule(items, from)
            requireRule(items, to)
            items.add(to, items.removeAt(from))
        }

    /** Copies rule [index] and places the copy right after it. */
    public fun duplicateRule(
        project: Project,
        sceneId: String,
        objectId: String,
        index: Int,
    ): BuildResult =
        edit(project, sceneId, objectId, { "Duplicate rule ${index + 1} of $it" }) { items ->
            requireRule(items, index)
            items.add(index + 1, items[index])
        }

    /** Appends [condition] as the last "And if" block of rule [rule]. */
    public fun addBlock(
        project: Project,
        sceneId: String,
        objectId: String,
        rule: Int,
        condition: Condition,
    ): BuildResult =
        editBlocks(project, sceneId, objectId, rule, "Add a block to") { current ->
            current.copy(alsoIf = current.alsoIf + condition).also(::checkSize)
        }

    /** Appends [action] as the last "Do" block of rule [rule]. */
    public fun addBlock(
        project: Project,
        sceneId: String,
        objectId: String,
        rule: Int,
        action: Action,
    ): BuildResult =
        editBlocks(project, sceneId, objectId, rule, "Add a block to") { current ->
            current.copy(actions = current.actions + action).also(::checkSize)
        }

    /** Replaces the condition at [location] ("When" at index 0, then "And if" blocks). */
    public fun changeBlock(
        project: Project,
        sceneId: String,
        objectId: String,
        location: BlockLocation,
        condition: Condition,
    ): BuildResult =
        editBlocks(project, sceneId, objectId, location.rule, "Change a block in") { current ->
            if (location.part != BlockPart.Condition) refuse("${describe(condition)} is a condition; a Do block must be an action")
            when (location.index) {
                0 -> current.copy(condition = condition)
                else -> current.copy(alsoIf = current.alsoIf.replaced(location.index - 1, condition, "And if"))
            }
        }

    /** Replaces the action at [location]. */
    public fun changeBlock(
        project: Project,
        sceneId: String,
        objectId: String,
        location: BlockLocation,
        action: Action,
    ): BuildResult =
        editBlocks(project, sceneId, objectId, location.rule, "Change a block in") { current ->
            if (location.part != BlockPart.Action) refuse("${describe(action)} is an action; When and And if blocks must be conditions")
            current.copy(actions = current.actions.replaced(location.index, action, "Do"))
        }

    /** Removes the "And if" or "Do" block at [location]. A rule always keeps its "When" block. */
    public fun removeBlock(
        project: Project,
        sceneId: String,
        objectId: String,
        location: BlockLocation,
    ): BuildResult =
        editBlocks(project, sceneId, objectId, location.rule, "Remove a block from") { current ->
            when {
                location.part == BlockPart.Action -> {
                    current.copy(actions = current.actions.removed(location.index, "Do"))
                }

                location.index == 0 -> {
                    refuse("A rule always has a When block; change it instead, or delete the rule")
                }

                else -> {
                    current.copy(alsoIf = current.alsoIf.removed(location.index - 1, "And if"))
                }
            }
        }

    /** Catalog entries for every rule edit, using the rule and block numbers the builder shows. */
    public val catalog: OperationCatalog by lazy {
        OperationCatalog(
            listOf(addRuleSpec, addBlockSpec, changeBlockSpec, removeBlockSpec, moveRuleSpec, duplicateRuleSpec, removeRuleSpec),
        )
    }

    private val addRuleSpec =
        OperationSpec(
            "add_rule",
            AuthoringInfo(
                "Add a rule",
                "Adds a When / If / Do rule to an object: when the When block and every And if block are true, the Do " +
                    "blocks run in order. Rules are checked on every moment of play.",
                RULES,
            ),
            listOf(
                sceneParameter(),
                objectParameter(),
                Parameter("when", "When", "The block that starts the rule.", RuleBlockCatalog.conditionType),
                Parameter(
                    "and_if",
                    "And if",
                    "Further conditions that must all be true.",
                    ParameterType.ListOf(RuleBlockCatalog.conditionType, MAX_AND_IF),
                    default = emptyList<Any>(),
                    required = false,
                ),
                Parameter(
                    "do",
                    "Do",
                    "Actions to run, in order.",
                    ParameterType.ListOf(RuleBlockCatalog.actionType, MAX_ACTIONS),
                    default = emptyList<Any>(),
                    required = false,
                ),
            ),
            listOf("When the coin touches the player, add 1 to the score and remove the coin", "While holding Right, move right"),
        ) {
            val rule =
                Rule(
                    condition(picked("when")),
                    list("and_if").map { condition(it as Picked) },
                    list("do").map { action(it as Picked) },
                )
            built(addRule(project, text("scene"), obj().id, rule))
        }

    private val addBlockSpec =
        OperationSpec(
            "add_block",
            AuthoringInfo(
                "Add a block to a rule",
                "Adds a block at the end of a rule: a condition becomes the last And if block, an action the last Do block.",
                RULES,
            ),
            listOf(sceneParameter(), objectParameter(), ruleParameter(), blockParameter()),
            listOf("Also remove the coin when it is collected", "Only when the score is below 10"),
        ) {
            val picked = picked("block")
            val rule = ruleIndex()
            val result =
                if (isCondition(picked)) {
                    addBlock(project, text("scene"), obj().id, rule, condition(picked))
                } else {
                    addBlock(project, text("scene"), obj().id, rule, action(picked))
                }
            built(result)
        }

    private val changeBlockSpec =
        OperationSpec(
            "change_block",
            AuthoringInfo(
                "Change a block",
                "Replaces one block of a rule. The When block and And if blocks take conditions; Do blocks take actions.",
                RULES,
            ),
            listOf(sceneParameter(), objectParameter(), ruleParameter(), partParameter(), positionParameter(), blockParameter()),
            listOf("Make coins worth 5", "Start the rule when the player taps instead"),
        ) {
            val picked = picked("block")
            val location = location()
            val result =
                if (location.part == BlockPart.Condition) {
                    if (!isCondition(picked)) reject("${picked.variant} is an action; When and And if blocks must be conditions")
                    changeBlock(project, text("scene"), obj().id, location, condition(picked))
                } else {
                    if (isCondition(picked)) reject("${picked.variant} is a condition; a Do block must be an action")
                    changeBlock(project, text("scene"), obj().id, location, action(picked))
                }
            built(result)
        }

    private val removeBlockSpec =
        OperationSpec(
            "remove_block",
            AuthoringInfo("Remove a block", "Removes an And if or Do block from a rule. The When block cannot be removed.", RULES),
            listOf(sceneParameter(), objectParameter(), ruleParameter(), partParameter(), positionParameter()),
            listOf("Stop removing the coin", "Drop the score check"),
        ) { built(removeBlock(project, text("scene"), obj().id, location())) }

    private val moveRuleSpec =
        OperationSpec(
            "move_rule",
            AuthoringInfo("Move a rule", "Moves a rule to another position. Rules run in order, so this changes which acts first.", RULES),
            listOf(
                sceneParameter(),
                objectParameter(),
                ruleParameter(),
                Parameter("to", "To", "New rule number, starting at 1.", ParameterType.Value(ValueKind.WholeNumber(1, MAX_RULE))),
            ),
            listOf("Check the win rule before the score rule"),
        ) { built(moveRule(project, text("scene"), obj().id, ruleIndex(), (whole("to") - 1).toInt())) }

    private val duplicateRuleSpec =
        OperationSpec(
            "duplicate_rule",
            AuthoringInfo("Duplicate a rule", "Copies a rule and places the copy right after it, ready to change.", RULES),
            listOf(sceneParameter(), objectParameter(), ruleParameter()),
            listOf("Make a copy of the move right rule for moving left"),
        ) { built(duplicateRule(project, text("scene"), obj().id, ruleIndex())) }

    private val removeRuleSpec =
        OperationSpec(
            "remove_rule",
            AuthoringInfo("Delete a rule", "Deletes a rule, whether it is made of blocks or advanced.", RULES),
            listOf(sceneParameter(), objectParameter(), ruleParameter()),
            listOf("Delete the timer rule"),
        ) { built(removeRule(project, text("scene"), obj().id, ruleIndex())) }

    private fun ruleParameter() =
        Parameter(
            "rule",
            "Rule",
            "Rule number as the builder shows it; 1 is the first rule.",
            ParameterType.Value(ValueKind.WholeNumber(1, MAX_RULE)),
        )

    private fun blockParameter() = Parameter("block", "Block", "The block, with its settings.", RuleBlockCatalog.blockType)

    private fun partParameter() =
        Parameter(
            "part",
            "Part",
            "Which part of the rule the block is in.",
            ParameterType.Value(
                ValueKind.Choice(listOf(ChoiceOption(WHEN, "When"), ChoiceOption(AND_IF, "And if"), ChoiceOption(DO, "Do"))),
            ),
        )

    private fun positionParameter() =
        Parameter(
            "position",
            "Position",
            "Block number within And if or Do, starting at 1; ignored for When.",
            ParameterType.Value(ValueKind.WholeNumber(1, MAX_ACTIONS.toLong())),
            default = 1L,
            required = false,
        )

    private fun OperationRequest.ruleIndex(): Int = (whole("rule") - 1).toInt()

    private fun OperationRequest.location(): BlockLocation {
        val position = whole("position").toInt()
        return when (text("part")) {
            WHEN -> BlockLocation(ruleIndex(), BlockPart.Condition, 0)
            AND_IF -> BlockLocation(ruleIndex(), BlockPart.Condition, position)
            else -> BlockLocation(ruleIndex(), BlockPart.Action, position - 1)
        }
    }

    private fun isCondition(picked: Picked): Boolean = RuleBlockCatalog.conditions.any { it.id == picked.variant }

    private fun OperationRequest.condition(picked: Picked): Condition =
        when (val result = RuleBlockCatalog.condition(picked)) {
            is BlockResult.Created -> result.block
            is BlockResult.Invalid -> reject(result.message)
        }

    private fun OperationRequest.action(picked: Picked): Action =
        when (val result = RuleBlockCatalog.action(picked)) {
            is BlockResult.Created -> result.block
            is BlockResult.Invalid -> reject(result.message)
        }

    private fun OperationRequest.built(result: BuildResult): EditOperation =
        when (result) {
            is BuildResult.Ready -> result.operation
            is BuildResult.Rejected -> reject(result.message)
        }

    private class RuleProblem(
        message: String,
    ) : Exception(message)

    private fun refuse(message: String): Nothing = throw RuleProblem(message)

    private fun edit(
        project: Project,
        sceneId: String,
        objectId: String,
        label: (String) -> String,
        change: (MutableList<RuleItem>) -> Unit,
    ): BuildResult {
        val scene = project.scene(sceneId) ?: return BuildResult.Rejected("That scene no longer exists")
        val obj = scene.find(objectId) ?: return BuildResult.Rejected("That object no longer exists")
        val items = RuleBlocks.decompile(obj.rules).toMutableList()
        val earlierTargets = sceneTargets(items)
        try {
            change(items)
        } catch (problem: RuleProblem) {
            return BuildResult.Rejected(problem.message ?: "This rule change is not possible")
        }
        // A change may not add a "Go to scene" to a scene that does not exist; targets that were already there stay.
        if ((sceneTargets(items) - earlierTargets).any { project.scene(it) == null }) {
            return BuildResult.Rejected("Go to scene: choose one of the game's scenes")
        }
        return BuildResult.Ready(EditOperation.SetRules(sceneId, objectId, RuleBlocks.compileItems(items), label(obj.name)))
    }

    private fun editBlocks(
        project: Project,
        sceneId: String,
        objectId: String,
        rule: Int,
        verb: String,
        change: (Rule) -> Rule,
    ): BuildResult =
        edit(project, sceneId, objectId, { "$verb rule ${rule + 1} of $it" }) { items ->
            requireRule(items, rule)
            val current =
                items[rule] as? RuleItem.Blocks ?: refuse("Rule ${rule + 1} is an advanced rule; its blocks cannot be changed here")
            items[rule] = RuleItem.Blocks(change(current.rule))
        }

    private fun requireRule(
        items: List<RuleItem>,
        index: Int,
    ) {
        if (index !in items.indices) {
            refuse(
                if (items.isEmpty()) "This object has no rules yet" else "There is no rule ${index + 1}; rules go from 1 to ${items.size}",
            )
        }
    }

    private fun sceneTargets(items: List<RuleItem>): Set<String> =
        items
            .filterIsInstance<RuleItem.Blocks>()
            .flatMap { it.rule.actions }
            .filterIsInstance<Action.GoToScene>()
            .map { it.sceneId }
            .toSet()

    private fun checkSize(rule: Rule) {
        if (rule.alsoIf.size > MAX_AND_IF) refuse("A rule can have at most $MAX_AND_IF And if blocks")
        if (rule.actions.size > MAX_ACTIONS) refuse("A rule can have at most $MAX_ACTIONS Do blocks")
    }

    private fun <T> List<T>.replaced(
        index: Int,
        value: T,
        part: String,
    ): List<T> {
        if (index !in indices) refuse(missing(part, index))
        return mapIndexed { i, old -> if (i == index) value else old }
    }

    private fun <T> List<T>.removed(
        index: Int,
        part: String,
    ): List<T> {
        if (index !in indices) refuse(missing(part, index))
        return filterIndexed { i, _ -> i != index }
    }

    private fun List<*>.missing(
        part: String,
        index: Int,
    ): String = if (isEmpty()) "This rule has no $part blocks" else "There is no $part block ${index + 1}; they go from 1 to $size"

    private fun describe(condition: Condition): String = RuleBlocks.describe(condition)

    private fun describe(action: Action): String = RuleBlocks.describe(action)

    private const val RULES = "Rules"
    private const val WHEN = "when"
    private const val AND_IF = "and_if"
    private const val DO = "do"
    private const val MAX_RULE = 200L
}
