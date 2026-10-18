# Auth Service Context — User Profile Provisioning Integration

## Purpose

Use this document as the integration handoff when implementing the Kotlin Auth
Service's connection to the Scala User Service. It captures the existing
architecture decisions and the User Service contract relevant to profile
provisioning and JWT compatibility.

The Auth Service owns identity, credentials, roles, and token issuance. The User
Service owns profile data. Each service writes only to its own database; do not
add cross-service database access or cross-database foreign keys.

## Auth Service assumptions

- The Auth Service is Kotlin with Spring Boot.
- It generates the user's UUID and remains authoritative for that identity.
- It owns authentication data and issues JWTs.
- The User Service's gRPC server is the internal API for creating a default
  profile.

The Auth Service source tree is not part of this repository. Verify its current
framework, build setup, and registration flow before changing its code.

## Registration and provisioning flow

1. Validate the registration request.
2. In the Auth Service's normal database transaction, create the identity and
   persist its UUID.
3. Commit that transaction.
4. From the Auth Service's application/service layer, call
   `CreateDefaultProfile` with the exact persisted UUID. Do not make this
   network call from inside the database transaction.
5. Apply the registration response and recovery behavior selected by the Auth
   Service's product/operations owners.

Call provisioning only after the identity transaction commits. Do not hold an
Auth database transaction open while making the network call. Do not generate a
second UUID for the profile.

The User Service operation is idempotent: retrying for an existing UUID returns
the existing profile and does not overwrite customized profile fields. This
makes retries safe from the profile-data perspective, including when a deadline
or connection failure leaves the client unsure whether the first call reached
or completed on the server. Always retry with the same UUID.

The registration failure policy is intentionally **not decided** here. A
synchronous call does not make the two database commits atomic. The Auth
Service team/product owner must choose whether registration reports failure
after identity commit, returns success with provisioning pending, or follows
another explicit contract. If the Auth API reports failure after commit,
subsequent duplicate registration attempts must be handled deliberately (for
example, retry provisioning for the existing identity rather than creating a
different identity or falsely reporting an ordinary duplicate-email error).

## gRPC contract

The source of truth is
[`src/main/protobuf/user_profile.proto`](./src/main/protobuf/user_profile.proto):

- Package: `dwp.userservice.v1`
- JVM package option: `com.devopswithprashant.user.v1`
- Service: `UserProfileService`
- RPC: `CreateDefaultProfile`
- Request: `CreateDefaultProfileRequest { string user_id = 1; }`
- Response: `CreateDefaultProfileResponse { string user_id = 1; }`
- Both `user_id` values carry the canonical string representation of the Auth
  user's UUID.
- Fully qualified RPC path:
  `/dwp.userservice.v1.UserProfileService/CreateDefaultProfile`

The protobuf field is snake_case on the wire and generated client property
naming depends on the chosen generator. The server parses a UUID and compares
its normalized string without case sensitivity: hyphenless/otherwise malformed
values are rejected, while uppercase hexadecimal UUID input is accepted and
the response is normalized to the UUID library's lowercase string form. Send
the standard UUID string (`xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`) to avoid
generator/client discrepancies.

Generate a Kotlin/Java gRPC client from this shared proto using tooling
compatible with the Auth Service's build. Do not hand-maintain a duplicate
contract. The User Service uses fs2-grpc/ScalaPB internally; the Auth Service
should generate and consume JVM client stubs appropriate to its Kotlin stack.
Check the Auth Service's existing Gradle/Maven plugins and gRPC dependencies
before selecting a generator, and keep generated sources/build outputs out of
handwritten source control unless that repository's conventions require
otherwise. Verify generated package, method name, and wire compatibility with
the RPC path above.

The User Service binds its gRPC listener to `GRPC_PORT`, default `9090`.
Configure the Auth Service's target address and port through environment or
service configuration; do not assume `localhost` outside a local development
setup. Confirm the deployed service-discovery name, network access, and
transport security before deployment.

The current User Service server does not configure gRPC TLS or authenticate
callers. Treat it as a trusted-network-only endpoint until both sides agree on
and implement service authentication and transport protection; do not expose
the plaintext listener to an untrusted network. JWT bearer tokens used by
frontend REST calls are not gRPC client authentication and should not be
forwarded as a substitute.

### Current server error behavior

| gRPC status | Meaning | Auth Service handling |
|---|---|---|
| `INVALID_ARGUMENT` | `user_id` is not a canonical UUID | Treat as a caller/programming error; do not retry unchanged input |
| `UNAVAILABLE` | Profile database is unavailable | Transient failure; retry under a bounded policy |
| `INTERNAL` | Other profile creation failure | Record/alert and follow the recovery policy; retry only when safe |
| `OK` | Profile exists or was created | Provisioning succeeded |

The response contains the normalized `user_id`; verify it matches the UUID
requested. The method returns no profile contents.

Also handle client-side outcomes:

- `DEADLINE_EXCEEDED`, connection reset, or transport loss can be ambiguous:
  the server may have committed the profile but the response did not arrive.
  Retry only with the same UUID; server idempotency makes this safe.
- `UNAVAILABLE` is explicitly used for caught SQL exceptions and is
  retryable with bounded backoff.
