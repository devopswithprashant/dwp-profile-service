# Blog Service Context — Linking Blogs to User Profiles

## Purpose

Use this handoff when changing the Blog Service so newly created blogs are
associated with the authenticated author's User Service profile rather than
being anonymous. It documents the implemented User Service contract and the
recommended identity boundary; it does not assume a Blog Service framework,
database, API shape, or deployment topology.

The Auth Service owns login, credentials, identity, and JWT issuance. The User
Service owns user profile data. The Blog Service owns blogs and should store
the Auth user's UUID as the author's stable cross-service identity. Each
service accesses only its own database; do not add cross-service database
access or a cross-database foreign key.

## Required behavior

- A new blog creation must require an authenticated user.
- Derive the author UUID from the verified JWT `sub` claim. Never trust an
  `authorId`, `userId`, or author object supplied in the blog request body,
  query string, or an untrusted HTTP header.
- Persist that UUID as the blog's author reference in the Blog Service's own
  database. Keep the UUID as the stable identity even if a display name or
  avatar changes.
- Authorize update and delete operations by comparing the authenticated
  subject UUID with the blog's stored author UUID, plus any explicitly defined
  administrative policy.
- Public blog reads may remain anonymous. Authentication for creating or
  managing a blog does not require making public content private.
- Preserve legacy anonymous records during rollout unless the Blog product
  explicitly chooses a backfill or retirement policy. Do not assign existing
  blogs to an arbitrary user.

Recommended request shape: the client submits blog content only; it does not
submit author identity. The Blog Service associates the verified principal
server-side. Responses may expose an author projection (for example, a user
UUID, display name, and avatar URL) without exposing private profile fields.

## User Service profile API

The User Service HTTP port defaults to `8080`; confirm the deployed service
address and any gateway prefix for each environment. Use configuration or
service discovery rather than hardcoding a developer machine hostname.

