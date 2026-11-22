# ADR-0011: AI-first building through a hosted model proxy

Status: **accepted** (owner decision, 2026-11-22). Extends [ADR-0005](0005-ai-writes-via-typed-commands.md),
[ADR-0009](0009-agentic-parity.md) and [ADR-0010](0010-accounts-on-ktor.md). Design:
[§9 of the contest additions](../design/2026-11-22-title-screens-presets-accounts-ads.md#9-c13--ai-first-building-and-hosted-ai).
Increments: C11 (the proxy) and C13 (the AI-first builder) in the
[contest track](../plans/2026-11-20-engine-foundation-revision.md#contest-delivery-track).

## Context

The assistant (C7) works through the builder's own operations: every builder control has a derived agent tool, and
every AI change is an ordinary undoable edit. Today each creator must bring their own Anthropic key. On 2026-11-22 the
owner asked for SpawnKit to be **AI-first, like Lovable**: the AI builds the game, and creators can still change
everything with the builder's tools. The owner decided:

- **No bring-your-own-key** in the launched product. Subscriptions will come later.
- **Everything free for now**, with a hard limit of **$20 a month** on AI spending.
- **AI changes apply right away**, each as one undo step with a Revert button, instead of Accept/Reject.
- **Nothing is trimmed** to make room.

## Decision

1. **The model runs behind SpawnKit's Ktor server, using the owner's key.**
   - The key comes only from the server's environment (`ANTHROPIC_API_KEY`).
   - It never reaches clients, projects, logs or recordings.
2. **The server is a model proxy; the agent loop stays on the device.**
   - The client keeps running `AgentSession`, so tools act on the creator's local editor and every change is an
     ordinary undoable edit.
   - A new `HostedModel : AgentModel` sends each model request to `POST /api/ai/reply` with the account's session
     token (ADR-0010).
   - The server forwards the request to the model through Koog and returns the reply and its usage.
3. **The proxy only serves the SpawnKit assistant.**
   - The server sets the system prompt itself.
   - It accepts only tool definitions whose names belong to the SpawnKit toolbox and catalog.
   - It caps output tokens and request size, so it is not a general-purpose LLM endpoint.
4. **Spending limits.**
   - A **monthly cap**, $20 by default (`SPAWNKIT_AI_MONTHLY_DOLLARS`). It is kept in the database from the usage the
     model reports. When it is reached, requests are refused with a plain message and the date the cap resets.
   - A **per-account fair-use limit** protects that shared cap from one account. The default is $1 of AI per account
     per day (`SPAWNKIT_AI_ACCOUNT_DAILY_DOLLARS`), plus a request rate limit.
   - These are abuse protection, not paid tiers. Everything is free.
5. **Subscriptions plug in later.** The check that allows a request reads an account's **plan**. Every account is on
   the free plan for now; paid plans and their billing (Stripe, Google Play, App Store) come with the launch, after the
   contest.
6. **Bring-your-own-key leaves the normal UI** once hosted AI works. It stays behind a developer setting for local
   testing, and the recorder workflow keeps using the repository secret.
7. **Signed-out creators** can still build by hand and watch labelled replays. Building with AI needs an account, so
   the limits can be enforced.

## Consequences

- Judges and users can build with AI without a key, once the server runs on the owner's public HTTPS host.
- **The owner pays for AI use**, up to the monthly cap. The cap and the per-account limit are server settings.
- **Hosted AI needs C11 accounts and a public host.** Until the host exists, it works only against a local server.
- **Tests never call a live model.** The proxy is tested with a fake Koog executor on the Ktor test host. The cap, the
  per-account limit and the refusal paths have tests.
- **One open-endpoint risk remains.** A signed-in account could send its own prompts through the proxy within its
  limits. The fixed system prompt, the tool allowlist and the limits bound that risk.
