# UI Service Context — Profile API Integration

## Purpose and ownership

Use this document when integrating a frontend/UI service with the Scala Profile
Service. It describes the implemented HTTP API contract, authentication
expectations, validation, and integration constraints.

The Auth Service owns identity, credentials, login, and JWT issuance. The Profile
Service owns profile data. The UI must use the Auth Service for authentication
and the Profile Service REST API for profile reads and updates. Do not connect the
UI directly to either database and do not send a user ID to identify the
current user: `/me` derives identity from the verified JWT subject.

This handoff does not prescribe a UI framework, routing design, component
library, or visual design. Inspect the UI repository and its existing API/auth
patterns before implementing.

## API base and routes

The Profile Service exposes these routes:

| Method | Path | Authentication | Purpose |
|---|---|---|---|
| `GET` | `/health/live` | None | Process liveness probe |
| `GET` | `/health` | None | Readiness probe including PostgreSQL |
| `GET` | `/metrics` | None; restrict by network | Prometheus scrape endpoint; not a UI API |
| `GET` | `/api/v1/profiles/me` | Bearer JWT | Get the signed-in user's full profile |
| `PUT` | `/api/v1/profiles/me` | Bearer JWT | Replace mutable profile fields |
| `GET` | `/api/v1/profiles/{profileId}` | None | Get the public profile projection |

Configure the Profile Service API origin through the UI's normal environment or
deployment configuration. Do not hardcode a machine-local hostname in
production code. The server's local HTTP port defaults to `8080`, but a
deployment gateway or reverse proxy may expose a different origin or path
prefix. Confirm the actual deployed origin, TLS, gateway path rewriting, and
CORS policy with the deployment configuration.

The service currently has no explicit CORS middleware. If browser code calls
the service cross-origin, configure CORS at the approved gateway/server
boundary for the UI origins and methods/headers required; do not assume
cross-origin requests are enabled. Prefer the platform's established
same-origin proxy/gateway pattern when one exists.

## Authentication and identity

For `/api/v1/profiles/me`, send:

```http
Authorization: Bearer <access-token>
```

The Auth Service issues the token. The Profile Service currently validates:

- Algorithm: `HS384`
- Issuer: `devopswithprashant-auth`
- Audience: `devopswithprashant-platform`
- `sub`: canonical Auth user UUID
- `exp`: required and in the future
- `iat`: optional, but must not be in the future
- `nbf`: if present, must not be in the future

The UI should treat access tokens as opaque credentials and follow the Auth
Service's established token renewal/logout flow. Do not mint tokens, infer
identity from editable UI state, append a client-supplied user ID to `/me`, or
log/store tokens in URLs, analytics, error reports, or console output. Token
storage strategy must follow the UI/Auth platform's security model; this API
does not define browser token storage or refresh behavior.

The Profile Service emits structured JSON request logs with method, normalized
route, status, and duration. Its request instrumentation does not read/log
headers or bodies. The UI must still avoid logging tokens or sensitive profile
payloads in its own logs or telemetry.

Missing, malformed, expired, wrong-signature, or otherwise invalid tokens
return HTTP `401 Unauthorized` with a `WWW-Authenticate: Bearer` challenge.
The UI should follow its normal reauthentication/session-expiry flow and avoid
infinite automatic retries with the same token.

The public profile route is unauthenticated and returns only allowlisted
profile fields. It must not be used to retrieve private preferences or social
links.

## `GET /api/v1/profiles/me`

Returns HTTP `200` and a full profile object:

```json
{
  "userId": "8c0c0b42-ff2a-4d68-b90f-3b845ca8106a",
  "displayName": "Ada",
  "bio": "Engineer",
  "avatarUrl": "https://images.example.test/ada.png",
  "preferences": {
    "theme": "dark"
  },
  "socialLinks": {
    "website": "https://example.test"
  },
  "createdAt": "2026-10-18T12:00:00Z",
  "updatedAt": "2026-10-18T12:00:00Z"
}
```

Nullable text fields (`displayName`, `bio`, `avatarUrl`) are JSON `null` when
unset. Timestamps are ISO-8601 strings. `preferences` and `socialLinks` are
JSON objects and may be empty. Do not assume only the example preference or
social-link keys exist; render/interpret keys supported by the UI product
contract and preserve unknown keys if the UI edits these objects.

HTTP `404 Not Found` means no profile currently exists for the authenticated
identity. This can occur if provisioning through Auth has not completed or
failed. Do not silently create an identity or substitute another user's UUID;
show an appropriate state and follow the platform's registration/recovery
experience.

## `PUT /api/v1/profiles/me`

Send JSON with `Content-Type: application/json`. The request has this shape:

```json
{
  "displayName": "Ada",
  "bio": "Engineer",
  "avatarUrl": "https://images.example.test/ada.png",
  "preferences": {
    "theme": "dark"
  },
  "socialLinks": {
    "website": "https://example.test"
  }
}
```

The `preferences` and `socialLinks` properties are required and must be JSON
objects, including when empty. Each update replaces all mutable profile fields;
it is not a partial patch. The nullable text fields are also required by the
current decoder in practice: when omitted, they decode as `None` and therefore
clear their stored values. Send all three fields explicitly, using `null` to
clear a value, to avoid accidental data loss.

Validation rules:

- `displayName`: string or `null`, maximum 100 Unicode code points.
- `bio`: string or `null`, maximum 500 Unicode code points.
- `avatarUrl`: string or `null`; if a string, maximum 2048 Unicode code points
  and must be an absolute HTTPS URL with a host and no user information.
