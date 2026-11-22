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
| `finish` | toolbox | end with a summary in the creator's words |

There is no tool for publishing, exporting, store builds or deleting saved files. No tool takes or returns
credentials.

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
- `ReplayModel` replays a recording without keys or network, labelled
  `Replay: <model>, recorded <date> (no model is called)`, and shows no cost. A recording that calls a tool which no
  longer exists stops with an explanation. Every replayed tool call still runs against the real editor and engine.

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

## Not yet

- C7c: the assistant UI in compact and expanded layouts (review, accept and undo, reject, progress, cancel, cost,
  questions), "Ask AI" on the Rules panel and inspector, secure key storage, and a publish confirmation.
- Recordings of live model sessions: they need an owner-provided key, and none is used without the owner's
  consent. Until then, demos use the labelled scripted fixtures.
- Hand-written Koog workflows (Level Designer, Character Forge) that compose the derived tools, and agent-authored
  advanced rules.
