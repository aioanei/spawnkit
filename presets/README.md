# Preset games

Each folder holds one preset game offered in **Project → New game** (C10). `project.json` is a normal SpawnKit
project file, the same format the builder saves, embedded into the app by `core/presets` as `EmbeddedPresets`.

| Folder | Game |
|---|---|
| `coin-dash` | Coin Dash: move and collect every coin |
| `tap-targets` | Tap Targets: tap the moving targets before time runs out |
| `maze-run` | Maze Run: reach the exit without touching a wall |
| `aura-farmer` | Aura Farmer: tap the Aura Toaster fast enough to farm 50 aura in 15 seconds |
| `npc-dodge` | NPC Dodge: survive 20 seconds while NPCs ricochet around |
| `star-defender` | Star Defender (complete game, C17): shoot spawned invaders across three waves; buy lives in the shop |
| `toaster-tycoon` | Toaster Tycoon (complete game, C17): tap, buy upgrades, unlock the Bakery, reach 5000 toast in 3 minutes |

Every preset's title screen has a How it works button. It opens a scene that explains the rules and suggests one
change to try. `GamePresets` lists the rule blocks each preset uses, and a test checks they are really used.

The files were made with the builder's own calls (object presets, catalog operations, rule operations and "Add
title screen") in `app/shared/test@jvm/dev/spawnkit/app/presets/PresetRecipes.kt`. The recipe test checks that every
file still matches its recipe. To rewrite the files after changing a recipe, run the test with
`SPAWNKIT_WRITE_PRESETS=1`:

```sh
SPAWNKIT_WRITE_PRESETS=1 ./gradlew :app:shared:jvmTest --tests "*PresetRecipesTest*"
```

A preset can also be opened in the builder, changed and saved over its file; then update its recipe the same way so
the check passes again. Older files keep loading through the project format's migrations.
