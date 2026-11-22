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
- Real ad networks, in-app purchases, payouts and the marketplace.
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

## 7. C12 — Test ads with real counts

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

| Dates | Work |
|---|---|
| 23–24 Nov | C7c |
| 25–26 Nov | C9 |
| 27 Nov | C10 |
| 28 Nov – 1 Dec | C11, with deployment on 1 Dec |
| 2–3 Dec | C12 |
| 4 Dec | buffer |
| 5–7 Dec | C8 |

If time runs short, cut in this order. High scores and Apple are already out.
1. Google sign-in. Email stays.
2. The interstitial placement.
3. The What if estimate.
4. My played games.
5. Duplicate and reorder in the scene list.
6. Public deployment, falling back to a local demo that is stated as such.

## 9. Left to the builder

The following are left to the increment's implementation plan:
- table and column details;
- endpoint shapes beyond those named here;
- UI layouts within the compact and expanded patterns already used by the builder;
- the interstitial frequency N;
- token and code lengths within current security guidance;
- the choice of SMTP client library.

Each new dependency's license and reason goes in the PR and in [third-party notices](../THIRD_PARTY.md).
