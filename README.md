# Money-load velocity-limit service

Spring Boot bootstrap using Java 25. Install a JDK 25 and set `JAVA_HOME` to it.
The Gradle Wrapper downloads Gradle; no global Gradle installation is required.

```sh
./gradlew clean test
./gradlew bootRun
```

The application listens on port 8080. Check health with:

```sh
curl http://localhost:8080/actuator/health
```

H2 runs in memory and data is discarded when the application exits. Flyway is
enabled with its default `classpath:db/migration` location. Migration `V1__create_velocity_schema.sql` creates the business schema on startup.
Only the health actuator endpoint is exposed. No external infrastructure is needed.

## Package boundaries

The base package is `com.example.moneyload`. As implementations are introduced,
they will use these boundaries:

```text
com.example.moneyload
├── domain
├── application
│   └── port
├── adapter
│   ├── inbound
│   │   ├── rest
│   │   └── file
│   └── outbound
│       └── persistence
├── configuration
└── MoneyLoadApplication
```

Unused packages are documented here rather than populated with placeholder classes.
The domain now contains immutable money, load attempt, decision and policy values,
plus UTC daily and Monday-based weekly bucket calculations. No decision algorithm
or persistence implementation is present.

Global limits are configured under `velocity.limits` in `application.yml`:
`daily-amount: "5000.00"`, `weekly-amount: "20000.00"`, and `daily-count: 3`.
Amounts bind as `BigDecimal` and convert exactly once at startup into an immutable
policy containing long cents. Missing, non-positive, fractional-cent, overflowing,
or inconsistent limits fail startup; the weekly amount must be at least the daily amount.

Load amount strings require `$` followed by unsigned decimal digits, optionally
with a decimal point and more digits. Whitespace, signs, grouping separators, and
scientific notation are rejected. Zero and trailing fractional zeros are allowed
when the amount represents whole cents. Values are never rounded.

The smoke test uses JUnit 5 and starts/closes Spring Boot directly because Spring
Boot 4.1's Spring test extension requires JUnit 6. It also checks JDBC connectivity
and Flyway initialization, and verifies the configured default policy. Focused
JUnit tests cover domain invariants, exact money parsing, UTC/DST boundaries, and
configuration binding and startup validation using a small Spring context.

## Database foundation (Chunk 3)

Flyway owns three tables: `load_attempt` stores completed accepted/declined decisions
keyed by `(customer_id, load_id)`; `daily_velocity` and `weekly_velocity` hold only
accepted aggregate state keyed by customer and UTC date / Monday week start.
Future enforcement will use these buckets, not history SUM/COUNT queries.

Money columns use `BIGINT` cents and the daily count uses `INTEGER`, all with
non-negative checks. Decisions use the Java enum names in a constrained
`VARCHAR(32)`, with a check keeping `accepted` consistent with the reason. All
columns are required. Timestamps use `TIMESTAMP(9) WITH TIME ZONE` for absolute
application times; bucket keys use `DATE` and are derived by the application.

Identifiers are strings stored as `VARCHAR(255)`. This is a conservative storage
assumption: the current domain has no maximum identifier length. Validation of
this boundary must be addressed when persistence is introduced. Primary keys
provide the only indexes; date-only retention indexes are deferred until retention
access patterns are implemented. No foreign keys or triggers are needed.

Repository ports are deferred to Chunk 4 to avoid inventing reservation and atomic
increment semantics now. This chunk adds no persistence implementation or velocity
logic. H2 is the only required database; PostgreSQL is neither configured nor
required. H2 remains ephemeral despite storing committed results during its lifetime.

The existing application smoke test shares one Spring context with schema checks
for composite keys, cross-customer load IDs, all business decisions, decision
consistency, required decision fields, and non-negative aggregate counters.
