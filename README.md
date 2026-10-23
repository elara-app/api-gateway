[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/elara-app/api-gateway)

# api-gateway

`api-gateway` is the single client entry point of the Elara platform, built as a thin Spring Cloud Gateway (WebFlux) application. It owns edge routing, Eureka-based service lookup, Swagger UI hosting, actuator endpoints, and access logging. It does not own domain logic, persistence, authentication, or the inventory/UOM APIs it forwards to; the route table lives in `centralized-configuration`, not in this repository.

## At a glance

| Item | Value |
|---|---|
| Service ID | `api-gateway` |
| Runtime port | `8080` — `${SERVER_PORT:8080}` from `centralized-configuration/api-gateway-dev.yml`; Compose injects `SERVER_PORT` |
| Stack | Java 21, Spring Boot 3.5.6, Spring Cloud 2025.0.0, Spring Cloud Gateway on Netty/WebFlux, springdoc 2.8.0 |
| Datastore | None (no JDBC driver, no schema) |
| Dependencies | `config-service` (optional config import), `discovery-service` (Eureka), `inventory-service`, `unit-of-measure-service` |
| Health | `/actuator/health` — liveness-style, not a readiness probe |
| Image | `ghcr.io/elara-app/api-gateway:latest` (published demo stack) |

## Responsibilities and boundaries

- Routes `/api/v1/inventory/**` and `/api/v1/uom/**` to the two business services with `StripPrefix=3`.
- Resolves backend instances through Eureka with Spring Cloud LoadBalancer (`lb://` URIs).
- Hosts Swagger UI and proxies the per-service OpenAPI documents.
- Exposes `health` and `info` actuator endpoints and logs one access line per request.
- Does not own routes (they are configuration), backend contracts, data, validation, security, or resilience policy.

## Runtime architecture

The service is a Netty/WebFlux application with two production classes:

| Component | Role |
|---|---|
| `ApiGatewayApplication` | Spring Boot entry point; behavior comes from framework auto-configuration plus externalized configuration. |
| `config.AccessLogFilter` | `WebFilter` at `Ordered.HIGHEST_PRECEDENCE`; one log line per request. |

There are no controllers, route definitions, security configuration, CORS configuration, or custom error handlers. A request flows as follows:

