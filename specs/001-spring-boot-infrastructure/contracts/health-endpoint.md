# Contract: Health Check Endpoint

**Feature**: Spring Boot Service & Infrastructure Bootstrap
**Endpoint**: `GET /actuator/health`
**Provided by**: Spring Boot Actuator (auto-configured)

---

## Contract

### Request

```
GET /actuator/health HTTP/1.1
Host: localhost:8080
```

No authentication, no request body, no query parameters.

### Response — Healthy (all components UP)

```http
HTTP/1.1 200 OK
Content-Type: application/vnd.spring-boot.actuator.v3+json

{
  "status": "UP",
  "components": {
    "r2dbc": {
      "status": "UP",
      "details": {
        "database": "PostgreSQL",
        "validationQuery": "SELECT 1"
      }
    },
    "diskSpace": {
      "status": "UP",
      "details": {
        "total": <bytes>,
        "free": <bytes>,
        "threshold": 10485760,
        "path": ".",
        "exists": true
      }
    },
    "ping": {
      "status": "UP"
    }
  }
}
```

### Response — Database Unavailable

```http
HTTP/1.1 503 Service Unavailable
Content-Type: application/vnd.spring-boot.actuator.v3+json

{
  "status": "DOWN",
  "components": {
    "r2dbc": {
      "status": "DOWN",
      "details": {
        "error": "..."
      }
    }
  }
}
```

---

## Configuration Required

The Actuator health endpoint is enabled by default. To expose it over HTTP and show component details, the following configuration is required:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      show-details: always
```

`show-details=always` makes the `components` sub-indicators visible in the response body (required by FR-008 to confirm database connectivity in the response).

---

## Acceptance Criteria Traceability

| Requirement | Verified By |
|-------------|------------|
| FR-008: `/actuator/health` endpoint exists | `GET /actuator/health` returns 2xx |
| FR-008: database connectivity as sub-indicator | `components.r2dbc.status` present in response |
| SC-004: responds within 2 seconds of container ready | Timed in integration test or smoke test |
| Edge case: database unavailable → clear error | Status 503 with `components.r2dbc.status=DOWN` |

---

## Test Approach (Test-First)

Per constitution principle V, the test is written before the Actuator dependency is added.

```java
// WebTestClient integration test (not MockMvc)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActuatorHealthIT {

    @Autowired
    WebTestClient webTestClient;

    @Test
    void healthEndpointReturnsUpWithR2dbcIndicator() {
        webTestClient.get()
            .uri("/actuator/health")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.status").isEqualTo("UP")
            .jsonPath("$.components.r2dbc.status").isEqualTo("UP");
    }
}
```

This test drives the need for:
- `spring-boot-starter-actuator` in `pom.xml`
- `management.endpoint.health.show-details=always` in `application.properties`
- A running PostgreSQL instance reachable during the test (use Testcontainers or the devcontainer DB)
