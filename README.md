# API Gateway

Single entry point for client traffic in the Elara microservices platform, built with **Spring Cloud Gateway (WebFlux)**.

This service centralizes request routing and decouples clients from internal service topology. It delegates business logic to backend services and keeps transport-level concerns at the edge.

## What this service does

`api-gateway` is a Spring Boot application that:

- exposes a unified HTTP entrypoint for clients,
- resolves downstream services through service discovery,
- forwards requests to backend microservices based on runtime routing configuration.

The gateway is intentionally thin: no domain logic, no persistence, and no service-specific code paths.

## How it interacts with the rest of the platform

Primary related services:
- [inventory-service](https://github.com/elara-app/inventory-service.git)
- [unit-of-measure-service](https://github.com/elara-app/unit-of-measure-service.git)
- [config-service](https://github.com/elara-app/config-service.git)

### Interaction flow

1. A client calls `api-gateway`.
2. The gateway loads environment configuration from Config Server (`config-service`), which sources values from `centralized-configuration`.
3. The gateway resolves target instances via Eureka (`discovery-service`).
4. The request is proxied to the selected backend service (for example, inventory-service or unit-of-measure-service).
5. The backend response is returned through the gateway to the client.

This makes client integrations stable while allowing backend services to scale, move, or restart independently.

## Configuration model

Local bootstrap configuration (`src/main/resources/application.yml`):

- `spring.application.name: api-gateway`
- `spring.config.import: configserver:http://localhost:8888`
- `spring.profiles.active: dev`

Operational routing/network properties are expected to live in `centralized-configuration` under profile-specific files (for example `api-gateway-dev.yml`) and are served by Config Server at runtime.

## Dependencies and runtime contracts

- **Config Server dependency:** startup requires `config-service` available at `localhost:8888`.
- **Discovery dependency:** route resolution depends on Eureka availability and correct backend registration.
- **Backend dependency:** effective routing depends on inventory-service and unit-of-measure-service exposing healthy instances.

If one of these components is unavailable, the gateway can start with degraded behavior or fail routing depending on the missing dependency and configuration.

## Build, test, and run

Requirements:
- Java 21
- Maven Wrapper (`./mvnw`)

Commands:

```bash
./mvnw clean install
./mvnw test
./mvnw spring-boot:run
```

Single test execution:

```bash
./mvnw test -Dtest=ApiGatewayApplicationTests
./mvnw test -Dtest=ApiGatewayApplicationTests#contextLoads
```

## Operational best practices

- Keep gateway logic infrastructure-focused (routing, cross-cutting transport concerns), not business rules.
- Keep all route/discovery configuration externalized in Config Server sources to avoid rebuilds for operational changes.
- Preserve backward-compatible public paths to prevent client-breaking changes during service evolution.
- Coordinate gateway route updates with service registration names in Eureka to avoid unresolved destinations.
- Validate effective configuration through Config Server endpoints (`/api-gateway/{profile}`) before rollout.
