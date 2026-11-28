# AI Studio core — C7b (R7 subset)

Tracked by [issue #69](https://github.com/aioanei/spawnkit/issues/69). This builds on the
[operation catalog](operation-catalog.md) (C7a) and follows [ADR-0005](adr/0005-ai-writes-via-typed-commands.md) and
[ADR-0009](adr/0009-agentic-parity.md). The `ai` module runs assistant sessions over the builder's own operations.
It has no screens; the assistant UI comes in C7c.

## Staged changes (`core/editing`)

`StagedChanges(editor)` holds a proposal apart from the creator's workspace:

- Operations are validated exactly as in `Editor`, but the workspace, its history and its revision stay untouched.
- Staging starts from `Editor.saveSnapshot()`, so object IDs minted while staging match the IDs the workspace mints
  on accept, even after undo retired some IDs.
- `accept(label)` commits every staged step as one `Batch`, which is one undo step. It is refused, with nothing
  changed, when the workspace revision moved since staging began, or when the result would differ from the preview.
- `discard()` drops the proposal. `undoLast()` removes the last staged step. A proposal has at most 500 steps.

## Agent toolbox

`AgentToolbox` gives a model these tools, all acting on one `StagedChanges`:

| Tools | Source | Effect |
|---|---|---|
| `rename_project`, `add_object`, … `add_rule`, `change_block`, … | derived from `StandardEngine.operations` | stage one builder operation |
| `look_at_game` | toolbox | every scene in play order (the first is where play starts), the images and the ad settings; inside one scene (`scene`, default the open one), objects with their IDs, component values and rules as blocks, in the form the rule tools take |
| `look_at_example` | toolbox | the preset games as read-only worked examples: the list, one game's scenes, or one scene in `look_at_game`'s form (C13a) |
| `describe_components` | toolbox | installed components, properties, kinds, limits and defaults |
| `check_game` | toolbox | rule and capability problems found by the real engine, exactly as Play reports them, for one scene or `all` |
| `trace_rules` | toolbox | headless play of a scene with held inputs, reporting which rules ran, when, and which conditions were never true |
| `ask_creator` | toolbox | pause the session with a clarifying question and suggested answers |
| `make_character` | toolbox | ask to turn the photo attached in the chat into characters; the session waits for the creator's consent before anything is sent (C16b) |
| `finish` | toolbox | end with a summary in the creator's words |

There is no tool for publishing, exporting, store builds or deleting saved files. No tool takes or returns
credentials. `make_character` is the only tool that sends something off the device, and it cannot do so by itself: see
[Character Forge from the chat](#character-forge-from-the-chat-c16b).

## Rule traces

`RuleTrace` explains rules from real play. It copies the scene and gives every block rule of the traced objects, and
each of its conditions, an extra probe rule built from standard blocks. A probe counts into a hidden tagged number
object, which has no placement or look, so nothing can touch, see or count it. The copy runs headlessly in the real
engine with scripted input, one tick at a time. The report states how often each rule ran and when it first did, which
conditions were never true, which objects' rules had problems or stopped, and which advanced rules are not traced. For
example: `Rule 1 of Coin (When touching players) never ran: "Touching players" was never true.`

## Sessions

`AgentSession(engine, editor, sceneId, model)` runs one conversation:

- `ask(prompt)` starts a new proposal, discarding any open one. The session loop asks the model, runs its tool
  calls, and returns their results until the model finishes.
- Outcomes:
  - `Proposal`: summary, review labels and the staged changes;
  - `Answer`: an explanation, with nothing staged;
  - `Question`: a clarifying question; reply with `answer(text)` to continue the same proposal;
  - `Failed`: the session ended and its staged changes were discarded.
- `accept(label)` commits the proposal as one undoable step; `reject()` discards it.
- Progress events (`Thinking`, `UsedTool`), the running `Cost` (tokens, plus dollars for live models with known
  prices) and the `transcript` are available for the UI.
- Cancelling the coroutine stops the session and discards the proposal. A failing model call ends the session with
  `Failed`, and the game is unchanged.

## Models, recording and replay

- `AgentModel` is the small interface sessions use. `ModelInfo` carries the name, provider, `live` flag, label and
  prices.
- `KoogModel` is the live adapter on Koog 1.3.0. It converts the conversation to a Koog `Prompt` and each derived
  JSON-Schema tool to a Koog `ToolDescriptor`: a block `oneOf` becomes `AnyOf` named by block IDs.
  `KoogModel.anthropic(apiKey)` uses `claude-sonnet-5-5` by default. The key stays inside the Koog HTTP client and is
  never part of the model info, requests, replies or recordings.
- `RecordingModel` wraps a live model and records its replies. `RecordedSession` (format `spawnkit-agent-recording`,
  version 1) stores the model, provider, date, a note, the creator's script and the replies. It contains no keys.
- **Choices in tool schemas.** Koog 1.3.0's Anthropic client refuses `AnyOf` parameters
  ([upstream report](upstream/koog-anthropic-anyof.md)), so `KoogModel` sends choices in a flat form and maps the
  model's input back before the session sees it:
  - a choice of objects, such as a rule block or a target, becomes one object whose discriminator (`block`, `kind`)
    is an enum of every choice, with what each is for and which settings it uses;
  - a choice of plain values (`set_property`'s value) becomes `{number | whole_number | text | yes_no}`.

  `KoogAnthropicWireTest` runs the real Anthropic client against a local stand-in for the API, with no key or network.
  A one-off live check on 2026-11-23 built a rule and set a width with the full toolbox, for about $0.20.
- `ReplayModel` replays a recording without keys or network, labelled
  `Replay: <model>, recorded <date> (no model is called)`, and shows no cost. A recording that calls a tool which no
  longer exists stops with an explanation. Every replayed tool call still runs against the real editor and engine.
- `DemoModel` is a keyless **scripted demo** (C7c), always labelled
  `Scripted demo: a fixed script stands in for the AI model (no model is called)`. It handles only its three
  example prompts:
  - collecting coins adds to the score;
  - moving with the arrow keys;
  - "Why doesn't my coin rule work?".

  It reads the real game with `look_at_game` and finds objects by tag. It then stages rules through the same tools,
  checks them, or answers from a real rule trace. Any other prompt gets an explanation and changes nothing. It never
  calls a model or the network. It is not a recording of a model.

### Recording live sessions

Live sessions are recorded on GitHub, so the key never has to be on a developer machine:

- **The key.** It is the `ANTHROPIC_API_KEY` repository secret. Only the `record-ai-session` workflow reads it; tests
  and the builder never do.
- **Starting a run.** The owner starts the workflow by hand from the Actions tab. It never runs on pull requests or
  pushes. With an empty form it records the demo set:
  - a collect game built from a blank game with one prompt;
  - a bouncing enemy added to Coin Dash;
  - two follow-up edits to Tap Targets.

  A custom prompt can be recorded instead, on a blank game or a preset.
- **Playing the creator.** `SessionRecorder` (`ai`) sends the prompts, answers each clarifying question with its first
  option and accepts every proposal. Its replay drives a `ReplayModel` through the same steps, and tests check that
  it rebuilds the same game. Tests use the scripted demo and fakes only.
- **The command.** `tools/agentRecorder` (`./kotlin run -m agentRecorder -- <out-dir>`) wraps the live model in
  `CappedModel`, which stops calling once the run's dollar budget is spent. The default is $3, and at most $20. Each
  prompt also has at most 20 model calls.
- **Output.** For each scenario it writes `<name>.json`, the replayable session, and `<name>.spawnkit`, the game after
  accepting every proposal. It also prints a summary of every outcome and the cost.
- **Review first.** The files are uploaded as the run's artifacts. They are reviewed before any is committed as a
  replay, and replays always show their model and date.

## Assistant UI (C7c)

The builder shows the assistant as an **AI** tab, first in the expanded right panel and on phones.
`AssistantState` (`app/shared`) drives one `AgentSession` per model, scene and project. Since C13b, changes are applied
right away with Revert and Review is an option; see [AI-first builder](#ai-first-builder-c13b).

- **Asking.** The panel has a prompt field, and example prompts while the scripted demo answers. Ask is disabled
  while the game plays or the prompt is blank.
- **While it works.** Progress shows in plain words: "Looked at the game", "Staged: Add a rule to Coin", "Checked the
  game". **Cancel** stops the session, and the game is unchanged.
- **Review.**
  - A proposal shows the summary and its steps, and says that nothing has changed yet.
  - **Accept** applies it as one undo step. **Reject** discards it.
  - Accepting after hand edits is refused with the reason. Loading another project, or switching scene, drops the
    proposal with a notice.
- **Questions and answers.** Clarifying questions show their options as buttons, plus a reply field. Answers and
  failures are shown in plain words.
- **Labels.** The model label (live, or the scripted demo) and the cost are always visible. The demo and replays
  show "No cost (no model is called)".
- **Ask AI.** "Ask AI" on Rules and Properties opens the assistant with a prompt about the selected object.
- **Publishing.** Publishing to the Arcade now asks for confirmation (ADR-0009). Agents still have no publish tool.

**Keys.** Settings take an Anthropic API key in a masked field. The key is stored only in the platform `SecretStore`
and handed only to the live model client. It never appears in the UI after saving, nor in projects, exports, logs,
transcripts or tool inputs.

| Platform | Where the key is kept |
|---|---|
| Android | Android Keystore (AES-GCM key that never leaves the Keystore; encrypted value in private preferences) |
| Windows | DPAPI, encrypted for the signed-in user (`%APPDATA%\SpawnKit\secrets`) |
| macOS | login keychain through `security` (the key is briefly visible in that tool's arguments to the same user) |
| Linux | Secret Service through `secret-tool`, when installed; otherwise memory only |
| iOS, web | memory only, until SpawnKit closes; the assistant says so |

Without a key, the labelled scripted demo answers its three example prompts.

## Hosted AI and accounts in the builder (C11b)

Creators no longer need a key ([ADR-0011](adr/0011-hosted-ai-model-proxy.md)). **Project → Account…** signs in to a
SpawnKit server (see [Arcade hosting](arcade-hosting.md)): enter its address, then sign in, create an account with the
emailed 6-digit code, or reset a forgotten password. Signed in, the screen shows the account with Sign out and Delete
account (which asks first).

- **Which model answers.** The creator's own key, when switched on under **Developer settings**; otherwise **SpawnKit
  AI** (`HostedModel`) when signed in to a server that offers it; otherwise the labelled demo, with a hint to sign in.
  The label always names the model that answers.
- **Limits.** The server's refusals, such as a reached daily allowance, show as plain notices, and nothing in the game
  changes.
- **A new account or server starts a new conversation.**
- **The session token** is kept in the same platform `SecretStore` as keys (`spawnkit-session`, with the server under
  `spawnkit-server`). It is never shown or logged, and never goes into projects, exports or the assistant. A token the
  server no longer accepts signs the builder out with "Your session ended. Sign in again."
- `AccountClient` (`ai`) is the client for the C11a routes; `AccountState` (`app/shared`) holds the builder's account.

## Game Architect (C13a)

[Plan](plans/2026-11-24-c13a-game-architect.md), [#85](https://github.com/aioanei/spawnkit/issues/85). The assistant can
plan and build a **complete** game from one prompt with the builder's own operations.

- **The whole game in view.** `look_at_game` lists every scene and looks inside any of them. `check_game` checks one
  scene or `all`. `trace_rules` plays any scene.
- **Worked examples.** `look_at_example` shows the preset games, including the complete ones (C17), read-only and in
  the same form as `look_at_game`, so the agent copies patterns rather than guessing them.
- **Instructions.** For a whole game the agent:
  1. studies an example and plans the scenes, mechanics, kept values, shop and ad spots;
  2. makes the first scene a title screen with a Play button;
  3. builds levels with a goal and a way to lose, and win and lose scenes with a way back;
  4. uses kept numbers, Test purchase blocks and `set_ads`;
  5. checks every scene, traces the main rules and summarizes how to change the game by hand.
- **Budget.** A request may take 60 model calls (30 before); the recorder uses the same limit, bounded by its dollar
  budget.
- **Prompt caching.** `KoogModel` asks Anthropic to cache the instructions, tools and conversation
  (`AnthropicParams.cacheControl`). `Usage` counts cache writes and reads from Koog's metadata. `Cost`, the server's
  ledger, recordings and the request JSON price writes at 1.25 × and reads at 0.1 × the input price.
- **Tests.**
  - `GameArchitectTest`: a scripted fixture builds a title screen, a level and a win scene, reading created IDs as a
    model would. Every scene checks clean, the trace shows the level's goal rule firing, and one undo removes it all.
  - `AgentToolboxScenesTest` +2, `ExamplesToolTest` 3.
  - `KoogAnthropicWireTest`: `cache_control` is sent and cache tokens are counted.
  - `HostedAiTest`: cache tokens are charged and returned.
- **Empty replies.** A reply with no text and no tool calls is usually one cut off at the output cap. It is never taken
  as "done": the session asks the model once to go on with fewer calls per reply, then fails plainly. Replies may now
  use 8,192 output tokens (Koog's default of 2,048 cut a first whole-game attempt off). The instructions ask for at
  most 15 tool calls per reply.
- **Live recording (2026-11-23).** One prompt on a blank game, through `tools/agentRecorder` with the owner's key and a
  $3 budget: "Make a complete jungle platformer … title screen with Play, two levels, bananas kept between levels, a
  shop that sells an extra life as a test purchase, test ads between levels, and win and lose screens."
  - `claude-sonnet-5-5` built **Jungle Banana Run** in 30 model calls and 142 steps. It has six scenes: Title, Level 1,
    Level 2, Shop, Jungle Champion (win) and Game Over (lose).
  - Platforms, bananas, snakes and a flag; kept bananas and lives; an Extra life test purchase; test banner and
    between-scene ads.
  - Every scene passed `check_game`. The summary said Level 2's flag was not reached in a simulated play.
  - Cost: about $1.51 (47,755 output tokens; 1,671,672 input tokens read from the cache).
  - Committed as a labelled replay, `ai/recordings/jungle-banana-run.json`, with the game it made. `WholeGameReplayTest`
    replays it through today's tools to exactly that game. `AiBuiltGameTest` plays the game:
    - Play opens Level 1 with 3 lives;
    - running into snakes ends on Game Over, and Retry restores the lives;
    - the shop's Extra life is a test purchase that adds a life.
  - **Cost against the limits.** A whole game costs more than the default per-account allowance of $1 a day, so with
    SpawnKit AI it would stop partway. The owner decides the limit (`SPAWNKIT_AI_ACCOUNT_DAILY_DOLLARS` on the server, default $1).

## AI-first builder (C13b)

[Plan](plans/2026-11-28-c13b-ai-first-builder.md), [#85](https://github.com/aioanei/spawnkit/issues/85). The builder
now starts from the assistant. Manual tools are unchanged.

- **The chat is the main panel.**
  - It is the first tab on phones (**AI**, opening first and taller than the other tabs).
  - On wide screens it is the first tab of the side panel beside the canvas. Selecting an object opens Properties.
  - The conversation (`AssistantState.chat`) keeps the creator's requests and answers, the assistant's answers and
    questions, and notes. It scrolls above a composer with the prompt, **Send** and **Attach photo**.
  - Another scene keeps the conversation and starts the model afresh, with a note saying so. Another project clears it.
- **Apply right away, with Revert.**
  - A proposal is applied as one undo step as soon as it arrives. It shows as an **Applied** card with the summary,
    "Show N changes" and **Revert**.
  - Revert is the editor's own undo. It works while the assistant's step is the latest one. After a manual change, or
    an Undo, the card says to use Undo (↶), so Revert never removes the creator's work. Redo brings the step back, and
    it can be reverted again.
  - The model is told about a revert (`AgentSession.reverted()`).
  - **The canvas shows the work as it grows.** While the assistant works, the viewport draws its staged game
    (`EditorState.assistantPreview`) under a "Preview of the assistant's changes" label. Preview objects can be panned
    and zoomed but not selected; the game itself changes only when the step is applied. With Review first, the
    preview stays until Accept or Reject; cancelling or a failure clears it.
  - If the creator edits while the assistant works, the late changes are refused with the reason. The creator's edit
    stays.
  - **Review changes first**, in the assistant's Settings, brings back Accept and Reject. Settings also hold the
    developer key, which stays out of the normal flow.
- **Prompt-first New game.**
  - Project → New game starts with **Describe your game…** above the presets.
  - **Build it** starts a new blank game like New (unsaved changes are asked about first), opens the assistant and
    sends the description (`ProjectSession.describe`, `BuildRequest`).
  - Signed out, the box explains that building with AI needs a free account, with **Sign in** and **Watch the AI build
    a game**.
- **The labelled replay.**
  - `WholeGameReplay` (`ai`) embeds the reviewed Jungle Banana Run recording (C13a). `tools/embed-recording.py`
    regenerates `JungleBananaRunJson` after the recording changes, and `WholeGameReplayTest` fails while the two differ.
  - `AssistantState.watchReplay` plays it on the new blank game, paced so the steps can be followed. While it runs, the
    model label reads "Replay: claude-sonnet-5-5, recorded 2026-11-23 (no model is called)".
  - The chat starts with a note that it is a replay, and the Applied card says "from the replay (no model was called)".
  - The cost line reads "No cost (no model is called)". The six scenes arrive as one step with Revert.
  - `Editor.load` now mints IDs from the loaded project alone, so a new blank game has the IDs the recording expects.
- **Tests.**
  - `AssistantChatTest` 13: the canvas preview while the assistant works, kept for review and cleared by cancel; apply right away; Revert; Revert refused after a manual change; Undo and Redo; Review first;
    answers in the chat; the model told of a revert; editing while the assistant works; the chat across scenes and
    projects; the replay builds six scenes and stays labelled.
  - `AiFirstUiTest` 5: Describe → Build it → applied → Revert → Redo on a phone and a desktop; signed out with the
    labelled replay on a phone; unsaved changes and cancel; the replay request.
  - `WholeGameReplayTest` +2: the embedded copy equals the file; the builder's replay builds the whole game.
  - `EditorTest` +1: IDs after `load`.
  - Changed: `AssistantStateTest` covers the Review first option. The UI tests open the Add tab on phones, and
    `AssistantUiTest` and `CharacterAgentUiTest` expect changes applied right away.
- **Android emulator (API 36, 2026-11-28).** `tools/android-ai-first-smoke.py`, with real taps and signed out:
  - New game shows the Describe box with Sign in;
  - Watch the AI build a game streams the labelled replay, the canvas previews the game as it grows, and Jungle
    Banana Run is applied (title screen, 142 changes);
  - Revert brings back the blank game.

  Screenshots: [New game](media/android-ai-first-new-game.png), [replay running](media/android-ai-first-replay-running.png),
  [canvas preview while it builds](media/android-ai-first-replay-preview.png),
  [applied](media/android-ai-first-replay-applied.png), [reverted](media/android-ai-first-reverted.png),
  [desktop New game](media/new-game-describe-desktop.png), [desktop built](media/ai-first-built-desktop.png),
  [phone built](media/ai-first-built-phone.png).

## Character Forge (C16a)

[ADR-0013](adr/0013-character-forge-image-model.md), [plan](plans/2026-11-23-c16a-character-forge.md),
[#90](https://github.com/aioanei/spawnkit/issues/90). A photo becomes game characters.

- **Builder.** When the signed-in server offers it, the Add panel shows **Character from photo…**:
  1. choose a photo;
  2. tick "This is me, or this person agreed" (Make characters stays disabled until then);
  3. choose Pixel art, Cartoon and Chibi;
  4. make the characters.

  `AccountState.forge` strips the photo's metadata chunks (`ProjectImages.stripMetadata`) before it leaves the device.
  **Use** on one result adds every image, with its provenance, and a **Character** in one undo step. The Character
  is a sprite (pixelated for pixel art), a body, the `player` tag, Left and Right walking, and Jump.
- **Server.** `POST /api/ai/character` requires sign-in and consent, and accepts a PNG photo of at most 1 MB and one to
  three styles.
  - Each style is one OpenAI image edit (`gpt-image-1-mini` by default, set with `SPAWNKIT_IMAGE_MODEL`; low quality, transparent background), reserved at $0.05
    against the same spend ledger as text AI. Failures and refusals cost nothing.
  - Pixel art is post-processed deterministically: a 32 × 32 box downscale, a fixed 16-colour palette, hard
    transparency and an 8 × upscale. Other styles are scaled to fit 512 px.
  - The photo is never stored or logged; only a failure's type is logged.
- **Tests.**
  - `CharacterForgeTest` 7, with a fake image gateway: all styles with provenance and spend, consent and sign-in,
    refusals and failures cost nothing, the shared daily limit, "not set up", deterministic pixel art, and the
    builder's `AccountClient` reading the route.
  - `CharacterForgeUiTest` on phone and desktop: consent gating, metadata stripped, images and Character added, and
    one undo.
  - `ImagesTest`: provenance and metadata stripping.
- **Live run (2026-11-23).**
  - A local server with the owner's key (`tools/run-server-local.ps1`) made all three styles from an original
    illustrated portrait in 33 seconds through `/api/ai/character`, each with the provenance "OpenAI gpt-image-1-mini,
    2026-11-23". [Results](media/character-forge-live.png).
  - `gpt-image-1` turned out to be retired, so the model is now set with `SPAWNKIT_IMAGE_MODEL`, by default
    `gpt-image-1-mini`.
  - The pixel palette gained skin tones after faces came out pink.

### Character Forge from the chat (C16b)

[Plan](plans/2026-11-23-c16b-character-agent.md). The same capability as an agent tool (ADR-0009): a photo attached in
the chat with "make this person a character in the game" works with SpawnKit AI, a creator's own key or the scripted
demo.

- **Attach photo** under the assistant's prompt attaches a picture. The model is never given the photo: the prompt only
  says that one is attached (`AgentSession.PHOTO_ATTACHED`).
- **`make_character`** takes one to three styles, which style the Character shows, and where it stands. It uploads
  nothing. The session pauses with `AgentOutcome.Confirmation`, like a clarifying question.
- **The confirmation card** says that the photo leaves the device for the image model only, is not saved, and uses the
  allowance. **Send photo** stays disabled until "This is me, or this person agreed" is ticked. **Don't send** tells the
  agent that nothing was sent, and the session goes on.
- **Sending** goes through `CharacterMaker` (`ai`), which the builder implements with the account's forge, so metadata
  is stripped on the device as in the dialog. The photo is let go once sent. Refusals and failures are reported to the
  agent and stage nothing.
- **The result** is staged with `CharacterRecipe`, the same batch the builder's dialog applies: every image with its
  provenance and the Character, as one proposal step ("Add character") the creator accepts or rejects. The tool returns
  the new IDs, so the agent can go on arranging the Character.
- **Cost line.** After images are made, the assistant's cost line counts them against the SpawnKit AI allowance, also
  under the scripted demo, whose text is free but whose pictures are real.
- **The scripted demo** has a fourth example, "Make this person a character in the game.", which asks for pixel art and
  cartoon.
- **Without a photo, or without a server that offers Character Forge**, the tool is refused with what to do. Recordings
  never send photos: the recorder has no maker.
- **Tests.**
  - `CharacterToolTest` 7, with scripted fixtures and a fake maker:
    - nothing is sent before confirmation, and accepting is one undo step;
    - the agent makes what the builder's recipe makes;
    - consent is required, and declining sends nothing;
    - without a photo or a server, the tool is refused;
    - bad choices are refused before asking;
    - refusals and failures stage nothing;
    - a refused style falls back to one that was made.
  - `DemoModelTest` +2.
  - `CharacterAgentUiTest` 3, on phone and desktop with a fake server:
    - consent gating and metadata stripping;
    - the photo is let go once sent;
    - the cost line counts the images;
    - parity with the builder's dialog, and one undo;
    - declined photos are never sent.
- **Android emulator (API 36, 2026-11-23).** `tools/android-character-smoke.py` ran from photo to playable character
  with real taps against a local server.
  - The photo was an original illustrated portrait drawn by the script.
  - Steps: sign up with the emailed code; add a Platform to a blank game; attach the portrait in the AI tab; ask the
    scripted demo's character example; tick consent; send; accept.
  - The server's image model was live and made pixel art and cartoon characters. SpawnKit AI was off, so the labelled
    demo chose the tool calls.
  - In play, the Character stood on the platform, walked right and jumped.
  - Screenshots: [confirmation](media/android-character-confirm.png), [review](media/android-character-review.png),
    [standing](media/android-character-play.png), [jumping](media/android-character-jump.png).
- **Fixed on the way.** The builder's HTTP client used the engines' default timeouts (about 10 seconds on Android), so
  every Character Forge request failed with "Unable to reach the SpawnKit server", in the C16a dialog too. It now waits
  up to four minutes for server work.
- **Found, then fixed.** Koog 1.3.0's Anthropic client refuses tool parameters of type `AnyOf` before sending ("AnyOf
  type is not supported"), so every live Anthropic request with the full toolbox failed. See
  [Models, recording and replay](#models-recording-and-replay).

## Evidence

- `StagedChangesTest` (`core/editing`, 3 tests): the workspace is untouched until accept; accept is one undo step;
  stale proposals are refused; discard is harmless; staged IDs match after undo retired IDs.
- `AgentSessionTest` (`ai`, 4 tests), using hand-written fixtures labelled "Scripted fixture … not a recording of a
  model":
  - Score + collect is built from two prompts alone. The result equals the rules built with the builder's
    `RuleOperations` and plays to "Score: 1". The trace and check tool results are real.
  - "Why doesn't my coin rule fire?" is explained from a trace (`"Touching players" was never true`). Then "fix it and
    make coins worth 5" changes two blocks and plays to 5.
  - A clarifying question ("Which score: Score or High score?") is answered and continues the same proposal. Every
    tool call gets exactly one reply.
  - Cancellation leaves the game unchanged; refused calls are explained to the model; a proposal for an older
    revision is refused; a stale recording fails safely; recordings round-trip and foreign files are refused.
- `KoogModelTest` (`ai`, 2 tests): with a fake Koog executor (no network and no key), every derived tool converts
  to a Koog descriptor, messages and tool results convert, and replies come back as tool calls with usage. The
  Anthropic factory requires a key and never exposes it.
- `ai` passes on JVM, Android host, JS and Wasm (Node). CI covers iOS.
- C7c:
  - `DemoModelTest` (5 tests): the demo builds the collect rule on every coin and plays to "Score: 1". It adds four
    arrow-key rules, answers "why" from a real trace, explains missing objects and unknown prompts without changes,
    and is labelled as a script.
  - `AssistantStateTest` (9 tests): accept is one undo step. Reject, cancel and failures change nothing. Questions
    continue the proposal. Ask is disabled while playing. Loading a project drops a proposal or question. A stale
    accept is refused. The key is never shown.
  - `DesktopSecretStoreTest` (4 tests): DPAPI round trip with no plain text on disk, delete, safe names, and memory
    storage that says it is temporary.
  - `AssistantUiTest` (5 tests, phone and desktop): demo, accept and undo; reject and cancel; a question answered by
    an option; Ask AI prefills the prompt; the key is masked and removable.
  - `ReleaseUiTest` covers the publish confirmation, including dismissing it.
  - Android emulator (API 36), `tools/android-assistant-smoke.py`, with real taps and no key:
    - add Player, Coin and Score;
    - ask the demo for the collect rule, review it and accept;
    - ask for arrow keys and accept;
    - play to "Score: 1".

  Screenshots: [phone review](media/android-assistant-review.png), [play](media/android-assistant-play.png),
  [compact](media/assistant-compact.png), [expanded](media/assistant-expanded.png).

## Not yet

- Secure key storage on iOS (Keychain) and the web.
- Committed recordings of live sessions. The recording workflow exists, but no run has been reviewed and committed
  yet; until then, demos use the labelled scripted fixtures.
- Hand-written Koog workflows (Level Designer, Character Forge) that compose the derived tools, and agent-authored
  advanced rules.
