# C11b Accounts and Hosted AI in the Builder Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Creators sign in to a SpawnKit server from the builder with email, build with SpawnKit AI through the
hosted proxy (no key of their own), and publish to the Arcade as the owner, with My games to republish and unpublish.
Google sign-in, sync, the Arcade page and deployment are C11c.

**Architecture:**
- **Client.** `ai` gains `AccountClient(baseUrl, http)`: server status, register, verify, sign in, sign out, reset,
  `me`, delete, my games, publish, republish and unpublish, over the C11a routes. Server refusals become
  `AccountException` with the server's message. It sits next to `HostedModel`, which already talks to the same server
  with the same Ktor client.
- **State.** `app/shared` gains `AccountState`: the server address, the signed-in account, and the session token in
  `SecretStore` (`spawnkit-session`; the address under `spawnkit-server`). Every network call runs in the screen's
  scope and reports failures as plain notices.
- **Assistant.** Signed in to a server whose AI is available → `HostedModel` answers ("Live: SpawnKit AI"). Signed
  out → the labelled demo and "Sign in to build with SpawnKit AI". Bring-your-own-key moves under **Developer
  settings** (ADR-0011 §6) and still works there.
- **Release Manager.** Signed in: the Arcade address is the account's server, publishing sends the token, and a **My
  games** list offers Update with this project (republish) and Unpublish. Both are outward actions with a
  confirmation. Signed out: today's anonymous publishing to a typed address stays.

**Tech Stack:** Compose Multiplatform, Ktor client 3.6.1 (already used), the C11a server.

**Spec:** [design §6 and §9](../design/2026-11-22-title-screens-presets-accounts-ads.md),
[ADR-0010](../adr/0010-accounts-on-ktor.md), [ADR-0011](../adr/0011-hosted-ai-model-proxy.md), issues #76 and #85.

## Global Constraints

- The session token lives only in `SecretStore`, never in projects, exports, logs or agent tools.
- Every screen has compact and expanded layouts; touch targets ≥ 48 dp.
- Delete account, publish, republish and unpublish need an explicit confirmation.
- Tests never call a live model or a real server on the network: client tests run against the C11a routes on the Ktor
  test host; UI tests use a fake `AccountClient`.
- Both builds stay green.

## Review Focus

1. **The token never leaks**: not in UI text, logs, the project, or the assistant transcript.
2. **A stale or revoked token** signs the builder out cleanly, with a message, instead of failing every call.
3. **Hosted AI is never presented as the demo, or the demo as live**: labels follow the model actually answering.
4. **Republish targets the game the creator picked**, never another one.

---

### Task 1: AccountClient in `ai`

- `AccountClient(baseUrl, http)` with suspend functions for each C11a route, returning `AccountSession(token,
  account)`, `AccountInfo`, `ServerStatus`, `PublishedGame` lists; refusals throw `AccountException(message, status)`.
- Tests in `server` (the client against the real routes on the test host):
  - `registerVerifySignInAndReadMe`;
  - `publishListRepublishAndUnpublishAsTheOwner`;
  - `refusalsCarryTheServerMessage` (wrong password 401, limit 429);
  - `statusTellsWhetherEmailAndAiAreAvailable`.

### Task 2: AccountState

- `AccountState(client factory, secrets, scope)`: `address`, `status`, `account`, `busy`, `notice`; `checkServer`,
  `register`, `verify`, `signIn`, `requestReset`, `reset`, `signOut`, `deleteAccount`, `myGames`, `refreshGames`.
- On start, a saved token is checked with `me`; a 401 signs out with "Your session ended. Sign in again."
- Unit tests with a fake client: sign-in stores the token; sign-out deletes it; a 401 on `me` clears it; errors become
  notices; the token is not in `toString` or notices.

### Task 3: Account screen

- Project → **Account…** opens a dialog (full screen on phones): server address with Check, then Sign in / Create
  account / Forgot password tabs; code entry; signed-in view with name, email, Sign out, Delete account (confirm).
- UI tests at phone and desktop sizes: create an account with a code, sign in, sign out, delete with confirmation.

### Task 4: Hosted AI in the assistant

- `AssistantState` takes the account: signed in with AI available → `HostedModel(address, token, http)`; the model
  label says "Live: SpawnKit AI (free, with limits)". The key settings move under "Developer settings".
- Tests: signed in uses the hosted model (fake gateway behind a test server, or a fake `AgentModel` factory); signed
  out uses the demo with a sign-in hint; server refusals (limits) show as notices; switching account resets the session.

### Task 5: My games in the Release Manager

- Signed in: the Arcade card shows "Publishing as <name> to <server>", publishes with the token, and lists My games
  with Update with this project and Unpublish (each confirmed).
- UI tests in both layouts with a fake platform: publish as owner, update a picked game, unpublish.

### Task 6: Evidence and docs

- Android emulator against the local server (`SPAWNKIT_LOCAL=1`, reached through `adb reverse`): create an account
  with the console code, sign in, publish, see it in My games, unpublish. Screenshots in `docs/media/android-account-*`.
- Docs: `docs/ai-studio.md` (hosted AI), `docs/release.md` (My games), `PROGRESS.md`, `AGENTS.md`.
