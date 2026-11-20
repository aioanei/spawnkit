package dev.spawnkit.build.ktlint

import com.pinterest.ktlint.rule.engine.api.Code
import com.pinterest.ktlint.rule.engine.api.KtLintRuleEngine
import com.pinterest.ktlint.rule.engine.core.api.AutocorrectDecision
import com.pinterest.ktlint.ruleset.standard.StandardRuleSetProvider
import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.TaskAction
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.writeText

/**
 * Reports ktlint violations in the module's Kotlin sources and scripts (`kotlin check ktlint`). Fails the check when
 * any violation is found. The module root is passed as a plain string, not an `@Input` path: it also holds unrelated
 * build outputs that must not be snapshotted, so execution avoidance is disabled instead.
 */
@TaskAction(ExecutionAvoidance.Disabled)
fun ktlintCheck(
    moduleRootDir: String,
    settings: Settings,
) {
    val root = Path.of(moduleRootDir)
    val engine = ruleEngine()
    val violations = mutableListOf<String>()
    kotlinFiles(root, settings).forEach { file ->
        engine.lint(Code.fromFile(file.toFile())) { error ->
            val location = file.relativeTo(root)
            violations += "$location:${error.line}:${error.col}: ${error.detail} (${error.ruleId.value})"
        }
    }
    violations.forEach(::println)
    check(violations.isEmpty()) {
        "ktlint found ${violations.size} violation(s) in ${root.name}. Run `./kotlin do ktlintFormat` to fix " +
            "the auto-correctable ones."
    }
}

/** Formats the module's Kotlin sources and scripts in place (`kotlin do ktlintFormat`). */
@TaskAction(ExecutionAvoidance.Disabled)
fun ktlintFormat(
    moduleRootDir: String,
    settings: Settings,
) {
    val root = Path.of(moduleRootDir)
    val engine = ruleEngine()
    var changed = 0
    kotlinFiles(root, settings).forEach { file ->
        val original = file.readText()
        val formatted = engine.format(Code.fromFile(file.toFile())) { AutocorrectDecision.ALLOW_AUTOCORRECT }
        if (formatted != original) {
            file.writeText(formatted)
            changed++
            println("Formatted ${file.relativeTo(root)}")
        }
    }
    println("ktlint formatted $changed file(s) in ${root.name}.")
}

private fun ruleEngine(): KtLintRuleEngine = KtLintRuleEngine(ruleProviders = StandardRuleSetProvider().getRuleProviders())

/** Every `.kt` and `.kts` file under [root], skipping excluded directories and nested modules' build outputs. */
internal fun kotlinFiles(
    root: Path,
    settings: Settings,
): List<Path> {
    val excluded = settings.excludedDirectories.toSet()
    val files = mutableListOf<Path>()
    Files.walk(root).use { paths ->
        paths
            .filter { path -> !path.isDirectory() && path.extension in setOf("kt", "kts") }
            .filter { path -> path.relativeTo(root).none { it.name in excluded } }
            .sorted()
            .forEach(files::add)
    }
    return files
}
