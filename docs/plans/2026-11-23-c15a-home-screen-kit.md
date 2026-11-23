# C15a Home Screen Kit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The first half of C15 ([#89](https://github.com/aioanei/spawnkit/issues/89)): creators build a real home
screen from several objects. Button and Text palette pieces, grouping objects ("Move into…"), and "+ Title screen"
layouts with Levels, Shop and How it works scenes. C15b adds images in projects (ADR-0012), the Image piece and the
pixelated option.

**Architecture:**
- **Grouping is a catalog operation.** `move_into` (in `core/gameplay`, `ObjectOperations`) moves an object into
  another one, or back to the top level, and keeps it where it is on screen: it applies `ReparentObject` and sets the
  object's position and scale relative to its new parent, as one undo step. The builder's "Move into…" control and
  dragging a row onto another in the scene list build the same operation, and the agent tool is derived (ADR-0009).
- **Palette pieces can be groups.** A palette piece may add child objects and rules in the same undo step. Button is a
  shape with a Play label inside it and "When tapped → Go to scene ⟨the next scene⟩" (no rule while the game has one
  scene). Text is a text object.
- **Title screen layouts.** "+ Title screen" asks for a layout: Play only; Play + Levels; Play + Shop + Levels + How it
  works. Each extra button goes to a scene that is made in the same step: Levels has a button for every scene of the
  game, and Shop and How it works hold a heading, a text to replace and a Back button. Everything is ordinary objects
  and rules made with catalog operations in one `Batch`.

**Spec:** [design §12](../design/2026-11-22-title-screens-presets-accounts-ads.md), issue #89.

## Tasks

### Task 1: `move_into`
- `ObjectOperations.moveInto(project, scene, object, parent)` and its catalog entry in `StandardEngine.operations`.
- Tests: the object keeps its world position and size inside a moved, scaled parent; back to the top level; moving into
  itself or its contents and moving where it already is are refused; one undo restores it.

### Task 2: Palette pieces and title layouts
- `ObjectPreset` children and rules; Button and Text pieces; `EditorState.addTitleScreen(layout)`.
- Tests: each layout's scenes, buttons and rules; playing the full layout from Title to Levels to a level and back.

### Task 3: Builder controls, parity and evidence
- "Move into…" in the inspector; long-press dragging in the scene list; the layout choice behind "+ Title screen".
- `OperationParityTest` case for `move_into`; `HomeScreenKitUiTest` in both layouts; Android screenshot of a home
  screen; docs and `PROGRESS.md`.
