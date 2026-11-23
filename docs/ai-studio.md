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
| `look_at_game` | toolbox | scene ID; objects with their IDs, component values and rules as blocks, in the form the rule tools take |
| `describe_components` | toolbox | installed components, properties, kinds, limits and defaults |
| `check_game` | toolbox | rule and capability problems found by the real engine, exactly as Play reports them |
| `trace_rules` | toolbox | headless play with held inputs, reporting which rules ran, when, and which conditions were never true |
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

The builder shows the assistant as an **Assistant** tab in the expanded right panel and an **AI** tab on phones.
`AssistantState` (`app/shared`) drives one `AgentSession` per model, scene and project:

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

- A visual preview of a proposal in the viewport before accepting; the review lists the steps in words.
- Secure key storage on iOS (Keychain) and the web.
- Committed recordings of live sessions. The recording workflow exists, but no run has been reviewed and committed
  yet; until then, demos use the labelled scripted fixtures.
- Hand-written Koog workflows (Level Designer, Character Forge) that compose the derived tools, and agent-authored
  advanced rules.