- `INTERNAL` can represent a non-transient programming/data failure; capture
  diagnostics and alert. Do not retry indefinitely. If policy retries it,
  keep attempts bounded and reuse the same UUID.
- Do not retry `INVALID_ARGUMENT` without correcting the UUID/input.
- Treat other terminal statuses such as `UNAUTHENTICATED` or `PERMISSION_DENIED`
  as configuration/security failures, not transient errors.

The current RPC has no authentication metadata, custom options, or explicit
deadline contract. Before exposing it beyond a trusted internal network, agree
on service-to-service authentication and transport security. Set a finite
client deadline and bounded retry policy in the Auth Service; select their
values based on deployment latency and operational requirements rather than
embedding unreviewed constants.

Configure the target, deadline, and retry controls outside source code (for
example, through the Auth Service's existing configuration system). Include
the target and chosen timeout/retry values in deployment documentation, but
never log credentials, JWT signing material, or full sensitive registration
payloads. Log the Auth user UUID/correlation identifier and gRPC status so an
operator can reconcile pending provisioning.

## JWT compatibility

The User Service validates tokens using these claims:

- Algorithm: `HS384`
- Issuer: `devopswithprashant-auth`
- Audience: `devopswithprashant-platform`
- Subject (`sub`): canonical UUID of the Auth identity
- Expiration (`exp`): required and must be in the future
- Issued-at (`iat`): if supplied, must not be in the future
- Not-before (`nbf`): must not be in the future when supplied

The User Service requires `JWT_SECRET` from its runtime environment and
currently requires at least 48 UTF-8 bytes for HS384. The Auth Service and User
Service must use the same secret, provisioned through the platform's approved
secret-management mechanism. Never place the secret in source code, this
document, logs, or checked-in configuration. A future switch to asymmetric
signing is a separate coordinated change.

This signing secret is only for JWT compatibility; it does not secure the
gRPC provisioning call. Confirm that Auth token issuance emits the exact
issuer, audience, UUID subject, and required expiration accepted by the User
Service. Avoid logging bearer tokens or signing secrets.

## Cross-service failure and recovery

The Auth and User databases are independent. Committing the Auth identity and
creating its profile is not one atomic transaction. The selected initial
integration is synchronous gRPC; RabbitMQ is not part of the current design.

The exact product behavior when identity creation succeeds but provisioning
fails is not yet specified. Decide this before finalizing registration behavior:

- Should registration fail to the caller even though the identity is already
  committed?
- Should registration succeed while profile provisioning is retried
  asynchronously?
- What durable state identifies provisioning as pending or failed?
- How are retries scheduled, bounded, observed, and eventually escalated?
- If compensation is considered, what happens to the committed identity and
  any already-issued tokens?

Also decide whether any provisioning-pending state belongs in the Auth
database/domain model, what duplicate-registration/login behavior is while
provisioning is pending, and what operational signal marks the identity fully
provisioned. These decisions are Auth Service behavior; they do not require a
User Service code change unless the RPC contract proves insufficient.

Do not imply that rolling back the Auth transaction is possible after commit.
At minimum, make retries use the original UUID, preserve idempotency, log
actionable correlation identifiers without secrets, and expose failures to
monitoring. If recovery must survive process restarts, use durable retry state
(for example, an outbox or provisioning record) rather than relying solely on
an in-memory retry.

## Implementation checklist

- [ ] Inspect the Auth Service repository's current registration transaction,
      identity model, configuration, and generated gRPC conventions before
      implementation.
- [ ] Share and generate stubs from the User Service proto; verify Kotlin
      interoperability and the generated RPC path.
- [ ] Add configurable User Service target, finite RPC deadline, and bounded
      retry/backoff policy; handle ambiguous deadlines/transport failures
      idempotently.
- [ ] Invoke profile provisioning after successful identity commit using the
      original Auth UUID.
- [ ] Decide and implement registration, duplicate-registration, login, and
      token behavior when provisioning fails after identity commit.
- [ ] Implement durable recovery if retry must survive Auth Service restarts.
- [ ] Ensure retrying `CreateDefaultProfile` cannot reset an existing profile.
- [ ] Configure the shared HS384 secret securely and issue the documented
      issuer, audience, subject, and time claims.
- [ ] Confirm a trusted network boundary or coordinate gRPC TLS and
      service-to-service authentication before non-local deployment.
- [ ] Add Auth-repository tests for successful provisioning, ordering after
      commit, identity-commit/provisioning-failure behavior, timeout/ambiguous
      outcomes, retries/backoff, duplicate registration, durable recovery,
      response UUID validation, and JWT claim compatibility.
- [ ] Verify service discovery, network policy, and the documented operational
      configuration for each deployment environment.

## Related User Service references

- [`context.md`](./context.md) is the broader project blueprint and source of
  architecture decisions.
- [`src/main/protobuf/user_profile.proto`](./src/main/protobuf/user_profile.proto)
  is the profile-provisioning contract.
- [`src/main/scala/com/devopswithprashant/service/user/UserProfileGrpcService.scala`](./src/main/scala/com/devopswithprashant/service/user/UserProfileGrpcService.scala)
  implements the RPC and maps failures to gRPC statuses.
- The User Service gRPC listener defaults to port `9090`; its JWT configuration
  is named `JWT_SECRET`.
