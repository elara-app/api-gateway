# api-gateway — agent guide

Instructions for coding agents working on this repository.

## Read first

- [README.md](README.md) — purpose, API/routing contract, verification steps.
- [centralized-configuration/api-gateway-dev.yml](https://github.com/elara-app/centralized-configuration/blob/main/api-gateway-dev.yml) — the actual route table, locator, Swagger URLs, actuator exposure, Eureka settings. It lives outside this repo; treat it as the source of truth for routing.
- [infrastructure/README.md](https://github.com/elara-app/infrastructure#readme) — full-stack startup, ports, and verification.
- [infrastructure/compose.yml](https://github.com/elara-app/infrastructure/blob/main/compose.yml) — how the gateway is built, configured, and health-checked.

## Scope and boundaries

- This repo is a thin WebFlux edge: routing, service lookup, Swagger UI, actuator, access logging. Only two production classes exist.
- Never add domain logic, persistence, database access, business validation, or service-specific code paths (for example special-casing `/item` or `/status`).
- Do not define application routes, controllers, route beans, or backend clients here. Routes belong in `centralized-configuration`.
- Do not add security, TLS, CORS, rate limiting, or resilience policy without an explicit decision (see [Stop and ask](#stop-and-ask)).
- Keep `src/main/resources/application.yml` limited to bootstrap properties: service name, config import, profile, fail-fast/retry.

## Quick facts

| Fact | Value |
|---|---|
| Maven coordinates | `com.elara.app:api-gateway:1.3.1` |
| Service ID | `api-gateway` |
| Package / main class | `com.elara.app.api_gateway` / `ApiGatewayApplication` |
| Port | `${SERVER_PORT:8080}` from `centralized-configuration/api-gateway-dev.yml`; not defined in this repo |
| Runtime dependencies | Config Client, Eureka client, LoadBalancer, Actuator, springdoc WebFlux UI, spring-retry + AOP |
| Datastore | None |
| Health | `/actuator/health` (not a readiness probe) |

## Commands

```bash
sh ./mvnw test                                            # 20 tests
sh ./mvnw test -Dtest=AccessLogFilterTest                 # 19 unit tests
sh ./mvnw test -Dtest=ApiGatewayApplicationTests          # 1 context-load test
sh ./mvnw -B clean verify -Dspring.profiles.active=test   # CI command
```

The wrapper is mode 644: `./mvnw` fails with permission denied. Always call `sh ./mvnw`; do not make the wrapper executable or instruct `chmod +x`. Full-stack run and verify live in [infrastructure/README.md](https://github.com/elara-app/infrastructure#readme).

## Repository map

| Path | Role |
|---|---|
| `src/main/java/com/elara/app/api_gateway/ApiGatewayApplication.java` | Boot entry point; no custom configuration. |
| `src/main/java/com/elara/app/api_gateway/config/AccessLogFilter.java` | `WebFilter`; one access line per request; actuator skip; level policy. |
| `src/main/resources/application.yml` | Bootstrap only: service name, optional config import, `dev` profile, fail-fast/retry. |
| `src/main/resources/application-test.yml` | Disables Config Client and Eureka for tests/CI. |
| `src/test/java/com/elara/app/api_gateway/ApiGatewayApplicationTests.java` | Context-load test. |
| `src/test/java/com/elara/app/api_gateway/config/AccessLogFilterTest.java` | 19 access-log unit tests. |
| `Dockerfile` | Multistage build; non-root UID 10001; `EXPOSE 8080`. |
| `.github/workflows/test.yml` | PR tests through the pinned reusable workflow. |
| `.github/workflows/publish-image.yml` | Publishes `ghcr.io/elara-app/api-gateway` on pushes to `main`. |

## Contracts and invariants

The routing contract is owned by `centralized-configuration/api-gateway-dev.yml`; never hardcode or "fix" routes in this repo. Invariants to preserve:

- Service IDs `inventory-service` and `unit-of-measure-service`, public prefixes `/api/v1/inventory/**` and `/api/v1/uom/**`, and `StripPrefix=3` on both routes. The full mapping table is in the [README](README.md#api).
- Discovery locator enabled with `lower-case-service-id: true` and an `include-expression` restricted to the two business service IDs. It is a case-insensitive `contains` filter, not an exact allowlist.
- Swagger URLs `/api/v1/inventory/v3/api-docs` and `/api/v1/uom/v3/api-docs` are client-facing. Changes are breaking.
- Keep trailing slashes in every example: `/api/v1/inventory/item/`, `/api/v1/uom/status/`.

Access-log invariants (`AccessLogFilter`):

- Exactly one line per request; requests that match no route are logged too.
- Skip exactly `/actuator` and every path under `/actuator/`; do not broaden or narrow the skip list.
- Never log query strings, headers, cookies, bodies, user agent, or remote address.
- Levels: 2xx/3xx INFO, 4xx WARN, 5xx ERROR, disconnect/cancellation WARN. Keep `Ordered.HIGHEST_PRECEDENCE`.

Reactor/WebFlux conventions: stay non-blocking; never call `.block()` in production code; compose with operators and return the chain; use `Mono<Void>` filter signatures. The only production pipeline is `AccessLogFilter.filter`.

Test expectations: 20 tests total; keep both classes green. `AccessLogFilterTest` is a plain unit test using a Logback `ListAppender` — follow that pattern for filter changes instead of adding a Spring context.

## Configuration and runtime

- `application.yml` uses `spring.config.import: optional:configserver:${CONFIG_URI:http://localhost:8888}`, `spring.profiles.active: dev`, `spring.cloud.config.fail-fast: false`, and a declared retry block (1500/10000 ms, 6 attempts, 1.5 multiplier).
- The import is optional: a missing Config Server never blocks startup — the app runs with no routes. Do not describe a hard Config Server dependency.
- Port, routes, locator, Swagger, actuator exposure, and Eureka come from `centralized-configuration/api-gateway-dev.yml`. The gateway has only a `dev` profile file.
- Environment variables: `CONFIG_URI`, `EUREKA_URI`, `SERVER_PORT`. Compose also injects `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75`.
- No refresh path is wired into this service; config changes require a gateway restart.
- With config loaded but no discovery or backend instances, `lb://` has no target: requests return 503 while `/actuator/health` stays `UP`.

## Testing and quality gates

- Before handing off, run `sh ./mvnw -B clean verify -Dspring.profiles.active=test`. That is exactly what CI runs on Java 21 for pull requests to `develop` and `main`.
- `ApiGatewayApplicationTests` (1 test) loads the context with the `test` profile; `AccessLogFilterTest` (19 tests) covers the log branches without a Spring context.
- No JaCoCo, Checkstyle, SpotBugs, or Sonar gate exists; `verify` is compile + tests + package. Do not add coverage thresholds or claim coverage numbers.
- There are no routing integration tests. Never describe route, `StripPrefix`, or locator behavior as test-covered.

## Definition of done

- `sh ./mvnw -B clean verify -Dspring.profiles.active=test` passes.
- Behavior changes are reflected in `README.md` (API/routing tables) and this file (invariants/traps); no stale commands or class names remain.
- Route, prefix, service ID, or Swagger changes are validated against `centralized-configuration` and the README table is updated.
- No domain logic, persistence, or service-specific code path is added; the diff touches only intended files.
- Links in both documents resolve.

## Stop and ask

- Changing the route contract: prefixes, service IDs, `StripPrefix=3`, locator expression, or Swagger URLs. These are client-facing and span repos.
- Adding authentication/authorization, TLS, CORS, rate limiting, retries, circuit breakers, or timeouts.
- Editing `centralized-configuration`, `infrastructure`, Compose, or CI workflows — cross-repo change.
- Changing access-log format or levels, or adding logged fields; treat the format as a contract.
- Adding dependencies, profiles, or actuator endpoints.

## Known traps

- Optional config import: without Config Server the gateway starts but routes are absent, so gateway paths return 404. It does not fail startup, and the retry block does not make startup wait or fail.
- `/actuator/health` reports `UP` even with no routes and no backend instances. Never use it as proof that routing works.
- `/actuator/gateway/routes` returns 404 even though `gateway` is in the exposure list; the endpoint is not enabled.
- Trailing slash matters downstream: `/api/v1/inventory/item` maps to `/item` and returns 500; `/api/v1/uom/status` returns 400. Use the trailing slash.
- The locator also exposes `/{service-id}/**` routes with the framework default `StripPrefix=1`; it is a `contains` filter, not an exact allowlist.
- `./mvnw` is not executable (mode 644); use `sh ./mvnw`.
- `eureka.instance.metadata-map.version: 1.0.0` in `api-gateway-dev.yml` is stale; the artifact version is `1.3.1` from `pom.xml`.
- Published image tags are `latest` and `sha-<7 chars>`; CI does not publish a `1.3.1` tag.
- Config changes in `centralized-configuration` do not hot-reload this service; restart is required.

## Documentation maintenance

- README owns the routing/API contract, run/verify steps, and troubleshooting. AGENTS owns commands, invariants, stop conditions, and traps. Do not duplicate the full route mapping table here; link to the [README](README.md#api).
- Update both files when the route table, port source, access-log policy, tests, or CI commands change.
- Documentation work must not edit code, resources, or `centralized-configuration` route files without an explicit request.
