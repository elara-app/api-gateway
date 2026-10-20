# syntax=docker/dockerfile:1.28.0
# Elara api-gateway — multistage build (ETAPA-03/D-01)
# Builder resolves Maven deps (cached layer), runtime is a JRE with the layered jar exploded.
FROM maven:3.9-eclipse-temurin-26 AS builder
WORKDIR /build

# Dependency cache layer: wrapper + pom first, so dependency resolution is cached
# independently of source changes.
COPY mvnw ./
COPY .mvn/ ./.mvn/
COPY pom.xml ./
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline

# Application layer.
COPY src ./src
RUN ./mvnw -B -DskipTests package \
 && java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination extracted

# Runtime: JRE only (java.version=21), curl for HEALTHCHECK, tzdata+TZ so LocalDateTime
# matches Postgres, non-root user per CIS.
FROM eclipse-temurin:21.0.12.1_1-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl tzdata \
 && rm -rf /var/lib/apt/lists/*
ENV TZ=America/Bogota
RUN useradd -m -u 10001 appuser
WORKDIR /app

# The four layers are flattened into one WORKDIR (that is what makes JarLauncher work).
COPY --from=builder /build/extracted/dependencies ./
COPY --from=builder /build/extracted/spring-boot-loader ./
COPY --from=builder /build/extracted/snapshot-dependencies ./
COPY --from=builder /build/extracted/application ./

USER appuser
EXPOSE 8080
# Memory/GC are injected by Compose via JAVA_TOOL_OPTIONS (no hardcoded heap).
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
