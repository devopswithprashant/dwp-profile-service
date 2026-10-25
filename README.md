# DWP Profile Service

Scala 3 profile service with PostgreSQL persistence, REST and gRPC APIs,
JWT validation, structured JSON logs, and Prometheus metrics.

## Build and test

Requires Java 21 and sbt 1.13.0.

```bash
printf 'PostgreSQL test password: '
read -s PROFILE_TEST_DB_PASSWORD
printf '\n'
export PROFILE_TEST_DB_PASSWORD
sbt clean coverage test coverageReport
```

The PostgreSQL integration suite connects directly to a dedicated local test
database; it does not use Testcontainers. Set `PROFILE_TEST_DB_PASSWORD` to the
password for `profileuser_test` before running the command. The test connection
defaults to `localhost:5432`, database `profiledb_test`, and user
`profileuser_test`; override these with `PROFILE_TEST_DB_HOST`,
`PROFILE_TEST_DB_PORT`, `PROFILE_TEST_DB_NAME`, and `PROFILE_TEST_DB_USER` if
needed. The test database must be dedicated to integration tests because the
suite applies Flyway migrations and writes test profiles.

Integration test database variables:

| Variable | Required | Default |
|---|---:|---|
| `PROFILE_TEST_DB_HOST` | No | `localhost` |
| `PROFILE_TEST_DB_PORT` | No | `5432` |
| `PROFILE_TEST_DB_NAME` | No | `profiledb_test` |
| `PROFILE_TEST_DB_USER` | No | `profileuser_test` |
| `PROFILE_TEST_DB_PASSWORD` | Yes | — |

The GitHub Actions workflow supplies its own PostgreSQL service for the test
job. The build enforces a minimum 90% statement coverage.

## Build the Docker image

Docker builds a single assembly JAR in a Scala/sbt builder stage and links a
trimmed Java 21 runtime for the modules used by this service. The final image
uses a non-root Distroless base and excludes the build environment, source,
build tools, and unused JDK modules.

```bash
docker build -t dwp-profile-service:local .
```

## Run locally

Set the same `JWT_SECRET` used by the Auth Service if the service must accept
tokens issued by it. HS384 secrets must contain at least 48 UTF-8 bytes.
Generate a temporary local-only value with `openssl rand -base64 48` when
testing without Auth Service tokens.

```bash
docker run --rm --name dwp-profile-service \
  -p 8080:8080 -p 9090:9090 \
  -e DB_HOST=host.docker.internal \
  -e DB_PORT=5432 \
  -e DB_NAME=profiledb \
  -e DB_USER=profileuser \
  -e DB_PASSWORD \
  -e JWT_SECRET \
  dwp-profile-service:local
```

Export `DB_PASSWORD` and `JWT_SECRET` in the invoking shell before running the
command. `host.docker.internal` is available by default on Docker Desktop; on
Linux, configure a host-gateway mapping or use the database container's
reachable hostname. The database must accept connections from the container.

### Runtime configuration

These variables configure the running service, including production. They are
separate from the `PROFILE_TEST_DB_*` variables above; do not point the
integration suite at the production database.

| Variable | Required | Default |
|---|---:|---|
| `DB_USER` | Yes | — |
| `DB_PASSWORD` | Yes | — |
| `JWT_SECRET` | Yes | — |
| `DB_HOST` | No | `localhost` |
| `DB_PORT` | No | `5432` |
| `DB_NAME` | No | `profiledb` |
| `SERVER_HOST` | No | `0.0.0.0` |
| `SERVER_PORT` | No | `8080` |
| `GRPC_PORT` | No | `9090` |
| `APP_ENV` | No | `development` |

The service applies Flyway migrations on startup. It exposes `/health/live`,
database-aware `/health`, and Prometheus `/metrics` over HTTP. Restrict
`/metrics` to the trusted Prometheus network path in the deployment.

## GitHub Actions and Docker Hub

`.github/workflows/build.yml` runs the Scala compile and test jobs. After those
pass on `main`, it reads the project version from sbt, builds the Docker image,
and publishes it to Docker Hub tagged with `<version>-<commit-sha>`, the
commit SHA, and `latest`.

Configure these GitHub repository settings:

| Setting | Type | Example / placeholder |
|---|---|---|
| `DOCKERHUB_USERNAME` | Actions variable | Your Docker Hub username or organization |
| `DOCKERHUB_REPOSITORY` | Actions variable | `dwp-profile-service` (optional; this is the default) |
| `DOCKERHUB_TOKEN` | Actions secret | A Docker Hub access token with write access |

Create the public Docker Hub repository before the first publish. The image
will be available as
`docker.io/<DOCKERHUB_USERNAME>/<DOCKERHUB_REPOSITORY>:<tag>`.
Kubernetes manifests, GitOps, and Argo CD deployment are intentionally not
included yet.