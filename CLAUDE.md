# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Authgear is an open-source authentication-as-a-service platform. This repo contains the core server, Admin API (GraphQL), Portal (management UI), and AuthUI (end-user authentication pages).

Module: `github.com/authgear/authgear-server` (Go 1.25.5)

## Build & Run (Quick Reference)

```sh
# Build frontend (authui + portal)
make authui portal

# Run main/auth server
make start

# Run portal server
make start-portal

# Build binaries
make build TARGET=authgear BIN_NAME=./authgear

# Run portal frontend dev server
cd portal && npm start

# Run authui dev server (HMR)
make authui-dev
```

## Testing, Linting, Formatting

```sh
# Run all tests (with 90s timeout)
make test

# Run tests for a specific package
go test ./pkg/lib/authenticationflow/... -timeout 1m30s

# Run a single test
go test ./pkg/some/package/... -run TestName

# Lint (requires golangci-lint v2.5.0)
make lint

# Format code (requires goimports)
make fmt

# Verify code generation is current
make check-tidy
```

Tests use `github.com/smartystreets/goconvey` (Convey BDD style). Look at existing `*_test.go` files in the package for conventions. Always run `go test` after making changes.

## High-Level Architecture

### Three Binaries

- **`cmd/authgear`** — Main server. `start main` (auth/web on :3000), `start resolver` (session resolver on :3001), `start admin` (GraphQL Admin API on :3002). Also handles database migrations, background jobs.
- **`cmd/portal`** — Portal management server (SaaS admin UI).
- **`cmd/once`** — Combined binary for simplified deployment.

### Dependency Injection (Google Wire)

All dependency injection uses `github.com/google/wire` with compile-time code generation.

- `wire.go` files (with `//go:build wireinject`) define injection functions.
- `wire_gen.go` files are generated output — never edit directly.
- Wire sets are composed hierarchically: each package exports a `DependencySet` that other packages import.

To regenerate wire files: `go generate ./pkg/... ./cmd/...`

### Three-Tier Provider Hierarchy

Defined in `pkg/lib/deps/providers.go`:

1. **`RootProvider`** — Created once at startup. Holds global singletons: DB pool, Redis pool, Sentry, JWK cache, resource manager, environment config.
2. **`AppProvider`** — Per-app (per-tenant), created per request by `RequestMiddleware`. Holds app-scoped DB handles and the app's config.
3. **`RequestProvider`** — Per HTTP request. Holds `*http.Request`, `http.ResponseWriter`, remote IP, etc.

`deps.RequestMiddleware` is the critical glue: it resolves the app from the Host header, creates an `AppProvider`, and stores it in context. Handlers retrieve it via `deps.GetAppProvider(ctx)`.

### Request Routing

Each service defines a `NewRouter()` returning `http.Handler`. Routes use `pkg/util/httproute.NewRouter()`. Middleware chains: panic recovery, Sentry, CORS, dynamic CSP, CSRF, rate limiting, OTel. The `ImplementationSwitcherMiddleware` dispatches between legacy (`interaction`) and new (`authflowv2`) auth UIs based on app config.

### Config System

- **Server config**: Loaded from env vars via `envconfig` (`cmd/authgear/server/config.go`).
- **App config per tenant**: Three YAML files — `authgear.yaml` (AppConfig), `authgear.secrets.yaml` (SecretConfig), `authgear.features.yaml` (FeatureConfig).
- **Config source**: Local filesystem (`local_fs`, default) or PostgreSQL database (`database`, multi-tenant mode), selected by `CONFIG_SOURCE_TYPE` env var.
- Config types live in `pkg/lib/config/`.

### Two Authentication Flow Engines

1. **`pkg/lib/authenticationflow/`** (newer, "authflow") — Declarative, JSON-config-driven. Flows are configured in app config as trees of steps. Core loop in `accept.go` (`doAccept`): find deepest `InputReactor`, match input via `InputSchema`, produce nodes with `ReactTo()`. Nodes form a tree with sub-flows. Flow state stored in Redis. Effects: `RunEffect` (immediate, e.g., send OTP) vs `OnCommitEffect` (deferred until flow completion in a DB transaction). Concrete implementations in `declarative/`.

2. **`pkg/lib/interaction/`** (legacy) — Imperative graph of Intents → Nodes → Edges. Still in active use. Being gradually replaced by authflow per-project via `UIImplementation` config.

3. **`pkg/latte/`** (experimental) — Newer third engine.

### Key Packages Under `pkg/`

| Package | Purpose |
|---|---|
| `api/` | Shared API types (Response, errors, models) |
| `auth/` | Main server: handlers, routes, Wire wiring |
| `admin/` | Admin API: GraphQL server, loaders, user import/export |
| `portal/` | Portal: management GraphQL API, billing, domains |
| `resolver/` | Lightweight session resolver service |
| `lib/deps/` | Core DI: providers, middleware, Wire sets |
| `lib/config/` | App config types and validation |
| `lib/authn/` | Auth primitives: user, identity, authenticator, OTP, MFA, SSO |
| `lib/oauth/` | Full OAuth 2.0 / OIDC implementation |
| `lib/session/` | User session management (IDP sessions, Redis storage) |
| `lib/feature/` | Features: forgot password, verification, passkey, captcha |
| `lib/hook/` | Webhook and Deno hook delivery |
| `lib/event/` | Event model and Redis Pub/Sub dispatch |
| `lib/infra/` | Infrastructure: DB, Redis, mail/SMS providers, middleware |
| `lib/web/` | Web UI: CSP, static assets, resource management |
| `lib/messaging/` | Email/SMS templating and sending |
| `lib/workflow/` | Event-driven workflow engine |
| `lib/facade/` | High-level facade orchestrating auth services |
| `lib/ratelimit/` | Rate limiting |
| `lib/lockout/` | Account lockout |
| `util/` | ~80+ utility packages (routing, logging, OTel, crypto, i18n, etc.) |

### Database

PostgreSQL with a custom migration system (`pkg/util/sqlmigrate/`). Migration SQL files are embedded in the binary.

```sh
# Create a new migration
go run ./cmd/authgear database migrate new my_migration_name

# Apply migrations
go run ./cmd/authgear database migrate up
```

### Translation System

Translation JSON files at `resources/authgear/templates/*/translation.json` (base language: `en`). To add a new key: add to `en` file, then run `make -C scripts/python generate-translations ANTHROPIC_API_KEY=<key>`.

To update an existing key: update English value, run `make translation-json-del-key KEY=the.key`, then regenerate.

### Comment Tags

- `FIXME`: Fix as soon as possible
- `TODO`: Do when someone really needs it
- `OPTIMIZE`: Do when it becomes a performance issue
- `SECURITY`: Known potential security issue

## Logging Guidelines

Use `log` from `slog` package. Severity: `Debug` (developer diagnostics), `Info` (normal events), `Warn` (unexpected but recoverable), `Error` (action needed). Keep messages stable — put variable data in attributes, not in the message string. No secrets/PII in logs.

## Custom Slash Commands

Available via `.claude/commands/`:
- `/add_go_test` — Add tests following package conventions
- `/add_portal_admin_api_query` — Add Admin API GraphQL query
- `/add_portal_admin_api_mutation` — Add Admin API GraphQL mutation
- `/update_go_version` — Update Go version across all config files
- `/update_important_modules` — Update key dependency modules
- `/update_vettedpositions` — Update `.vettedpositions` for goanalysis
