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
plus UTC daily and Monday-based weekly bucket calculations. JDBC persistence adapters
implement application ports; no service decision algorithm is present.

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
assumption: the current domain has no maximum identifier length. The database
rejects longer identifiers with a technical integrity exception; domain behavior
is unchanged. Input-boundary validation remains for a later chunk. Primary keys
provide the only indexes; date-only retention indexes are deferred until retention
access patterns are implemented. No foreign keys or triggers are needed.

Chunk 3 introduced only the schema; Chunk 4 adds the ports and adapters below.
H2 is the only required database; PostgreSQL is neither configured nor required. H2 remains ephemeral despite storing committed results during its lifetime.

The existing application smoke test shares one Spring context with schema checks
for composite keys, cross-customer load IDs, all business decisions, decision
consistency, required decision fields, and non-negative aggregate counters.

## JDBC persistence (Chunk 4)

`LoadResultRepository` inserts a completed decision and retrieves the original
attempt, decision, and creation timestamp by `(customer_id, load_id)`.
`JdbcLoadResultRepository` uses a plain INSERT guarded by the composite primary
key. There is no pending row or provisional business decision. Accepted and declined results are both recoverable.

The final INSERT is the uniqueness arbitration operation. An initial lookup is
only a duplicate check, never a reservation. A competing insert raises Spring's
`DuplicateKeyException`. The future service MUST roll back the entire attempt,
including any bucket increments, before reading the winning committed result in
a new transaction. It must not catch the duplicate and commit prior increments.
This permits recovery after an ambiguous outcome without overwriting the original
result. Reservation, retry, and cross-repository orchestration are not implemented.

`VelocityRepository` ensures daily/weekly buckets, attempts conditional increments,
and reads immutable bucket snapshots. `JdbcVelocityRepository` inserts zeroed
buckets and handles only `DuplicateKeyException` for existing buckets. This is an
H2 choice: duplicate statements leave the caller transaction usable. It preserves
existing counters and timestamps; a future PostgreSQL adapter needs different
bucket-creation SQL because PostgreSQL aborts a transaction on a duplicate error.

Each increment is a single conditional UPDATE. Amount predicates compare the
stored amount with `limit - requestedAmount` to avoid overflowing BIGINT addition;
the daily count predicate uses `count < limit`. Money and policy domain types
ensure non-negative requests and positive limits. One affected row means success;
zero means no matching row satisfied the limits. Callers must ensure the bucket
exists first. Technical failures propagate rather than becoming limit rejections.

Bucket reads are snapshots, not a SELECT-before-UPDATE authorization. After a
failed daily update, a read can support deterministic decline-reason precedence,
but intervening commits may change the state. It cannot prove which predicate
failed at update time; final reason precedence/isolation belongs to the later
service design. No explicit locks are taken.

Callers supply UTC bucket dates and processing `Instant` values. JDBC binds times
as UTC `OffsetDateTime` and maps them back to `Instant`, preserving nanoseconds on
H2. Repositories create no transaction boundaries and participate in caller-owned
transactions. H2 tests cover persisted decisions, duplicate keys, bucket creation,
exact limits, rejected updates, overflow, key isolation, technical failures, and
low-level rollback participation. No concurrent tests or service workflow exist.

Migration `V1__create_velocity_schema.sql` uses a CASE expression for the
allowed-reason check. Repository tests using
fresh JDBC connections exposed H2 2.4.240 retaining a closed DDL session in the IN
constraint ([H2 issue #4291](https://github.com/h2database/h2database/issues/4291)).
The CASE form preserves the same business values after the migration connection
closes; existing schema tests still verify invalid reasons are rejected.
