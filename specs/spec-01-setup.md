# Spec 01 - Project Setup

## Objective

Bootstrap a Spring Boot 3.x project with Gradle, configure all required dependencies, Docker infrastructure, and verify the build passes.

## Tasks

1. **Gradle build file** (`build.gradle`): Spring Boot 3.x with Java 21. Dependencies:
   - `spring-boot-starter-web`
   - `spring-boot-starter-data-redis`
   - `spring-boot-starter-actuator`
   - `resilience4j-spring-boot3`
   - `micrometer-registry-prometheus`
   - `spring-boot-starter-test` (test)
   - `testcontainers` + `junit-jupiter` (test)
   - Lombok

2. **docker-compose.yml**: Redis service (port 6379).

3. **Application.java**: Main class with `@SpringBootApplication`.

4. **application.yml**: Minimal config (server port, Redis host/port placeholders).

## Acceptance Criterion

- `./gradlew build` completes successfully.
