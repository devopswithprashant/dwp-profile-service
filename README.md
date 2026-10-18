# DWP User Service

Scala 3 user-profile service with PostgreSQL persistence, REST and gRPC APIs,
JWT validation, structured JSON logs, and Prometheus metrics.

## Build and test

Requires Java 21 and sbt 1.13.0.

```bash
sbt clean coverage test coverageReport
```

The build enforces a minimum 90% statement coverage.

## Build the Docker image

Docker builds a single assembly JAR in a Scala/sbt builder stage and links a
trimmed Java 21 runtime for the modules used by this service. The final image
uses a non-root Distroless base and excludes the build environment, source,
build tools, and unused JDK modules.

```bash
docker build -t dwp-user-service:local .
```

## Run locally

Set the same `JWT_SECRET` used by the Auth Service if the service must accept
tokens issued by it. HS384 secrets must contain at least 48 UTF-8 bytes.
Generate a temporary local-only value with `openssl rand -base64 48` when
testing without Auth Service tokens.

```bash
docker run --rm --name dwp-user-service \
  -p 8080:8080 -p 9090:9090 \
  -e DB_HOST=host.docker.internal \
  -e DB_PORT=5432 \
  -e DB_NAME=profiledb \
  -e DB_USER=profileuser \
  -e DB_PASSWORD \
  -e JWT_SECRET \
  dwp-user-service:local
```

Export `DB_PASSWORD` and `JWT_SECRET` in the invoking shell before running the
command. `host.docker.internal` is available by default on Docker Desktop; on
Linux, configure a host-gateway mapping or use the database container's
reachable hostname. The database must accept connections from the container.

### Runtime configuration

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

## GitHub Actions and Amazon ECR

`.github/workflows/ci.yml` runs the test/coverage gate and builds the Docker
image for pull requests and pushes to `main`. It publishes `main` images to
ECR using GitHub Actions OIDC; no long-lived AWS keys are stored in GitHub.

Configure these GitHub repository settings before enabling publishing:

| Setting | Type | Example / placeholder |
|---|---|---|
| `AWS_REGION` | Actions variable | `us-east-1` — replace with the ECR region |
| `ECR_REPOSITORY` | Actions variable | `dwp-user-service` |
| `AWS_ROLE_TO_ASSUME` | Actions secret | `arn:aws:iam::<ACCOUNT_ID>:role/<GITHUB_ACTIONS_ECR_ROLE>` |

Create the ECR repository in the selected AWS account/region before the first
publish. Configure image scanning on push and mutable tags if using `latest`:

```bash
aws ecr create-repository \
  --repository-name dwp-user-service \
  --image-scanning-configuration scanOnPush=true \
  --image-tag-mutability MUTABLE \
  --region <AWS_REGION>
```

The assumed role needs `ecr:GetAuthorizationToken` on `*`, and these image
actions scoped to the repository: `ecr:BatchCheckLayerAvailability`,
`ecr:BatchGetImage`, `ecr:CompleteLayerUpload`, `ecr:InitiateLayerUpload`,
`ecr:PutImage`, and `ecr:UploadLayerPart`. Its OIDC trust policy should
restrict access to this GitHub repository and the `main` branch, with the
audience `sts.amazonaws.com`; for example, use the subject
`repo:devopswithprashant/dwp-user-service:ref:refs/heads/main`.

The workflow publishes both the commit SHA and `latest` tags. Update the
repository variable, role ARN, region, and branch trigger to match the actual
AWS account and GitHub configuration. Kubernetes/EKS manifests, GitOps, and
Argo CD deployment are intentionally not included yet.