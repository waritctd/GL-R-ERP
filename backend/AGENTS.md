# Backend

## Overview

The Spring Boot API behind `/api/*`. It owns every business rule, every permission check, the database schema, and the customer documents (quotation, deposit notice, invoice, payroll PDFs). The frontend and the mock API follow it, never the other way round.

## Key files

| File | Owns |
|---|---|
| `pom.xml` | Java 21, Spring Boot 4, test fork settings, the 50% line coverage floor |
| `src/main/java/th/co/glr/hr/<feature>/` | One package per feature: `*Controller`, `*Service`, `*Repository`, record DTOs |
| `src/main/java/th/co/glr/hr/auth/` | Session login, `SessionContext`, `ApplicationRoles`, access policies |
| `src/main/java/th/co/glr/hr/config/` | `SecurityConfig`, `JacksonConfig`, `ProductionReadinessConfig` |
| `src/main/java/th/co/glr/hr/common/` | `ApiException`, `ApiExceptionHandler`, paging, PDF helpers |
| `src/main/resources/db/migration/` | Flyway migrations, `V<n>__<snake_name>.sql` |
| `src/main/resources/db/migration-demo/` | Demo seed, loaded only by the `demo` profile |
| `src/main/resources/templates/` | The real company `.xls` forms the renderers fill in |
| `src/test/java/th/co/glr/hr/support/AbstractPostgresIntegrationTest.java` | Base class for tests that need a database |
| `../docs/api/*.json` | API digests that the contract tests compare against |

## Commands

```bash
# Run with demo data (needs `docker compose up -d db` from the repo root)
SPRING_PROFILES_ACTIVE=demo SERVER_SESSION_COOKIE_SECURE=false APP_MAIL_PROVIDER=log ./mvnw spring-boot:run

# One test class. The flag name is exact: other spellings are ignored without a warning.
./mvnw -Dtest.fork.count=1 -Dtest=<ClassName> test

# Build the jar
./mvnw -DskipTests package

# Regenerate an API digest after a deliberate contract change
./mvnw -Dtest=ApiSurfaceContractTest -Dapi.surface.update=true test
./mvnw -Dtest=ResponseFieldContractTest -Dresponse.fields.update=true test
./mvnw -Dtest=StatusCatalogContractTest -Dstatus.catalog.update=true test
```

Tests that need a database use `TEST_DB_URL`, `TEST_DB_USERNAME` and `TEST_DB_PASSWORD` when set, else a Testcontainers `postgres:16-alpine` when Docker runs, else they skip.

## Conventions

- Layering is Controller, then Service, then Repository. Controllers stay thin. Rules and permission checks live in the service or a policy class (`TicketAccessPolicy`, `DivisionAccessPolicy`, `LeaveEmployeeAccess`).
- Data access is plain JDBC with `NamedParameterJdbcTemplate` and SQL strings in the repository. There is no JPA. Rows and DTOs are Java records.
- Every controller method starts by resolving the user (`sessions.requireUser(session)`), then checks roles with `requireAnyRole` or `@PreAuthorize`. Roles are listed in `auth/ApplicationRoles.java`.
- Errors are thrown as `ApiException(status, message)`. Messages shown to users are Thai. `ApiExceptionHandler` shapes the response.
- Writes run inside `@Transactional` on the service. Use `SELECT ... FOR UPDATE` where two people can act on the same row.
- Money is `BigDecimal` with `RoundingMode.HALF_UP`. Dates and times use `ZoneId.of("Asia/Bangkok")`, never the server default.
- HTTP JSON uses Jackson 3, the Spring Boot 4 default. A few classes still use the Jackson 2 `ObjectMapper` from `JacksonConfig`. A primitive `boolean` request field that the client leaves out returns a bare 400, so use `Boolean` for optional fields.
- Test classes end in `Test` (no database) or `IntegrationTest` (database). Both run in the same `test` phase.

## Gotchas

- Migrations are forward only. Never edit one that has been applied. Check the highest number on `origin/develop` before you pick yours, since other sessions add migrations in parallel.
- Production turns Flyway checksum validation off because of old drift. A wrong migration will not be caught there, so `FlywayMigrationTest` is the gate.
- A new or changed endpoint, response field or status value fails a contract test until you regenerate the matching digest in `docs/api/` and commit it. The frontend digest `ui-reachable.json` has its own command (see `frontend/AGENTS.md`).
- Sessions live in server memory. Every backend restart or deploy signs everyone out.
- The `.xls` templates are legacy BIFF8 on purpose. Do not convert them to `.xlsx`. `PdfTemplateHygieneTest` fails if a personal name lands in a template.
- The Thai fonts in `fonts/` are licensed and not in git. The release image is built only on the machine that has them, never by CI.
- A commission snapshot is frozen once submitted. Never recompute it.
- Late and early minutes in attendance are for reporting only. They must never become a payroll deduction.
- `TemporaryPasswordGenerator` output must never be logged. `ProductionReadinessConfig` refuses to boot `prod` with unsafe mail settings.
- A quotation with `is_sample` is a price document only. Any new query that reads value, counts, billing, orders or a live quotation from `sales.quotation` must leave samples out. The approver's pending count is the one deliberate exception.
- The `demo` profile seeds fake logins. Never enable it against a real database.

_Drafted by /audit from the repo, worth a quick human pass. Edit freely: once a line stops matching this draft, later runs treat it as curated and will flag rather than overwrite it._
