# C7c Assistant UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task by task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Creators use the C7b assistant from the builder on phone and desktop. They ask, review, accept or reject,
cancel, and answer questions. They use "Ask AI" from Rules and Properties, and can keep an API key in secure storage.
Publishing to the Arcade asks for confirmation.

**Architecture:**
- An `AssistantState` holder in `app/shared` drives one `AgentSession` (from the `ai` module) per model and scene.
- `AssistantPanel` renders it as a third right-panel tab ("Assistant") in the expanded layout and a fifth tab ("AI")
  in the compact layout.
- Models:
  - Live: `KoogModel.anthropic`, with the key read from a platform `SecretStore`.
  - Keyless: a `DemoModel` in `ai` drives the real tools from a fixed script and is always labelled.
  - Tests inject scripted models.

**Tech stack:** Kotlin Multiplatform, Compose Multiplatform 1.12.2, Koog 1.3.0 (C7b), Android Keystore, and Windows
DPAPI through JNA 5.19.1 (jna-platform, Apache-2.0/LGPL-2.1 dual license, maintained).

**Spec:** [issue #69](https://github.com/aioanei/spawnkit/issues/69) (C7 requirements and acceptance),
[AI Studio core](../ai-studio.md), [ADR-0009](../adr/0009-agentic-parity.md).

## Global Constraints

- Every editor screen has compact (< 720 dp) and expanded layouts with touch-sized targets (≥ 48 dp) (AGENTS.md).
- Nothing reaches the game until the creator accepts. Accepting is one undo step, and rejecting or cancelling leaves
  the game unchanged.
- The API key is never shown back, logged, put in a project, export, transcript or tool input, or given to the agent.
- Replays and demos are always labelled. The demo label is exactly:
  `Scripted demo: a fixed script stands in for the AI model (no model is called)`.
- Publishing to the Arcade needs an explicit confirmation (ADR-0009). Agents have no publish tool.
- AI stays optional: the builder works fully without a key, and the assistant is never required.
- No live model calls in tests.

## Review Focus

1. Asking while a game is playing: the creator expects Ask to be disabled, not edits to land on a running game. This
   is tested in Task 3.
2. Changing the scene, or loading or creating a project, while a proposal is open: the creator expects the proposal
   to be dropped, not applied to the wrong project. This is tested in Task 3 (a `loadProject` resets the session).
3. A blank prompt, or an empty key: Ask stays disabled, and Save key is disabled. This is tested in Tasks 3 and 4.
4. A live model failing (network, bad key): a plain message, the game unchanged and the key kept. This is tested in
   Task 3 with a throwing model.
5. Accepting after the creator edited by hand during review: a plain refusal, with nothing changed (C7b's stale
   check). This is tested in Task 3.

---

### Task 1: `DemoModel` (keyless, labelled)

**Files:**
- Create: `ai/src/dev/spawnkit/ai/DemoModel.kt`
- Test: `ai/test/dev/spawnkit/ai/DemoModelTest.kt`
- Modify: `docs/ai-studio.md` (a "Scripted demo" section)

**Interfaces:**
- Produces:
  - `public class DemoModel : AgentModel`, with `info = ModelInfo("Scripted demo", "none", live = false, label = DemoModel.LABEL)`.
  - `public companion object { public const val LABEL: String; public val PROMPTS: List<String> }`, where `PROMPTS`
    are exactly:
    1. `When the player touches a coin, add 1 to the score and remove the coin.`
    2. `Make the player move with the arrow keys.`
    3. `Why doesn't my coin rule work?`

**Behaviour.** The model is stateless and reads `request.messages` after the last `AgentMessage.User` prompt. It never
invents IDs.
- **Step 0:** call `look_at_game`.
- **Step 1:** parse the look result. Objects are found by their `gameplay:tag` component value (`player`, `coin`,
  `score`).
  - **Prompt 1:** one `add_rule` per coin: when Touching `player`, do ChangeNumber(tagged `score`, 1),
    ShowNumber(tagged `score`, `Score: `), RemoveSelf. Then `check_game`.
  - **Prompt 2:** four `add_rule` calls on the player: KeyHeld `input:left`/`right`/`up`/`down` → Move with that
    direction at speed 3. Then `check_game`.
  - **Prompt 3:** `trace_rules` for 3 seconds, holding `input:right`.
  - If a required object is missing, finish with the summary
    `This demo needs a Player, a Coin and a Score. Add them from the palette, then ask again.`
- **Step 2:** finish with a plain summary. For prompt 3, the summary is the trace text.
- **Any other prompt:** finish at once with
  `The scripted demo only knows its example prompts. Add an Anthropic API key in the assistant settings for any request.`

- [ ] Write the failing tests:
  - `collectPromptStagesOneRulePerCoinAndPlaysToScoreOne`: with two coins, the proposal has steps
    `["Add a rule to Coin", "Add a rule to Coin"]`. After accepting, play holding Right reaches `Score: 1`.
  - `movePromptAddsFourKeyRules`.
  - `whyPromptAnswersFromARealTrace`: the outcome is an `Answer` whose text contains `never ran`.
  - `missingObjectsAndUnknownPromptsExplainWithoutChanges`.
  - `labelSaysNoModelIsCalled`: `assertFalse(info.live)` and the exact label.
- [ ] Run: `./kotlin.bat test -m ai -p jvm`. Expected: FAIL (unresolved `DemoModel`).
- [ ] Implement `DemoModel`.
- [ ] Run: `./kotlin.bat test -m ai -p jvm`. Expected: PASS.
- [ ] Commit: `feat(ai): labelled keyless scripted demo model (C7c)`.

### Task 2: `SecretStore` (device secure storage)

**Files:**
- Create: `app/shared/src/dev/spawnkit/app/assistant/SecretStore.kt`, containing:
  - `interface SecretStore { val persistent: Boolean; val where: String; fun read(name: String): String?; fun write(name: String, value: String); fun delete(name: String) }`;
  - `class MemorySecretStore : SecretStore`, with `persistent = false` and `where = "kept until you close SpawnKit"`;
  - `@Composable expect fun rememberSecretStore(): SecretStore`.
- Create the actuals:
  - `src@android/.../SecretStore.android.kt`: AES/GCM key `spawnkit-secrets` in `AndroidKeyStore`; encrypted values
    (IV + ciphertext, Base64) in SharedPreferences `spawnkit_secrets`; `where = "Android Keystore"`.
  - `src@jvm/.../SecretStore.jvm.kt`, class `DesktopSecretStore(dir: java.nio.file.Path)`:
    - Windows: `Crypt32Util.cryptProtectData` into `<dir>/<name>.bin`, with `where = "Windows (DPAPI, your user account)"`.
    - macOS: the `security add-generic-password -U -s SpawnKit -a <name> -w <value>` and `find-generic-password -w`
      commands.
    - Linux: `secret-tool` when it is on PATH.
    - Otherwise it falls back to memory with `persistent = false`. Default `dir`:
      `%APPDATA%/SpawnKit/secrets`, or `~/.spawnkit/secrets`.
  - `src@ios`, `src@js`, `src@wasmJs`: `MemorySecretStore` (stated limitation).
- Modify:
  - `app/shared/module.yaml` and `app/shared/build.gradle.kts`: add `//ai` / `project(":ai")` to common, and
    `$libs.jna.platform` to JVM.
  - `gradle/libs.versions.toml`: `jna = "5.19.1"`, `jna-platform = { module = "net.java.dev.jna:jna-platform", version.ref = "jna" }`.
  - `docs/THIRD_PARTY.md`.
- Test: `app/shared/test@jvm/dev/spawnkit/app/assistant/DesktopSecretStoreTest.kt`:
  - `valuesRoundTripAndAreNotStoredInPlainText`: write `sk-test-123` to a temp dir, read it back, and check that the
    file bytes do not contain `sk-test-123` (Windows only, otherwise skipped with an explanatory early return);
  - `deleteRemovesTheValue`.

- [ ] Write the failing tests, run `./kotlin.bat test -m shared -p jvm` (FAIL), implement, run again (PASS).
- [ ] Commit: `feat(app): device secret storage for assistant keys (C7c)`.

### Task 3: `AssistantState`

**Files:**
- Create: `app/shared/src/dev/spawnkit/app/assistant/AssistantState.kt`
- Test: `app/shared/test@jvm/dev/spawnkit/app/assistant/AssistantStateTest.kt`

**Interfaces:**
- Consumes: `EditorState` (`editor`, `engine`, `sceneId`, `play`, `revision`), `SecretStore` (Task 2), `DemoModel`
  (Task 1).
- Produces `class AssistantState(editor: EditorState, secrets: SecretStore, scope: CoroutineScope, liveModel: (String) -> AgentModel = { KoogModel.anthropic(it) })`
  with:
  - `var prompt: String`;
  - `val running: Boolean`;
  - `val progress: List<String>`: plain lines such as `Looking at the game`, `Staged: Add a rule to Coin` and
    `Checked the game`;
  - `val outcome: AgentOutcome?`;
  - `val notice: String?`: for cancellation, refusals and failures;
  - `val modelLabel: String`;
  - `val costText: String`: `No cost (no model is called)` for a non-live model, or `<in> in · <out> out tokens · about $<d.dd>`;
  - `val hasKey: Boolean`;
  - `fun saveKey(key: String)`;
  - `fun removeKey()`;
  - `var useLive: Boolean`: defaults to `hasKey`, and is false without a key;
  - `fun ask()`;
  - `fun answer(text: String)`;
  - `fun accept()`;
  - `fun reject()`;
  - `fun cancel()`;
  - `fun canAsk(): Boolean`: `!running && editor.play == null && prompt.isNotBlank()`.
- The key name is `anthropic-api-key`.
- The session is recreated when `useLive`, the key, `editor.sceneId` or the project identity changes; that discards
  any open proposal.
- `accept()` uses the label `Assistant: ` + the proposal summary, cut to 60 characters, and clears the outcome. A
  refusal sets `notice` to the refusal message.
- After cancelling, `notice` is `Cancelled. Nothing in your game changed.`
- A failed outcome sets `notice` to its message.

- [ ] Write the failing tests (scripted models via `ReplayModel(RecordedSession(...))` as in `AgentSessionTest`,
  and a suspending model for cancel):
  - `proposalChangesNothingUntilAcceptedAndUndoesInOneStep`;
  - `rejectAndCancelLeaveTheGameUnchanged`;
  - `questionShowsOptionsAndAnswerContinues`;
  - `askIsDisabledWhilePlayingOrBlank`;
  - `loadingAnotherProjectDropsTheProposal`;
  - `failingModelExplainsAndKeepsTheKey`;
  - `staleAcceptIsRefusedPlainly`;
  - `keyIsStoredOnlyInTheSecretStore`: after `saveKey`, `modelLabel`, `progress`, `notice` and the session
    transcript do not contain the key.
- [ ] Run (FAIL), implement, run (PASS).
- [ ] Commit: `feat(app): assistant state over staged agent sessions (C7c)`.

### Task 4: `AssistantPanel`, layouts, Ask AI and publish confirmation

**Files:**
- Create: `app/shared/src/dev/spawnkit/app/assistant/AssistantPanel.kt`, containing
  `@Composable fun AssistantPanel(assistant: AssistantState, modifier: Modifier = Modifier)`:
  - the model label (`assistant-model`), with a settings toggle (`assistant-settings`);
  - a key field (`assistant-key`, masked), Save (`assistant-key-save`, disabled when blank) and Remove
    (`assistant-key-remove`);
  - the where-stored text;
  - Live/Demo switch (`assistant-live`);
  - example prompts as chips (`assistant-example-<n>`) while in demo mode;
  - the prompt field (`assistant-prompt`) and Ask (`assistant-ask`);
  - progress lines and Cancel (`assistant-cancel`) while running;
  - the outcome: summary, step list, Accept (`assistant-accept`) and Reject (`assistant-reject`); answer text; a
    question with option buttons (`assistant-option-<n>`) and a reply field (`assistant-reply`);
  - the notice (`assistant-notice`) and the cost text (`assistant-cost`).
- Modify:
  - `EditorScreen.kt`:
    - `EditorScreen(..., secrets: SecretStore = rememberSecretStore(), liveModel: (String) -> AgentModel = { KoogModel.anthropic(it) })`
      creates `AssistantState` with `remember`.
    - Expanded: a third right-panel tab, `panel:Assistant`.
    - Compact: a fifth tab, `tab:AI`.
    - The right-panel tab state is lifted, so that Ask AI can switch tabs.
  - `RulesPanel.kt`, `Inspector.kt`: an optional `onAskAi: ((String) -> Unit)? = null` with an "Ask AI" button
    (`ask-ai`) when an object is selected. The prefilled prompts are:
    - Rules: `Help me with the rules of <name>.`
    - Properties: `Help me change <name>.`
  - `ReleaseManager.kt`: "Publish game" opens an `AlertDialog` (`publish-confirm`). Its text is
    `Publish "<title>" to <address>? Anyone with the play link can play it.`, with Publish (`publish-confirm-yes`)
    and Cancel (`publish-confirm-no`).
- Tests:
  - `app/shared/test@jvm/dev/spawnkit/app/AssistantUiTest.kt`, at phone 411×891 and desktop 1280×800, in the style
    of `BuilderUiTest`:
    - `phoneDemoBuildsCollectRuleAcceptsAndUndoes`;
    - `desktopRejectAndCancelKeepTheGame`;
    - `clarifyingQuestionIsAnsweredWithAnOption`;
    - `askAiFromRulesPrefillsThePrompt`;
    - `keyIsSavedMaskedAndNeverShown`;
    - screenshots `docs/media/assistant-compact.png` and `docs/media/assistant-expanded.png`.
  - `ReleaseUiTest`: the publish tests click `publish-confirm-yes`. Add `dismissingTheConfirmationDoesNotPublish`.

- [ ] Write the failing UI tests, run `./kotlin.bat test -m shared -p jvm` (FAIL), implement, run (PASS).
- [ ] Commit: `feat(app): assistant panel, Ask AI and publish confirmation (C7c)`.

### Task 5: Android evidence, docs and gates

**Files:**
- Create: `tools/android-assistant-smoke.py`, modelled on `tools/android-rules-smoke.py`. It adds Player, Coin and
  Score; opens AI; taps example 1; taps Ask; checks for "Add a rule to Coin"; Accept; Play holding Right; then saves
  screenshots `docs/media/android-assistant-*.png`.
- Modify:
  - `docs/ai-studio.md`: assistant UI, demo, keys and storage, platforms;
  - `docs/ARCHITECTURE.md`;
  - `PROGRESS.md`;
  - `AGENTS.md` (the `ai` row mentions the UI).
- [ ] Build and install the debug APK (`./kotlin.bat build -m androidApp`, then `adb install -r`), boot
  `Medium_Phone_API_36`, run the script. Expected: screenshots, and the script prints `OK`.
- [ ] Gates:
  - `./gradlew spotlessApply`;
  - then `./gradlew spotlessCheck allJvmAndWebTests localNativeTests`;
  - `./kotlin.bat check ktlint`;
  - `./kotlin.bat test -p jvm -p android`;
  - `-p mingwX64`.

  All green.
- [ ] Commit `docs: assistant UI evidence (C7c)`, open the PR (`Closes #69` only if every C7 acceptance box is met),
  and merge after CI with the owner's consent.
