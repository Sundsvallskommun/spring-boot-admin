# SpringBootAdmin

This is a Spring Boot Admin Server application for Sundsvalls kommun that monitors and displays health, metrics, and application details for Spring Boot services deployed in Kubernetes.

**Tech Stack:**
- Java 25
- Spring Boot (via dept44-service-parent)
- Spring Boot Admin Server (de.codecentric)
- Spring Cloud Kubernetes Client
- Spring Security
- Hazelcast (shared in-memory event store across SBA replicas)
- Maven build system

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

### Instance State (Hazelcast)

Instance state lives in memory only — there is no database. Two SBA replicas share state via a Hazelcast cluster:

- `HazelcastConfig` produces a `com.hazelcast.config.Config` bean
- SBA's `AdminServerHazelcastAutoConfiguration` picks that up and backs its event store with a Hazelcast `IMap`, so both pods see the same instance list and event stream
- In Kubernetes, members discover each other via the headless service named by `spring.hazelcast.kubernetes.service-name`. When that property is unset (local dev / tests), Hazelcast runs as a single-member cluster on localhost
- State is rebuilt on a full cluster restart by SBA re-discovering instances from Kubernetes — there is no on-disk journal

### Hazelcast Setup

**How `HazelcastConfig` works**

`HazelcastConfig.hazelcastClusterConfig(...)` reads three values:

- `spring.application.name` — used as the Hazelcast cluster name (so unrelated Hazelcast clusters in the same network can't accidentally merge)
- `spring.hazelcast.kubernetes.service-name` — when set, switches Hazelcast into Kubernetes discovery mode; when blank, falls back to a single-member local cluster
- `spring.hazelcast.kubernetes.namespace` — optional; when blank, Hazelcast looks in the pod's own namespace (read from the mounted service-account token)

Multicast is always disabled. In local mode (blank service-name), TCP/AWS/Kubernetes joiners are also disabled — the member only sees itself, which is what tests and local dev want.

**Mode 1: single-member local cluster** (default in tests / dev)

Triggered when `config.hazelcast.kubernetes.service-name` is unset (empty after the `${…:}` default in `application.yml`). The pod doesn't try to find peers. SBA's event store still goes through Hazelcast, but the IMap has exactly one owner.

**Mode 2: clustered in Kubernetes**

Triggered when `config.hazelcast.kubernetes.service-name` is set in the overlay. Hazelcast uses its built-in Kubernetes discovery (Kubernetes API mode by default) to find peer pods and form a TCP mesh on port 5701.

For two SBA pods to actually form one cluster, three things have to be in place:

1. **A headless Service that selects the SBA pods.** A normal ClusterIP service load-balances to one random pod, which is useless for cluster formation — each member needs to reach every *other* member directly. `clusterIP: None` makes K8s return all backing pod IPs.

   ```yaml
   apiVersion: v1
   kind: Service
   metadata:
     name: spring-boot-admin-hz
     namespace: <ns>
   spec:
     clusterIP: None
     publishNotReadyAddresses: true   # let members find each other before readiness probe passes
     selector:
       app: spring-boot-admin
     ports:
       - name: hazelcast
         port: 5701
         targetPort: 5701
   ```

   Then in the deployment overlay:

   ```yaml
   config.hazelcast.kubernetes.service-name: spring-boot-admin-hz
   config.hazelcast.kubernetes.namespace: <ns>   # optional
   ```
2. **RBAC for the pod's ServiceAccount.** The Hazelcast K8s plugin calls the Kubernetes API to list endpoints for the named service. Without permissions you'll see `Forbidden` in the logs and each pod will form a one-member cluster of its own.

   ```yaml
   rules:
     - apiGroups: [""]
       resources: ["endpoints", "pods"]
       verbs: ["get", "list"]
   ```
3. **Pod-to-pod network on TCP 5701.** If the namespace has a default-deny `NetworkPolicy`, allow SBA pods to talk to each other on 5701. Without it, discovery resolves but the TCP join silently fails and you end up with two split-brain single-member clusters.

**Verifying the cluster formed**

Watch the startup log of either pod — successful join logs `Members {size:2, ver:2}` with both member addresses. If you see `Members {size:1}` on both pods, one of the three requirements above is missing.

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
- `application-junit.yml` - Test profile, activated by `@ActiveProfiles("junit")`
- Configuration uses custom properties under `config.*` namespace that map to Spring Boot Admin properties

### Package Structure

```
se.sundsvall.springbootadmin/
├── Application.java                 # Main entry point with @EnableAdminServer, @EnableDiscoveryClient
└── configuration/
    ├── AdminUser.java                            # @ConfigurationProperties for admin credentials
    ├── ApplicationSecurityConfiguration.java     # Security setup with conditional beans
    └── HazelcastConfig.java                      # Hazelcast Config bean (K8s discovery / local mode)
```

### Dependency Management

- Parent POM: `dept44-service-parent` (Sundsvalls kommun's shared parent)
- Excludes standard dept44 configurations: `WebConfiguration`, `OpenApiConfiguration`, `SecurityConfiguration`
- Hazelcast is the only persistence-ish dependency — cluster-shared in-memory state, no on-disk store

## Testing Guidelines

- Test profile: Use `@ActiveProfiles("junit")`
- Use `@SpringBootTest` for integration tests that need the full context
- Use `@Nested` classes to group related test scenarios (e.g., SecurityEnabled vs SecurityDisabled)
- Test both security enabled and disabled states using `properties` parameter in `@SpringBootTest`

## Status

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=Sundsvallskommun_spring-boot-admin&metric=alert_status)](https://sonarcloud.io/summary/overall?id=Sundsvallskommun_spring-boot-admin)
[![Reliability Rating](https://sonarcloud.io/api/project_badges/measure?project=Sundsvallskommun_spring-boot-admin&metric=reliability_rating)](https://sonarcloud.io/summary/overall?id=Sundsvallskommun_spring-boot-admin)
[![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=Sundsvallskommun_spring-boot-admin&metric=security_rating)](https://sonarcloud.io/summary/overall?id=Sundsvallskommun_spring-boot-admin)
[![Maintainability Rating](https://sonarcloud.io/api/project_badges/measure?project=Sundsvallskommun_spring-boot-admin&metric=sqale_rating)](https://sonarcloud.io/summary/overall?id=Sundsvallskommun_spring-boot-admin)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=Sundsvallskommun_spring-boot-admin&metric=vulnerabilities)](https://sonarcloud.io/summary/overall?id=Sundsvallskommun_spring-boot-admin)
[![Bugs](https://sonarcloud.io/api/project_badges/measure?project=Sundsvallskommun_spring-boot-admin&metric=bugs)](https://sonarcloud.io/summary/overall?id=Sundsvallskommun_spring-boot-admin)

## 

Copyright (c) 2023 Sundsvalls kommun
