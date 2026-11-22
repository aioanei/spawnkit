# C11a Accounts Server and Hosted AI Proxy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Ktor server gains a database, email accounts with sessions, owned Arcade games and the hosted AI model
proxy with its spending limits. The client side, Google sign-in, sync, the Arcade page and deployment are C11b.

**Architecture:**
- **Database.** SQLite through Exposed (JDBC) behind small store classes, with a `schema_version` table and ordered
  migrations.
- **Accounts.** An `Accounts` service holds the rules: Argon2id passwords, 6-digit codes, hashed session tokens,
  attempt limits, and no account enumeration. It sends codes through a `MailSender`: a console sender in local mode
  only, and SMTP otherwise.
- **Requests.** Routes stay thin. A request is signed in with `Authorization: Bearer <token>`.
- **Hosted AI.**
  - `ai` gains `ModelRequestJson`, a wire format, and `HostedModel : AgentModel`.
  - The server's `/api/ai/reply` sets the system prompt, checks the tool allowlist, enforces the limits in
    `SpendLedger`, and calls a `ModelGateway`. The real gateway is `KoogGateway`; tests use a fake.

**Tech Stack:** Ktor 3.6.1, Exposed 1.6.1, sqlite-jdbc 3.54.1.0, Bouncy Castle 1.87 (Argon2id), Angus Mail 2.0.5,
Koog 1.3.0.

**Spec:** [design §6 and §9](../design/2026-11-22-title-screens-presets-accounts-ads.md),
[ADR-0010](../adr/0010-accounts-on-ktor.md), [ADR-0011](../adr/0011-hosted-ai-model-proxy.md), issues #76 and #85.

## Global Constraints

- **Secrets** come only from the environment: `ANTHROPIC_API_KEY`, `SPAWNKIT_SMTP_*`. They are never in logs,
  responses, the database or the repo.
- **Session tokens** are 32 random bytes, Base64url-encoded, and stored only as SHA-256 hashes. They expire after
  30 days and are revoked on sign-out.
- **Passwords** use Argon2id with 64 MiB memory, 3 iterations, parallelism 1 and a 16-byte salt.
  - Passwords are 10 to 128 characters.
  - Codes are 6 digits, stored hashed, expire after 15 minutes, and allow 5 tries.
- **No enumeration.** Register, sign-in and reset answers do not reveal whether an email exists.
- **Rate limits.** Sign-in allows 10 attempts per address and 5 per email per 15 minutes.
- **Console codes** happen only when `SPAWNKIT_LOCAL=1` and no SMTP is configured. The console line says it is local
  mode.
- **AI limits:**
  - a monthly cap from `SPAWNKIT_AI_MONTHLY_DOLLARS` (default 20);
  - a per-account daily limit from `SPAWNKIT_AI_ACCOUNT_DAILY_DOLLARS` (default 1);
  - 120 model calls per account per hour.

  Requests are refused with plain messages. There is no bypass.
- **Tests** never call a live model or a real mail server.
- **Both builds** stay green. New dependencies go in the catalog and `docs/THIRD_PARTY.md`.

## Review Focus

1. **A stolen database must not yield sign-ins.** Only token and code hashes are stored; no raw tokens.
2. **Concurrent spending must not exceed the cap.** Recording spend and checking the cap happen in one transaction.
3. **The proxy must not be a free general model API.** The server's system prompt always wins, unknown tool names are
   refused, and requests are bounded in size and in output tokens.
4. **Owned games.** Republish and unpublish by anyone but the owner are refused. Games published before accounts stay
   playable and listed as unowned.
5. **Deleting an account** removes its identities, sessions and projects. Its published games become unowned, so play
   links keep working.

---

### Task 1: Database and schema

- Create `server/src/main/kotlin/dev/spawnkit/data/Db.kt`, with the tables `Accounts`, `Identities`, `EmailCodes`,
  `Sessions`, `Games`, `Projects`, `Likes`, `Plays` and `AiSpend`, and `SchemaVersion`.
- `Db.open(path: Path): Db` connects, creates the directory, and runs the migrations in one transaction. Tests:
  - `aFreshDatabaseGetsTheCurrentSchema`;
  - `reopeningKeepsDataAndVersion`;
  - `aNewerSchemaThanThisServerIsRefused`.
- Commit `feat(server): SQLite database with versioned schema (C11a)`.

### Task 2: Accounts service

- `Passwords` (Argon2id with the parameters above), `Tokens` (random tokens and SHA-256), `MailSender` with
  `ConsoleMail` and `SmtpMail`, and `Accounts`.
- `Accounts` operations: `register`, `verify`, `signIn`, `account(token)`, `signOut`, `requestReset`, `reset` and
  `delete`.
- Tests, with a captured mail sender and a fake clock:
  - `registerVerifyAndSignIn`;
  - `wrongPasswordsAreLimited`;
  - `answersDoNotRevealWhetherAnEmailExists`;
  - `codesExpireAndAllowFiveTries`;
  - `tokensAreStoredHashedAndExpire`;
  - `signOutRevokes`;
  - `resetChangesThePasswordAndEndsOtherSessions`;
  - `deleteRemovesTheAccountAndFreesItsGames`;
  - `consoleMailOnlyInLocalMode`.

### Task 3: Auth routes and owned games

- **Auth routes:** `POST /api/auth/register`, `/verify`, `/sign-in`, `/sign-out`, `/reset`, `/reset/confirm`;
  `GET /api/me`; `DELETE /api/me`.
- **Publishing:**
  - with a bearer token, the game is owned by that account;
  - without one, it stays unowned, as today, until C11b's client requires sign-in.
- **Game management:**
  - `GET /api/me/games`;
  - `PUT /api/games/{slug}` republishes, owner only;
  - `DELETE /api/games/{slug}` unpublishes, owner only.
- Existing game files are imported as unowned at startup.
- Ktor test host tests cover each route, ownership refusals and the 401 answers.

### Task 4: Hosted AI proxy

- **In `ai`:**
  - `ModelRequestJson` encodes and decodes requests and replies; tests check the round trips;
  - `HostedModel(baseUrl, token, http)` posts to `/api/ai/reply`. Server refusals become exceptions with the server's
    message, which `AgentSession` shows as a failed request.
- **On the server:**
  - `SpendLedger` keeps the monthly and daily totals in `AiSpend`;
  - `ModelGateway`, with `KoogGateway(apiKey)`;
  - the route checks the size and the tool allowlist (toolbox tools plus catalog operation IDs) and forces
    `AgentSession.SYSTEM`. It records the spend from the reported usage.
- Tests with a fake gateway:
  - `aSignedInAccountGetsAReply`;
  - `signedOutIsRefused`;
  - `theMonthlyCapRefusesWithTheResetDate`;
  - `theDailyAccountLimitRefuses`;
  - `unknownToolsAreRefused`;
  - `theServerSystemPromptIsUsed`;
  - `spendIsRecordedFromUsage`.

### Task 5: Configuration, docs and evidence

- `ServerConfig.fromEnvironment()` wires everything in `Application.module`. The AI route is installed only when
  `ANTHROPIC_API_KEY` is set; otherwise it answers that AI is not configured.
- `docs/arcade-hosting.md` covers the environment variables, local mode and SQLite backups. Also update
  `docs/THIRD_PARTY.md`, `PROGRESS.md` and `AGENTS.md`.
- **Local evidence:** with `SPAWNKIT_LOCAL=1` and curl, register (code on the console), verify, sign in, publish an
  owned game, list my games, and get the AI route's "not configured" answer.
