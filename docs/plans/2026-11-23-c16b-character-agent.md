# C16b Character Forge agent tool Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The second half of C16 ([#90](https://github.com/aioanei/spawnkit/issues/90), [ADR-0013](../adr/0013-character-forge-image-model.md)):
a photo attached in the assistant chat with "make this person a character in game" works through an agent tool. The
upload costs money and leaves the device, so the chat asks the creator first (ADR-0009). Builder and agent make the
same Character.

**Design.**
- The model never sees the photo. The creator attaches it in the assistant panel; the prompt only says that a photo is
  attached. `make_character` (styles, which style to use, optional position) is a toolbox tool, so it is derived for
  the hosted model and the server's copy of the tool list alike.
- `make_character` does not upload. The session pauses with `AgentOutcome.Confirmation`, like a clarifying question.
  The panel shows what will be sent and where, with the same consent checkbox as the builder's dialog. Only
  `confirm(consent = true)` calls the injected `CharacterMaker` (the account's forge, which strips metadata); `decline()`
  tells the model that nothing was sent. Either way the session continues.
- The images and the Character are staged as one `Batch` built by `CharacterRecipe`, which the builder's
  `addCharacterImages` also uses. The creator still accepts or rejects the proposal; rejecting leaves the game
  unchanged (the images were already paid for, and the panel says so).

## Tasks

### Task 1: One recipe for both paths
- `StagedChanges.nextId` (the ID floor staged operations mint from). `CharacterRecipe.batch` in `ai`; the builder's
  `addCharacterImages` uses it.

### Task 2: The tool and the confirmation
- `CharacterMaker`, `make_character`, `ToolOutcome.Confirm`, `AgentOutcome.Confirmation`, `AgentSession.confirm` and
  `decline`, and the instructions.
- Tests with a fake maker: nothing is sent before confirmation; consent is required; declining sends nothing;
  without a photo the tool is refused; refusals and failures are reported and stage nothing; the photo never appears
  in model requests; accepting is one undo step.

### Task 3: The scripted demo
- A fourth example, "Make this person a character in the game.", that uses the tool when a photo is attached.

### Task 4: The assistant panel
- Attach a photo, the confirmation card (consent, Send photo, Don't send), progress lines; `EditorScreen` wiring.
- `AssistantStateTest` cases; `CharacterAgentUiTest` on phone and desktop; a parity test that the dialog and the
  agent tool give the same project.

### Task 5: Evidence and docs
- Android emulator script from photo to playable character; AI Studio docs, ADR-0013, `PROGRESS.md`; gates; PR.