| Method | Path | Authentication | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/users/{userId}` | None | Retrieve the public profile for an author UUID |
| `GET` | `/api/v1/users/me` | Bearer JWT | Retrieve the current user's full profile |
| `PUT` | `/api/v1/users/me` | Bearer JWT | Replace mutable fields on the current user's profile |

For rendering blog authors, use the unauthenticated public endpoint:

```http
GET /api/v1/users/8c0c0b42-ff2a-4d68-b90f-3b845ca8106a
```

Successful response (`200`):

```json
{
  "userId": "8c0c0b42-ff2a-4d68-b90f-3b845ca8106a",
  "displayName": "Ada",
  "bio": "Engineer",
  "avatarUrl": "https://images.example.test/ada.png",
  "createdAt": "2026-10-18T12:00:00Z"
}
```

Only `userId`, `displayName`, `bio`, `avatarUrl`, and `createdAt` are public.
`preferences`, `socialLinks`, and `updatedAt` are not returned by this route.
Unset nullable text values are JSON `null`. Use the `userId` in the response
as the canonical ID and verify it matches the requested UUID.

| Status | Meaning | Suggested handling |
|---|---|---|
| `200` | Public profile found | Render the allowlisted author fields |
| `400` | Path value is not a standard UUID | Treat as an integration/data error; do not retry unchanged input |
| `404` | No profile exists for this UUID | Show a neutral/unavailable author state; do not invent profile data |
| `5xx` or network failure | Profile Service, gateway, or transport unavailable | Apply bounded retries for reads or degrade author presentation; never expose private data as a fallback |

Public profile responses do not require the Blog Service to forward the
reader's bearer token. Configure the internal/public API origin and TLS
according to the deployment environment.

## Identity and JWT contract

The Auth Service issues the access token. The User Service currently validates
JWTs with:

- Algorithm: `HS384`
- Issuer: `devopswithprashant-auth`
- Audience: `devopswithprashant-platform`
- `sub`: canonical UUID of the Auth identity
- `exp`: required and must be in the future
- `iat`: optional; must not be in the future
- `nbf`: if present, must not be in the future

The User Service uses `JWT_SECRET`, requires at least 48 UTF-8 bytes, and
requires the Auth Service to use the same signing secret. Never put the
secret or bearer token in source, checked-in configuration, URLs, logs, or
error reports.

The Blog Service must establish the caller identity using one of these
explicitly trusted arrangements:

1. Validate the Auth-issued JWT itself using the agreed issuer, audience,
   algorithm, keys, expiry, and subject rules; or
2. Rely on a gateway that validates the token only if the Blog Service accepts
   identity metadata exclusively over a trusted, authenticated gateway-to-
   service path and cannot be reached in a way that bypasses that gateway.

Do not trust a caller-provided identity header just because it is named
`X-User-Id`. If using direct JWT validation, coordinate secure key delivery
with platform/Auth owners. Sharing the current HS384 signing secret grants
each holder the ability to mint tokens; consider a coordinated asymmetric
signing design if the platform requires verification without token-issuing
capability. The User Service's bearer-token validation is for its REST routes;
it does not automatically authenticate Blog Service requests or the User
Service's gRPC listener.

## Request and author lookup flow

1. The client sends the blog creation request with the Auth-issued bearer
   token and blog content; it sends no author UUID.
2. The Blog Service validates the token (or consumes identity from the
   explicitly trusted gateway boundary).
3. The Blog Service reads the verified UUID from `sub` and persists that UUID
   with the blog in its own transaction.
4. When showing authors, the Blog Service resolves that UUID through
   `GET /api/v1/users/{userId}` and uses only the public response fields.

The Blog Service does not create user profiles. Profile creation/provisioning
belongs to the Auth Service calling the User Service's gRPC
`CreateDefaultProfile` after the identity transaction commits. Blog creation
must not call that gRPC method or create a second identity/profile. The gRPC
listener defaults to port `9090`, currently has no caller authentication or
TLS, and is intended for a trusted internal network; it is not the Blog
Service's author lookup API.

The public profile API has no batch endpoint. For blog listing pages, avoid
an unbounded one-request-per-post lookup pattern: deduplicate author UUIDs and
use the Blog Service's established bounded cache or request batching strategy.
Do not cache private profile data in the author projection. If this becomes a
measured bottleneck, coordinate a batch public-profile API with the User
Service rather than reading its database.

## Existing anonymous blogs and migration

The User Service cannot infer which existing anonymous blogs belong to which
profiles. The Blog Service owns its schema and migration.

- Keep legacy author references nullable or represent legacy anonymity
  explicitly until product owners decide otherwise.
- Require a verified author for new blog writes after the feature is enabled.
- Do not backfill by matching display names, emails from blog content, or
  client-supplied IDs. A backfill requires a trusted mapping from the identity
  owner.
- Plan the migration and rollout so older clients or old records do not cause
  accidental ownership assignment.

## Decisions to confirm in the Blog Service

- Which write operations require authentication (create, edit, delete, draft,
  comments, or other author actions).
- Whether unauthenticated legacy-blog creation is removed entirely or retained
  as an explicitly separate guest flow. The recommended default is that new
  blog creation requires login.
- How the Blog Service validates the JWT, and whether it receives requests
  directly or only through a trusted gateway.
- Deployed User Service API origin, TLS, network access, and gateway path.
- How author details are resolved and cached without creating N+1 requests.
- How old anonymous blogs are displayed and whether any trusted backfill
  source exists.
- How an author's profile deletion/absence or temporary User Service outage
  should appear in blog pages.
- Whether the author projection includes display name/avatar snapshots or is
  resolved from the current public profile. The User Service is the canonical
  source for current profile values.

## Implementation checklist

- [ ] Inspect the Blog Service's current authentication, routes, domain model,
      persistence schema, migration tooling, and test conventions first.
- [ ] Add a required author UUID reference for new authenticated blog records.
- [ ] Derive ownership from a verified JWT subject or a strictly trusted
      gateway identity; reject missing/invalid identity on protected writes.
- [ ] Ignore or reject client-supplied author/user identity fields.
- [ ] Enforce owner checks on every author-controlled operation.
- [ ] Resolve author cards through the User Service public profile endpoint,
      handling `400`, `404`, timeouts, and `5xx` without leaking tokens.
- [ ] Preserve existing anonymous data and make any backfill an explicit,
      audited product/data decision.
- [ ] Configure service origin, TLS, JWT verification material, and any
      bounded read retry/cache through the Blog Service's deployment settings.
- [ ] Test missing/invalid tokens, forged author IDs, successful association,
      cross-user modification/deletion denial, legacy anonymous records,
      profile `404`, and User Service unavailability.
- [ ] Confirm public blog reads remain available without authentication if that
      is the existing product behavior.

## Related User Service references

- [`context.md`](./context.md) — project blueprint and implemented service
  status.
- [`context-for-auth.md`](./context-for-auth.md) — Auth-owned JWT issuance and
  profile provisioning handoff.
- [`context-for-ui.md`](./context-for-ui.md) — detailed REST response and
  status-code contract for the public profile and `/me` routes.
- [`src/main/scala/com/devopswithprashant/service/user/DwpuserserviceRoutes.scala`](./src/main/scala/com/devopswithprashant/service/user/DwpuserserviceRoutes.scala) —
  implemented HTTP routes and status handling.
- [`src/main/scala/com/devopswithprashant/service/user/JwtAuthenticator.scala`](./src/main/scala/com/devopswithprashant/service/user/JwtAuthenticator.scala) —
  JWT verification rules and claims.
- [`src/main/protobuf/user_profile.proto`](./src/main/protobuf/user_profile.proto) —
  Auth-owned default-profile provisioning contract; not used for blog author
  lookups.