- `preferences`: required JSON object.
- `socialLinks`: required JSON object.

The Profile Service validates URL shape, not that the remote image exists, is
safe to render, or has an allowed content type. Apply the UI's image-loading
and content-security policies separately.

On success, HTTP `200` returns the updated full profile in the same shape as
the GET response. Use this response to refresh the UI's profile state.

Validation failure returns HTTP `400`, usually with:

```json
{
  "error": "invalid_update",
  "details": [
    {
      "field": "displayName",
      "message": "must be at most 100 characters"
    }
  ]
}
```

Malformed JSON or a body that does not match the request schema returns HTTP
`400` with `error: "invalid_request"` and a message. The UI should associate
field-level `invalid_update.details` with controls where possible, while still
handling unknown fields/errors gracefully.

HTTP `404` means the authenticated user's profile does not exist. HTTP `401`
means the bearer token is missing or invalid. Other server/network failures
should use the UI's standard retry/error experience; do not report a failed
save as successful. Repeating the same PUT body replaces the same profile
values, but still sends a database update and may advance `updatedAt`.

## `GET /api/v1/profiles/{profileId}`

The `profileId` path segment must be a standard hyphenated UUID. No Bearer token
is required. Example:

```http
GET /api/v1/profiles/8c0c0b42-ff2a-4d68-b90f-3b845ca8106a
```

HTTP `200` response:

```json
{
  "userId": "8c0c0b42-ff2a-4d68-b90f-3b845ca8106a",
  "displayName": "Ada",
  "bio": "Engineer",
  "avatarUrl": "https://images.example.test/ada.png",
  "createdAt": "2026-10-18T12:00:00Z"
}
```

The public response intentionally excludes `preferences`, `socialLinks`, and
`updatedAt`. Unset nullable text values are JSON `null`. A malformed UUID
returns HTTP `400` with `error: "invalid_profile_id"`; a syntactically valid UUID
without a profile returns HTTP `404` with `error: "profile_not_found"`.

## Common status handling

| Status | Meaning | UI behavior |
|---|---|---|
| `200` | Request succeeded | Read the response body and update UI state |
| `400` | Invalid UUID, request JSON, or field values | Show actionable input feedback; do not blindly retry |
| `401` | Missing/invalid/expired Bearer token on `/me` | Follow Auth session renewal/logout flow |
| `404` | Profile absent | Show not-found/provisioning state; do not invent a profile ID |
| `5xx` or network failure | Service, database, gateway, or transport failure | Show recoverable error; retry reads cautiously and saves only according to UI policy |

Errors use JSON when produced by the application routes, but gateways or
infrastructure may return a different error body. The UI should not assume
every failure response is valid JSON.

## UI integration checklist

- [ ] Inspect existing UI API-client, Auth token, environment configuration,
      routing, form, and error-handling conventions before implementation.
- [ ] Confirm deployed API origin, optional gateway prefix, HTTPS, CORS, and
      credential/header behavior for local, test, staging, and production.
- [ ] Use the Auth Service's issued access token for `/me`; implement no
      independent identity source or token issuance.
- [ ] Load/update only `/me` for the signed-in user's private profile; use the
      public endpoint only when displaying another user's public profile.
- [ ] Treat PUT as replacement: submit all nullable fields and both JSON
      objects, explicitly using `null` to clear nullable strings.
- [ ] Map server validation errors to the form and handle unknown errors safely.
- [ ] Handle `401`, `404` (including profile provisioning delay/failure), and
      service/network failures without leaking credentials.
- [ ] Apply client-side validation for usability, while treating server
      validation as authoritative.
- [ ] Sanitize/render social links and avatar URLs according to UI security
      policy; server HTTPS validation alone does not establish content safety.
- [ ] Add UI-side tests for success/error states, validation display, token
      expiry, missing profiles, and request serialization using the UI repo's
      established test tools.

## Decisions to confirm with the UI/Auth/platform owners

The REST implementation establishes the data contract above, but these
deployment/product details are not specified by this service:

- Actual API origin and whether a gateway adds a path prefix.
- Whether browser calls are same-origin or cross-origin and where CORS is
  configured.
- Auth Service token refresh/expiry interaction and UI token-storage pattern.
- Desired UI behavior while Auth has committed an identity but profile
  provisioning is incomplete.
- Which preference/social-link keys the UI owns and how unknown keys should be
  retained.
- User-facing copy and navigation for validation errors, unavailable service,
  and missing profiles.

Do not block ordinary API-client scaffolding on visual/design decisions, but
resolve the deployment and Auth/session details before end-to-end integration.

## Related Profile Service references

- [`context.md`](./context.md) — full project blueprint and implemented phase
  status.
- [`src/main/scala/com/devopswithprashant/service/profile/DwpProfileServiceRoutes.scala`](./src/main/scala/com/devopswithprashant/service/profile/DwpProfileServiceRoutes.scala) —
  HTTP routes and status mapping.
- [`src/main/scala/com/devopswithprashant/service/profile/ProfileApi.scala`](./src/main/scala/com/devopswithprashant/service/profile/ProfileApi.scala) —
  request decoder and public/private response shapes.
- [`src/main/scala/com/devopswithprashant/service/profile/ProfileService.scala`](./src/main/scala/com/devopswithprashant/service/profile/ProfileService.scala) —
  update validation limits and business errors.
- [`src/main/scala/com/devopswithprashant/service/profile/JwtAuthenticator.scala`](./src/main/scala/com/devopswithprashant/service/profile/JwtAuthenticator.scala) —
  accepted JWT claims and verification rules.