1. Startup runs an optional Config Server import and loads `api-gateway-dev.yml` from `centralized-configuration` (see [Configuration](#configuration)).
2. The route table matches the path; `lb://` URIs are resolved through the Eureka registry.
3. The request is proxied to the selected instance and the response streams back unchanged.

Platform topology, ports, and startup order live in [infrastructure/README.md](https://github.com/elara-app/infrastructure#readme); the property and profile matrix lives in [centralized-configuration/README.md](https://github.com/elara-app/centralized-configuration#readme).

### Access logging

`AccessLogFilter` writes exactly one line per request:

```text
[access] GET /api/v1/inventory/item/ -> 200 (18 ms)
```

- Actuator paths are skipped: exactly `/actuator` and every path under `/actuator/` (so container healthchecks do not flood the log). Requests that match no route are still logged (gateway 404s appear).
- Query strings, headers, cookies, bodies, user agent, and remote address are intentionally omitted. Path variables can still contain identifiers.
- Levels: 2xx/3xx INFO; 4xx WARN; 5xx ERROR; client disconnect or cancellation WARN with `n/a` and a `cancelled`/`failed` marker.
- It is a log line, not an audit trail: no request IDs, latency histograms, or metrics.

## API

The gateway exposes no business API of its own, only the route table from `centralized-configuration/api-gateway-dev.yml`:

| Route ID | Predicate | Filter | URI |
|---|---|---|---|
| `inventory-service` | `Path=/api/v1/inventory/**` | `StripPrefix=3` | `lb://inventory-service` |
| `unit-of-measure-service` | `Path=/api/v1/uom/**` | `StripPrefix=3` | `lb://unit-of-measure-service` |

`StripPrefix=3` removes the first three path segments, so the fourth segment onward reaches the downstream controller:

| Gateway request | After StripPrefix | Downstream mapping |
|---|---|---|
| `/api/v1/inventory/item/` | `/item/` | `InventoryItemController` `@RequestMapping("item/")` |
| `/api/v1/uom/` | `/` | `UomController` `@RequestMapping("")` |
| `/api/v1/uom/status/` | `/status/` | `UomStatusController` `@RequestMapping("status/")` |

The gateway prefix is not the collection path: `/api/v1/inventory/` alone is not the inventory collection — `/api/v1/inventory/item/` is. Keep the trailing slash; `/api/v1/inventory/item` returns 500 and `/api/v1/uom/status` returns 400 because the downstream mappings include the slash.

The discovery locator is enabled (`lower-case-service-id: true`) with `include-expression: serviceId.toLowerCase().contains('inventory-service') || serviceId.toLowerCase().contains('unit-of-measure-service')`. This is a case-insensitive substring filter, not an exact two-service allowlist; it adds `/{service-id}/**` routes with the framework default `StripPrefix=1` (for example `/inventory-service/item/`).

### Swagger

- `/swagger-ui.html` returns 302 to `/webjars/swagger-ui/index.html`.
- Aggregated docs: inventory at `/api/v1/inventory/v3/api-docs`, UOM at `/api/v1/uom/v3/api-docs`. There is no single merged OpenAPI document.
- `/v3/api-docs` on the gateway is its own (empty) spec.
- The `gateway` actuator endpoint appears in the exposure list but is not enabled: `/actuator/gateway/routes` returns 404.

### Error behavior

- Unmatched path -> 404 JSON.
- `lb://` target with no registered instance -> 503 JSON (`{"status":503,"error":"Service Unavailable",...}`).
- No custom error body, retry, circuit breaker, or timeout policy is configured.

## Run and verify

Prerequisites: Java 21. The Maven wrapper is mode 644, so `./mvnw` fails with permission denied — always use `sh ./mvnw`.

```bash
sh ./mvnw test
sh ./mvnw -B clean verify -Dspring.profiles.active=test
```

`spring-boot:run` is not a standalone quickstart: without Config Server the app starts with no routes, and with Config Server but no backend instances every proxied call returns 503. Start the full stack as described in [infrastructure/README.md](https://github.com/elara-app/infrastructure#readme), then check the gateway-specific URLs:

```bash
curl -s http://127.0.0.1:8080/api/v1/inventory/item/ | jq '.page.totalElements'   # 10
curl -s http://127.0.0.1:8080/api/v1/uom/ | jq '.page.totalElements'            # 5
curl -s http://127.0.0.1:8080/api/v1/uom/status/ | jq '.page.totalElements'     # 3
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/swagger-ui.html  # 302
```

If the stack is up but backends have not registered yet, the data calls return 503 instead.

## Configuration

`src/main/resources/application.yml` is the entire local configuration:

- `spring.application.name: api-gateway`
- `spring.config.import: optional:configserver:${CONFIG_URI:http://localhost:8888}`
- `spring.profiles.active: dev`
- `spring.cloud.config.enabled: true`, `fail-fast: false`, with a declared retry block (initial 1500 ms, max 10000 ms, 6 attempts, multiplier 1.5)

| Startup situation | Result |
|---|---|
| Config Server reachable | Loads `api-gateway-dev.yml`: port, route table, locator, Swagger URLs, actuator exposure, Eureka settings. |
| Config Server missing | The `optional:` import and `fail-fast: false` let the app start anyway; no routes are loaded, so gateway paths return 404 and health still reports `UP`. The retry block does not block startup. |
| Config loaded, no discovery or no instances | Routes match, but `lb://` resolves no instance; requests return 503 while health still reports `UP`. |

Environment variables: `CONFIG_URI` (config server URL), `EUREKA_URI` (Eureka URL), `SERVER_PORT`. The `test` profile (`application-test.yml`) disables the Config Client and Eureka for tests and CI; it is not a production profile. The gateway has only a `dev` file in `centralized-configuration`; the full matrix is in [centralized-configuration/README.md](https://github.com/elara-app/centralized-configuration#readme).

## Testing and quality gates

| Class | Tests | Kind |
|---|---|---|
| `ApiGatewayApplicationTests` | 1 (`contextLoads`) | `@SpringBootTest` |
| `com.elara.app.api_gateway.config.AccessLogFilterTest` | 19 | Plain unit test with a Logback `ListAppender`; no Spring context |

20 tests total.

```bash
sh ./mvnw test -Dtest=AccessLogFilterTest
sh ./mvnw test -Dtest=ApiGatewayApplicationTests
sh ./mvnw -B clean verify -Dspring.profiles.active=test
```

CI runs `sh ./mvnw -B clean verify -Dspring.profiles.active=test` on Java 21 for pull requests to `develop` and `main`. Pushes to `main` rerun the tests and publish the image (`latest`, `sha-<7 chars>`, and per-architecture tags). There is no JaCoCo, Checkstyle, SpotBugs, or Sonar gate; `verify` is compile + tests + package.

## Current limitations

- No authentication, authorization, rate limiting, or TLS termination.
- No retry, circuit breaker, or timeout policy for downstream calls.
- No routing integration tests: one context-load test plus access-log unit tests; predicates, `StripPrefix`, and the locator are untested.
- `/actuator/health` is not a readiness probe: it returns `UP` with no config, no routes, or no backend instances. Prove routing with smoke calls.
- The `gateway` actuator endpoint is not enabled (`/actuator/gateway/routes` -> 404).
- No merged OpenAPI document; Swagger UI links the two per-service specs.
- Eureka metadata `version: 1.0.0` is stale; the artifact version is `1.3.1`.
- Route, locator, or Swagger changes in `centralized-configuration` require a gateway restart; no refresh flow (for example Spring Cloud Bus) is wired into this service.
- The source Compose stack publishes port 8080 on all interfaces; the published demo stack binds loopback by default.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `/api/v1/inventory/item/` -> 404 | Config Server not reached, so no routes are loaded | Start `config-service`, confirm it serves `api-gateway-dev.yml`, restart the gateway |
| `/api/v1/inventory/item` -> 500, `/api/v1/uom/status` -> 400 | Missing trailing slash | Use `/api/v1/inventory/item/` and `/api/v1/uom/status/` |
| Proxied call -> 503 | No instance registered in Eureka for the target service | Check `discovery-service` and backend registration/health |
| `/actuator/gateway/routes` -> 404 | Gateway actuator endpoint not enabled | Expected; read the route table from `centralized-configuration/api-gateway-dev.yml` |
| Health `UP` but calls fail | Health is not readiness | Smoke-test a route (see above) |
| No access log line for a healthcheck | Actuator paths are skipped by design | Expected |
| `./mvnw` -> permission denied | Wrapper mode is 644 | Use `sh ./mvnw` |

## Documentation

- [AGENTS.md](AGENTS.md) — commands, invariants, and traps for coding agents.
- [infrastructure/README.md](https://github.com/elara-app/infrastructure#readme) — full-stack startup, ports, and canonical verification.
- [centralized-configuration/README.md](https://github.com/elara-app/centralized-configuration#readme) — gateway route table, profile matrix, and property ownership.
- Swagger UI at `http://127.0.0.1:8080/swagger-ui.html` when the stack runs.
