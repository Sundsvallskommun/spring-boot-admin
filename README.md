# SpringBootAdmin

This is a Spring Boot Admin Server application for Sundsvalls kommun that monitors and displays health, metrics, and application details for Spring Boot services deployed in Kubernetes.

**Tech Stack:**
- Java 25
- Spring Boot (via dept44-service-parent 7.0.0)
- Spring Boot Admin Server (de.codecentric)
- Spring Cloud Kubernetes Client
- Spring Security
- Maven build system
- MariaDB for persistence
- Flyway for database migrations
- Testcontainers for integration testing

## Build and Test Commands

```bash
# Build the project
mvn clean install

# Run tests
mvn test

# Run a single test class
mvn test -Dtest=ApplicationSecurityConfigurationTest

# Run a single test method
mvn test -Dtest=ApplicationSecurityConfigurationTest#userDetailsService

# Package the application
mvn package

# Run the application locally
mvn spring-boot:run
```

## Architecture

### Service Discovery

- Uses **Spring Cloud Kubernetes Discovery** to automatically discover Spring Boot applications in Kubernetes
- Configured to scan **all namespaces** for services with label `spring-boot: true`
- Services register themselves automatically when discovered
- **SBA excludes itself** from monitoring via `spring.boot.admin.discovery.ignored-services` to prevent version conflicts during rolling restarts

### Instance Persistence Architecture

Instance state is reconstructed from persisted events using an event-sourcing approach:

- Uses SBA's `EventsourcingInstanceRepository` which reconstructs instance state by replaying events from the `JdbcEventStore`
- `JdbcEventStore` loads all events from the database on startup
- `PersistenceConfig` publishes stored events via an `ApplicationReadyEvent` listener to trigger status checks for known instances
- No separate `instances` table is needed — instance state is derived entirely from events
- Database migrations managed by Flyway in `src/main/resources/db/migration/`

### Event Journal Architecture

The application persists instance events (health changes, registrations, etc.) for audit and recovery:

**Components:**
- `JdbcEventStore` - Extends SBA's `ConcurrentMapEventStore` with database persistence; loads events on startup, persists on append
- `EventPersistenceStore` - JDBC operations for batch insert and cleanup queries
- `EventSerializer` - JSON serialization for `InstanceEvent` objects
- `EventRetentionService` - Scheduled cleanup: deletes events older than retention period (default 30 days) and keeps max events per instance (default 1000)

**Rolling Restart Handling:**
- The `uk_instance_version` unique constraint prevents duplicate events when multiple pods receive the same event
- `saveBatch()` catches `DuplicateKeyException` and ignores already-persisted events
- `handleVersionConflict()` refreshes the in-memory cache from the database when version conflicts occur

**Configuration (`EventJournalProperties`):**

```yaml
spring.boot.admin.journal:
  retention-days: 30           # Days to keep events (default: 30)
  max-events-per-instance: 1000  # Max events per instance (default: 1000)

scheduler.journal.retention:
  cron.expression: "0 0 2 * * *"  # Daily cleanup at 02:00
```

### Security Architecture

- Security can be **enabled/disabled** via `spring.security.enabled` property (default: false in application.yml)
- When enabled: Uses form-based authentication with in-memory user store
- Username/password configured via `spring.security.user.name` and `spring.security.user.password` properties
- Security configuration has two conditional beans:
  - `securityEnabled()` - Active when `spring.security.enabled=true`
  - `securityDisabled()` - Active when `spring.security.enabled=false`
- Public endpoints (health, actuator/info, wallboard, assets) accessible without auth
- SBA instance registration/deregistration endpoints exempt from CSRF protection
- Remember-me authentication configured for 14 days

### Configuration Structure

- `application.yml` - Base configuration with property placeholders
- `application-default.yml` - Local development profile
- Tests use `@ActiveProfiles("junit")` with Testcontainers providing the database
- Configuration uses custom properties under `config.*` namespace that map to Spring Boot Admin properties

### Package Structure

```
se.sundsvall.springbootadmin/
├── Application.java                 # Main entry point with @EnableAdminServer, @EnableDiscoveryClient, @EnableScheduling
├── configuration/
│   ├── AdminUser.java               # Configuration properties record for admin credentials
│   ├── ApplicationSecurityConfiguration.java  # Security setup with conditional beans
│   ├── EventJournalProperties.java  # Configuration for event retention
│   └── PersistenceConfig.java       # Wires persistence components together, triggers event publishing on startup
├── repository/
│   ├── EventPersistenceStore.java   # Event database operations with JdbcTemplate
│   ├── EventSerializationException.java  # Exception for serialization failures
│   ├── EventSerializer.java         # JSON serialization for events using Jackson
│   └── JdbcEventStore.java          # JDBC-backed event store extending ConcurrentMapEventStore
└── service/
    └── EventRetentionService.java   # Scheduled cleanup of old events (@Dept44Scheduled)
```

### Dependency Management

- Parent POM: `dept44-service-parent` (Sundsvalls kommun's shared parent)
- Excludes standard dept44 configurations: `WebConfiguration`, `OpenApiConfiguration`, `SecurityConfiguration`
- Uses dept44 starter for testing and scheduling (`@Dept44Scheduled`)
- Hazelcast included for potential future distributed event storage

## Testing Guidelines

- Test profile: Use `@ActiveProfiles("junit")`
- Use `@SpringBootTest` for integration tests
- Use `@Nested` classes to group related test scenarios (e.g., SecurityEnabled vs SecurityDisabled)
- Test both security enabled and disabled states using `properties` parameter in `@SpringBootTest`
- Integration tests use **Testcontainers** with MariaDB (auto-configured via JDBC URL)
- Use `@Sql` annotations to set up/clean database state between tests
- Use `reactor-test` (`StepVerifier`) for testing reactive streams in repository tests

## Status

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=Sundsvallskommun_spring-boot-admin&metric=alert_status)](https://sonarcloud.io/summary/overall?id=Sundsvallskommun_spring-boot-admin)
[![Reliability Rating](https://sonarcloud.io/api/project_badges/measure?project=Sundsvallskommun_spring-boot-admin&metric=reliability_rating)](https://sonarcloud.io/summary/overall?id=Sundsvallskommun_spring-boot-admin)
[![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=Sundsvallskommun_spring-boot-admin&metric=security_rating)](https://sonarcloud.io/summary/overall?id=Sundsvallskommun_spring-boot-admin)
[![Maintainability Rating](https://sonarcloud.io/api/project_badges/measure?project=Sundsvallskommun_spring-boot-admin&metric=sqale_rating)](https://sonarcloud.io/summary/overall?id=Sundsvallskommun_spring-boot-admin)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=Sundsvallskommun_spring-boot-admin&metric=vulnerabilities)](https://sonarcloud.io/summary/overall?id=Sundsvallskommun_spring-boot-admin)
[![Bugs](https://sonarcloud.io/api/project_badges/measure?project=Sundsvallskommun_spring-boot-admin&metric=bugs)](https://sonarcloud.io/summary/overall?id=Sundsvallskommun_spring-boot-admin)

## 

Copyright (c) 2023 Sundsvalls kommun
