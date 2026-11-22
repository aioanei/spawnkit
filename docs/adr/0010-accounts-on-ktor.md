# ADR-0010: SpawnKit accounts on its own Ktor server

Status: **accepted** (owner decision, 2026-11-22). Extends [ADR-0007](0007-mobile-builder-and-in-builder-release.md)
and [ADR-0009](0009-agentic-parity.md). Design:
[title screens, presets, accounts and test ads](../design/2026-11-22-title-screens-presets-accounts-ads.md).
Increment: C11 in the [contest track](../plans/2026-11-20-engine-foundation-revision.md#contest-delivery-track).

## Context

Today the Arcade is anonymous and file-based, and projects are only local files. On 2026-11-22 the owner moved
accounts into the contest track. An account must let a creator:
- own their published Arcade games;
- sync projects between devices;
- see their ad statistics (C12).

Players must also be able to sign in on the Arcade. The owner asked for "the basic sign-in": email and password,
Google and Apple. GitHub is not needed. The owner will provide a public HTTPS host. They have no Apple Developer
membership yet.

## Decision

1. **SpawnKit's own Ktor server owns accounts and their data.**
   - The server issues its own session tokens after any sign-in method.
   - Tokens are random and opaque, are stored only as hashes, expire after 30 days, and are revoked on sign-out.
   - Clients keep the token in device secure storage, the same store as AI model keys (C7c). The token never appears
     in projects, exports or logs.
2. **Sign-in methods sit behind one `SignInProvider` interface.**
   - **Email and password.**
     - Passwords are hashed with Argon2id.
     - Email is verified with a short-lived code, and passwords are reset with a code.
     - Codes are sent by SMTP. In local mode only (`SPAWNKIT_LOCAL=1`, without SMTP), codes are written to the
       server console, clearly labelled.
   - **Google.**
     - On Android, Credential Manager returns an ID token. Desktop and the Arcade page use the authorization-code
       flow with `state` and PKCE.
     - The server verifies the ID token's issuer, audience, expiry and signature.
   - **Apple: designed, built when the owner has an Apple Developer membership.** It uses the web flow with
     `form_post`, a client-secret JWT signed with the owner's key, and ID-token verification like Google's. The App
     Store will require it in the iOS app once Google sign-in is offered there.
   - **Behaviour common to all methods.**
     - A method whose secrets are not configured is hidden.
     - A signed-in account can link another method. Accounts are never merged automatically because emails match.
3. **Data lives in a server database behind repository interfaces.**
   - The first implementation is SQLite through Exposed. It needs no setup for local runs and keeps the server in
     Kotlin.
   - PostgreSQL can replace it behind the same interfaces.
   - Stored personal data is limited to the display name, avatar, email (when given) and provider subject.
   - "Delete my account" removes the account and its data.
4. **Secrets stay in the environment.** Provider client secrets, keys and SMTP credentials come from environment
   variables on the host. They never come from the repository, projects, logs or exports. Agents never see them
   (ADR-0009).
5. **No bypass and no fake providers in production.** Tests use fake token issuers and a captured-mail stub on the
   Ktor test host. Nothing lets a client sign in without one of the real methods.

## Consequences

- **Publishing.**
  - Publishing to the Arcade requires sign-in.
  - Existing games become unowned listings.
  - Creators can list, update and unpublish their own games. Outward-facing actions keep their confirmation
    (ADR-0009).
- **Project sync.** Sync stores revisions. Saving against an older revision warns the creator and keeps both copies;
  merging is future work.
- **Arcade web page.** The Arcade gains a small server-rendered web page for players: games, sign-in, likes and
  played games.
- **Security work.** Rate limits, redirect allowlists, cookie flags, forwarded-header trust and no account
  enumeration become part of the server's tested surface.
- **Deferred.** Server-hosted agents, credits, payouts and managed publishing still build on these accounts later
  (R8).

## Alternatives considered

- **OAuth only (Google or GitHub).** No passwords to store, but players and creators without those accounts could
  not sign in. The owner wants email sign-in, and does not need GitHub.
- **A hosted backend (Firebase or Supabase).** Faster to wire, but data would move off SpawnKit's own server. It
  would add an outside account to every deployment and split ad statistics across two backends.
