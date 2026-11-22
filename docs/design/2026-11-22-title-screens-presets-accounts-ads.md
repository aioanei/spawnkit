# Title screens, preset games, accounts and test ads (C9–C12)

Date: 2026-11-22. Status: **accepted direction**, owner decisions recorded below. Plan: contest track C9–C12 in the
[engine foundation revision](../plans/2026-11-20-engine-foundation-revision.md#contest-delivery-track). Accounts
decision: [ADR-0010](../adr/0010-accounts-on-ktor.md).

## 1. Intent

The owner keeps four product ideas and asked for them to be planned:
1. a home screen for games that the creator can configure;
2. user accounts with sign-in and a database;
3. preset games made in the app;
4. ads with an earnings analysis.

They make SpawnKit games feel finished (a title screen), get new creators going quickly (presets), and connect
creators, players and released games (accounts, test ads and their statistics). All four come before the 7 Dec 2026
contest deadline. They run after C7c and before C8.

## 2. Owner decisions (2026-11-22)

| Topic | Decision |
|---|---|
| Home screen | A **title screen inside each game**, which players see first and the creator configures in the builder. It is optional per game. |
| Timing | **All four before the contest deadline.** |
| Accounts | A signed-in creator **owns their Arcade games**, **syncs projects** and **sees their ad statistics**. **Players** can sign in too. |
| Sign-in | **Email and password, Google and Apple.** GitHub is not needed; more providers can follow. |
| Hosting | The owner provides a **public HTTPS host**. There is **no Apple Developer membership** yet, so Apple is designed but not built before the contest. |
| Ads | **Test ads with real counts.** Ads are labelled TEST, views and clicks are really counted, and revenue is shown as 0. |
| Cut line | **Cut depth inside these features first.** C8 (demos, README, essay) keeps 5–7 Dec. |
| Templates (later on 2026-11-22) | Brain-rot style presets with original characters and "How it works" guidance, "for showing judges what this app is capable of and also showing users and guiding them" (C10b, #84). |
| AI-first (later on 2026-11-22) | SpawnKit becomes **AI-first, like Lovable**: the AI builds the game with the builder's own tools, and creators can still change everything by hand (C13, [ADR-0011](../adr/0011-hosted-ai-model-proxy.md)). |
| Paying for AI | **No bring-your-own-key** after launch; **subscriptions** come later. **Everything is free for now**, with a **$20 monthly cap** on AI spending. |
| AI changes | **Apply right away**, each as one undo step with a **Revert** button, instead of Accept/Reject. |
| Scope | **Nothing is trimmed** to make room: "we will be done until then". |
| Complete games (2026-11-23) | The chat should let "even an inexperienced person" get **a fully functional game**: a home screen, levels, places for ads, in-game purchases and the business side. The owner is sure the schedule holds. |
| Purchases before the contest | **Test mode**, like the ads: no money is charged, purchases are really counted. Real payments need Google Play and App Store developer accounts and store builds, which are not available before 7 Dec, so they come after launch with the subscriptions. This is my call, stated to the owner. |

## 3. Goals, non-goals and anti-goals

**Goals.**
- A creator adds a title screen in one action and changes it with the normal inspector and rules.
- A new creator starts from a playable preset.
- Creators sign in with email or Google, own and manage their Arcade games, and open their projects on another
  device.
- Players sign in on the Arcade, like games and see the games they played.
- Creators turn on test ads and see real view and click counts.

**Non-goals before the contest.**
- Apple sign-in (until the membership exists).
- High scores, and merging of conflicting project edits.
- Real ad networks, real in-app payments, payouts and the marketplace. Test-mode purchases are in scope (C12).
- iOS and web creator sign-in.

**Anti-goals: these would be failures even if the feature works.**
- A title-screen object type special-cased in the engine.
- A sign-in bypass or a fake provider reachable in production.
- Invented revenue, or analytics that are not real counts.
- Secrets in the repository, projects, logs or exports.
- A builder action without its agent tool and parity test (ADR-0009). The one exception is the project-file action
  in §5.

## 4. C9 — Game title screens

**Scenes in the builder.**
- Scene list in compact and expanded layouts:
  - add, rename, duplicate;
  - remove (undoable), reorder;
  - **Set as start scene**.
- The start scene is stored in the project. It needs a `ProjectCodec` version bump and a migration; older files use
  their first scene. `GamePackage` takes the project's start scene.

**Catalog and parity.**
- New catalog entries for the existing `AddScene` and `RenameScene`.
- New operations `RemoveScene`, `DuplicateScene`, `MoveScene` and `SetStartScene`, each with an entry.
- Each one gets a derived agent tool and an `OperationParityTest` case.

**"Go to scene ⟨scene⟩" action block.**
- It lives in `RuleBlocks` and `RuleBlockCatalog`, with its control generated from the catalog.
- The play session switches at the end of the tick, building a new world from the target scene.
- Numbers do not carry across scenes yet. Game-wide values are later work.
- A missing target is a rule problem shown on the block.

**"Add title screen" helper.** One builder action stages ordinary operations:
- a scene with title text and a background;
- a tagged **Play** button (shape plus text) with the rule "When tapped → Go to scene ⟨first level⟩";
- the new scene set as the start scene.

The creator then changes everything through the normal inspector and rules: text, colours, sprite, button text and
extra buttons.

**Players.** Builder Play, the web player and the Arcade start at the start scene and follow "Go to scene".

## 5. C10 — Preset games

- **Project → New game** opens a picker: **Blank** plus three presets with a picture and a one-line pitch, in compact
  and expanded layouts.
- **Which presets.** They use only today's blocks plus "Go to scene":
  - **Coin Dash**: move and collect coins; the last coin leads to "You win".
  - **Tap Targets**: tap moving targets to score before the timer, counted down every second, reaches 0.
  - **Maze Run**: walls restart the scene; the exit leads to "You escaped".

  Games that need spawning, random placement or scaling upgrades need new blocks. Those blocks are separate work,
  not part of C10.
- **How presets are made.** Each preset is made by hand in the running builder and saved as a normal project file
  with a title screen. Files are embedded into the app with the `embedded-templates` generator pattern. Format
  changes rely on `ProjectCodec` migrations.
- **Using a preset.** Choosing a preset loads a copy through the normal unsaved-changes dialog and autosave.
- **Agent exemption.** Starting from a preset is a project-file action like New and Open, so it is outside the
  editing catalog. An agent builds the same content through operations.
- **Demos.** Two presets double as C8's mechanically different games.

## 6. C11 — Accounts, sync, player accounts and a public Arcade

The accounts decision itself is [ADR-0010](../adr/0010-accounts-on-ktor.md).

**Database.** SQLite through Exposed behind repository interfaces, with a versioned schema. Tables:

| Table | Holds |
|---|---|
| `accounts` | display name, avatar, email |
| `identities` | account, provider, provider subject, password hash |
| `email_codes` | hashed code, purpose, expiry |
| `sessions` | hashed token, expiry |
| `games` | owner |
| `projects` | owner, revision, document, updated |
| `likes`, `plays` | per account and game |
| `ad_events` | ad events (C12), with daily totals |

Games already stored as files are imported as unowned.

**Sign-in methods.**
- **Email.** Argon2id hashes (pure-Java Bouncy Castle), a 6-digit verification code, password reset by code, and
  SMTP from `SPAWNKIT_SMTP_*`. Codes go to the console only in local mode. Sign-in attempts are rate-limited, and
  errors do not reveal whether an email exists.
- **Google.** Android uses Credential Manager. Desktop uses the system browser with a loopback redirect. The Arcade
  page uses the authorization code flow with `state` and PKCE. The server verifies the ID token against Google's
  keys.
- **Apple.** Same shape as Google, built later.
- Providers that are not configured are hidden. Linking a second method is allowed; automatic merging is not.

**Clients.**
- The token is kept in C7c's `SecureStore`.
- Android and desktop creators can sign in. iOS and web creators show "coming later".
- The Arcade page uses an HttpOnly, SameSite, Secure cookie.

**Own my games.**
- Publishing requires sign-in, and keeps its confirmation.
- **My games** in the Release Manager lists the creator's games, and republishes or unpublishes one after
  confirmation.

**Sync.**
- **Save to my account** and **Open from my account** sit beside local files.
- Each save stores a revision. Saving against an older revision warns the creator and keeps both copies.
- Quotas apply. Local saving works without an account.

**Arcade page.**
- A server-rendered page (kotlinx.html) at `/` with:
  - the games list with likes and a Play link;
  - sign-in;
  - like and unlike;
  - **My played games**, recorded when a signed-in player opens `/play/{slug}/`.
- It is plain HTML and CSS, and works at phone width.

**Deployment.**
- The server JAR runs as a service on the owner's host. TLS comes from the host platform or Caddy.
- A persistent volume holds the database and games, with a nightly copy.
- Secrets are environment variables.
- The server trusts forwarded headers only from the proxy.
- The builder's Arcade address is configurable.
- Steps are documented in `docs/arcade-hosting.md`.

**Owner actions.**
- the host and domain;
- a Google OAuth client (web and Android, with the public return URL and `http://127.0.0.1` for desktop);
- an SMTP account;
- the host's environment variables;
- later, the Apple membership and key.

## 7. C12 — Test ads and test purchases with real counts

**Builder.**
- The Release Manager gets an **Ads** section, off by default, with two placements:
  - a **banner on the title screen**;
  - an **interstitial between scenes**, at most once every N scene changes and never before the first play.
- The settings are stored in the project (codec bump). Changing them is a catalog operation with an agent tool and a
  parity test.

**Player.**
- An `AdHost` interface with explicit results.
- `TestAdHost` draws a labelled **"TEST AD — SpawnKit"** panel. It uses no ad SDK and makes no third-party calls.
- Builder previews never count.
- Arcade plays send `view` and `click` events to `POST /api/games/{slug}/ad-events`: batched, rate-limited,
  deduplicated per play session, with no personal data.
- Exported offline games show test ads and do not report.

**Statistics view (signed-in creator).**
- Views, clicks and click-through rate per game and day, from real counts.
- Revenue is shown as **"$0.00 — test ads earn nothing"**.
- An optional **What if** line multiplies the views by a rate the creator types in, and is labelled "estimate, not
  earnings".

**Later.** AdMob (K6) plugs into `AdHost`.

## 8. Schedule and cut line

C7c, C9, C10 and C10b were all finished on 22 Nov, ahead of the first schedule. The schedule from 23 Nov, after the
owner asked for complete games:

| Dates | Work |
|---|---|
| 23–26 Nov | C11 accounts, with the hosted AI proxy |
| 27–28 Nov | C14 game-wide values, spawning and randomness |
| 29 Nov – 1 Dec | C12 test ads and test purchases |
| 2–4 Dec | C13 AI-first building of complete games; public deployment; live AI recordings |
| 5–7 Dec | C8 |

No buffer day is left. The order puts C13 last before C8, so the AI can build with everything the other increments
add.

The owner decided that nothing is trimmed. The cut order below is only a safety net: it is used only if C8's reserved
days are at risk, and the owner is told first. High scores and Apple are already out.
1. Google sign-in. Email stays.
2. The interstitial placement.
3. The What if estimate.
4. My played games.
5. Duplicate and reorder in the scene list.
6. Public deployment, falling back to a local demo that is stated as such.

## 9. C13 — AI-first building and hosted AI

The decision is [ADR-0011](../adr/0011-hosted-ai-model-proxy.md).

**Hosted AI (with C11).**
- The Ktor server forwards the assistant's model calls through Koog with the owner's key, which lives only in the
  server environment.
- The agent loop stays on the device (`HostedModel : AgentModel`), so every change is an ordinary undoable edit of
  the creator's local game.
- The proxy fixes the system prompt and accepts only SpawnKit tools.
- **Limits:**
  - a $20 monthly cap kept in the database;
  - a per-account fair-use limit (default $1 a day) and a request rate limit, so one account cannot use up the shared
    cap.

  Everything is free; accounts carry a plan, so paid subscriptions plug in at launch.

**AI-first builder.**
- **Start with a prompt.** New game opens with "Describe your game…" first, and the presets below it as starting
  points.
- **A complete game from one prompt (owner, 2026-11-23).** Even a creator with no experience gets a fully functional
  game:
  - a home screen (title screen) and several levels;
  - game-wide values such as coins and lives that carry between levels, plus spawning and randomness (C14);
  - a shop with test purchases and ads at chosen spots (C12);
  - win and lose scenes.

  The AI first plans the game (screens, levels, mechanics, shop, ad spots), then builds every part with the builder's
  tools. It checks each scene in the real engine and traces the main rules. A short summary says what it made and
  how to change it by hand.
- **What "app" means before the contest.** The game plays in the builder, publishes to the Arcade as a link and
  exports for the web. Play Store and App Store apps need the build service (K5, K6) and come after the contest.
- **The chat is the main panel.** It is docked beside the canvas on wide screens and is the first tab on phones. Steps
  stream in while the canvas updates.
- **Apply right away, with Revert.** Each AI message is applied as one undo step and gets a **Revert** button. Review
  first stays available as an option for creators who want it.
- **Manual tools are unchanged.** Every change the AI made can be edited by hand, and the AI sees manual changes the
  next time it looks at the game.
- **Signed out.** Without an account, creators build by hand and can watch labelled replays. Building with AI needs
  an account so the limits work.
- **Bring-your-own-key** leaves the normal UI once hosted AI works. It stays behind a developer setting.

**Evidence.**
- A live recording (`record-ai-session`) of a whole game built from one prompt, reviewed and committed as a labelled
  replay.
- Proxy tests with a fake model executor: the cap, the per-account limit, refusals, and the tool allowlist.
- UI tests of prompt-first New game and Revert, in both layouts.
- Android emulator screenshots.

## 10. C14 — Game-wide values, spawning and randomness ([#88](https://github.com/aioanei/spawnkit/issues/88))

Complete games need three general capabilities the engine lacks today. Each gets rule blocks with catalog entries,
agent tools and parity tests (ADR-0009), and none is specific to a genre (ADR-0006):
- **Game-wide values.** Named numbers such as Coins, Lives and Level that keep their value across "Go to scene".
  Blocks change, set, compare and show them. A play starts fresh, except for what purchases unlock (C12).
- **Spawning.** "Spawn a copy of ⟨object⟩ at ⟨place⟩" during play, so enemies, falling objects and shots can appear.
  Copies run their own rules.
- **Randomness.** Random positions and numbers in blocks, from the engine's seeded random generator, so tests repeat
  exactly on every target.

**C12 in-game purchases (test mode)**, added to §7:
- A shop is an ordinary scene. Items are objects with a price and a "Buy" rule that grants something through a
  game-wide value, such as a skin, extra lives or a level.
- Every purchase is labelled "TEST PURCHASE: no money is charged". Purchases from Arcade plays are really counted
  and appear in the creator's earnings view next to test ads, with revenue $0.00.
- Real payments (Google Play Billing, App Store, Stripe) plug in behind the same host interface after launch.

## 11. Left to the builder

The following are left to the increment's implementation plan:
- table and column details;
- endpoint shapes beyond those named here;
- UI layouts within the compact and expanded patterns already used by the builder;
- the interstitial frequency N;
- token and code lengths within current security guidance;
- the choice of SMTP client library.

Each new dependency's license and reason goes in the PR and in [third-party notices](../THIRD_PARTY.md).
