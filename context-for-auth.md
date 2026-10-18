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
2. Create the Auth identity and commit its database transaction.
3. After commit, call the User Service's `CreateDefaultProfile` RPC with the
   exact UUID assigned to the Auth identity.
4. Handle the RPC result according to the service's registration/recovery
   policy.

Call provisioning only after the identity transaction commits. Do not hold an
Auth database transaction open while making the network call. Do not generate a
second UUID for the profile.

The User Service operation is idempotent: retrying for an existing UUID returns
the existing profile and does not overwrite customized profile fields. This
makes retries safe from the profile-data perspective.

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

Generate a Kotlin/Java gRPC client from this shared proto using tooling
compatible with the Auth Service's build. Do not hand-maintain a duplicate
contract. The User Service uses fs2-grpc/ScalaPB internally; the Auth Service
should generate and consume JVM client stubs appropriate to its Kotlin stack.

The User Service binds its gRPC listener to `GRPC_PORT`, default `9090`.
Configure the Auth Service's target address and port through environment or
service configuration; do not assume `localhost` outside a local development
setup. Confirm the deployed service-discovery name, network access, and
transport security before deployment.

### Current server error behavior

| gRPC status | Meaning | Auth Service handling |
|---|---|---|
| `INVALID_ARGUMENT` | `user_id` is not a canonical UUID | Treat as a caller/programming error; do not retry unchanged input |
| `UNAVAILABLE` | Profile database is unavailable | Transient failure; retry under a bounded policy |
| `INTERNAL` | Other profile creation failure | Record/alert and follow the recovery policy; retry only when safe |
| `OK` | Profile exists or was created | Provisioning succeeded |

The current RPC has no authentication metadata, custom options, or explicit
deadline contract. Before exposing it beyond a trusted internal network, agree
on service-to-service authentication and transport security. Set a finite
client deadline and bounded retry policy in the Auth Service; select their
values based on deployment latency and operational requirements rather than
embedding unreviewed constants.

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

Do not imply that rolling back the Auth transaction is possible after commit.
At minimum, make retries use the original UUID, preserve idempotency, log
actionable correlation identifiers without secrets, and expose failures to
monitoring. If recovery must survive process restarts, use durable retry state
(for example, an outbox or provisioning record) rather than relying solely on
an in-memory retry.

## Implementation checklist

- [ ] Share and generate stubs from the User Service proto; verify Kotlin
      interoperability and the generated RPC path.
- [ ] Add configurable User Service target, finite RPC deadline, and bounded
      retry policy.
- [ ] Invoke profile provisioning after successful identity commit using the
      original Auth UUID.
- [ ] Decide and implement registration behavior for provisioning failures.
- [ ] Implement durable recovery if retry must survive Auth Service restarts.
- [ ] Ensure retrying `CreateDefaultProfile` cannot reset an existing profile.
- [ ] Configure the shared HS384 secret securely and issue the documented
      issuer, audience, subject, and time claims.
- [ ] Verify service discovery, firewall/network policy, and gRPC transport
      security for each deployment environment.
- [ ] Add automated tests after the current testing deferral is lifted:
      successful registration, RPC failure, timeout, retries, idempotency,
      invalid UUID, and JWT claim compatibility.

## Related User Service references

- [`context.md`](./context.md) is the broader project blueprint and source of
  architecture decisions.
- [`src/main/protobuf/user_profile.proto`](./src/main/protobuf/user_profile.proto)
  is the profile-provisioning contract.
- [`src/main/scala/com/devopswithprashant/service/user/UserProfileGrpcService.scala`](./src/main/scala/com/devopswithprashant/service/user/UserProfileGrpcService.scala)
  implements the RPC and maps failures to gRPC statuses.
- The User Service gRPC listener defaults to port `9090`; its JWT configuration
  is named `JWT_SECRET`.
