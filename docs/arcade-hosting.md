# Arcade hosting

How to run SpawnKit's Ktor server: the Arcade, accounts (C11a, [ADR-0010](adr/0010-accounts-on-ktor.md)) and the
hosted AI proxy ([ADR-0011](adr/0011-hosted-ai-model-proxy.md)). Publishing and playing are described in
[release](release.md).

## Run it

```sh
./gradlew :server:installDist
SPAWNKIT_DATA_DIR=/srv/spawnkit server/build/install/server/bin/server
```

The Toolchain builds the same server as a JAR: `./kotlin package -m server`. Build the web player first on a clean
checkout (`./kotlin build -m webPlayer -v release`); the Gradle tasks do that themselves.

`./gradlew :server:run` turns on Ktor's development mode, whose reloading class loader cannot read this project's
Java 25 classes. Use the installed server, or set `-Dio.ktor.development=false`.

For local development, copy `.env.example` to `.env` (ignored by git), fill in the keys and run
`powershell -File tools/run-server-local.ps1`. It loads `.env` into the server's environment only, prints just the
names it loaded, builds the installed server and starts it.

## Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `PORT`, `HOST` | `8080`, `0.0.0.0` | Where the server listens. |
| `SPAWNKIT_DATA_DIR` | `~/.spawnkit` | Holds the database `spawnkit.db` and, by default, the Arcade's game files. |
| `SPAWNKIT_ARCADE_DIR` | `$SPAWNKIT_DATA_DIR/arcade` | Published game files. |
| `SPAWNKIT_LOCAL` | unset | `1` turns on local mode: without SMTP, account codes are printed to the console. Never in production. |
| `SPAWNKIT_SMTP_HOST`, `SPAWNKIT_SMTP_PORT` | unset, `587` | The SMTP server for account emails (STARTTLS required). Without it, and outside local mode, email sign-in is unavailable. |
| `SPAWNKIT_SMTP_USER`, `SPAWNKIT_SMTP_PASSWORD` | unset | SMTP sign-in. |
| `SPAWNKIT_SMTP_FROM` | `SpawnKit <no-reply@HOST>` | Sender of account emails. |
| `ANTHROPIC_API_KEY` | unset | The owner's model key. Without it, `/api/ai/reply` answers that SpawnKit AI is not set up. |
| `OPENAI_API_KEY` | unset | The owner's image model key for Character Forge (ADR-0013). Without it, `/api/ai/character` answers that Character Forge is not set up, and the builder hides "Character from photo…". |
| `SPAWNKIT_IMAGE_MODEL` | `gpt-image-1-mini` | The OpenAI image model Character Forge uses. |
| `SPAWNKIT_AI_MONTHLY_DOLLARS` | `20` | Everything the server may spend on AI in one calendar month (UTC). |
| `SPAWNKIT_AI_ACCOUNT_DAILY_DOLLARS` | `1` | What one account may spend on AI in one UTC day. |

Secrets come only from the environment. The startup log prints the configuration without them.
`GET /api/server` tells clients whether email sign-in and AI are available.

## Accounts

| Request | Body | Answer |
|---|---|---|
| `POST /api/auth/register` | `email`, `password` (10–128 characters), `displayName` | 202 and a message. A 6-digit code is emailed. |
| `POST /api/auth/verify` | `email`, `code` | `token` and `account` |
| `POST /api/auth/sign-in` | `email`, `password` | `token` and `account` |
| `POST /api/auth/sign-out` | (bearer token) | 204 |
| `POST /api/auth/reset` | `email` | 202 and a message. A code is emailed if the account exists. |
| `POST /api/auth/reset/confirm` | `email`, `code`, `password` | `token` and `account`. Other sessions end. |
| `GET /api/me`, `DELETE /api/me` | (bearer token) | The account; deleting removes it, its sessions and projects. Its games stay published, unowned. |
| `GET /api/me/games` | (bearer token) | Games this account published. |

Signed-in requests send `Authorization: Bearer <token>`. Errors are `{"error": "plain message"}`: 400 for bad input,
401 for a wrong code, password or session, 429 when a limit is reached, 503 when email is not set up.

- **What the database keeps.** Argon2id password hashes, and SHA-256 hashes of codes and session tokens; never a raw
  token or code. Sessions last 30 days. Codes last 15 minutes and allow 5 tries.
- **No enumeration.** Register, sign-in and reset answer the same whether an email has an account or not.
- **Limits.** Sign-in: 5 failures per email and 10 per network address in 15 minutes. Emails: 5 per address in 15
  minutes. Code requests: 10 per network address in 15 minutes. These counters live in memory; a restart forgets them.
- **Behind a reverse proxy**, every request comes from the proxy's address, so the per-address limits are shared by
  everyone. Reading the client address from `X-Forwarded-For` comes with public deployment (C11c).

## Hosted AI

`POST /api/ai/reply` takes one model request from a signed-in builder (`HostedModel` in `ai`) and returns the model's
reply. The agent and its tools stay on the device; the server only talks to the model.

- **Only the SpawnKit assistant.** The server replaces the instructions with its own, sends its own copies of the
  toolbox tools, refuses unknown tool names, accepts at most 512 KB per request and caps replies at 4,096 tokens.
- **Spending.** Before each call, the server sets aside the most it can cost: every request byte counted as an input
  token at $3 per million, plus a full reply at $15 per million. It checks the monthly cap and the account's daily
  limit in the same step, so concurrent calls cannot pass the cap. Afterwards the reservation is replaced by the
  reported usage, or by nothing when the call fails. An account may also make 120 calls an hour.
- **Refusals** are plain: the monthly cap names the day it starts again; the daily limit renews at midnight UTC.
- Spending rows keep only an account number, a day and an amount. They stay when an account is deleted, so deleting
  an account never lowers the month's total.

## Backups

The database is one SQLite file. Back it up while the server runs with the SQLite shell:

```sh
sqlite3 /srv/spawnkit/spawnkit.db ".backup /backups/spawnkit-$(date +%F).db"
```

Copy `SPAWNKIT_ARCADE_DIR` alongside it. To restore, stop the server and put both back. A database written by a newer
server is refused at startup rather than changed.

## Local evidence (2026-11-23)

With `SPAWNKIT_LOCAL=1` and no model key, curl registered an account (the code appeared on the console), verified it,
signed in, published Coin Dash while signed in, listed it in My games, republished it as its owner, was refused
republishing while signed out, played it, and got the AI route's "not set up" answer:
[transcript](evidence/c11a/local-server-curl.txt).
