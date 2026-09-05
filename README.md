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
implement application ports; `LoadFundsService` coordinates one load transaction.

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
`DuplicateKeyException`. The service rolls back the entire attempt,
including any bucket increments, before reading the winning committed result in
a new transaction. It must not catch the duplicate and commit prior increments.
This permits recovery after an ambiguous outcome without overwriting the original
result. Chunk 5 implements this orchestration; reservation and retry loops are not implemented.

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
failed at update time. Chunk 5 uses READ_COMMITTED and amount-before-count
precedence for that snapshot. No explicit pessimistic locks are taken.

Callers supply UTC bucket dates and processing `Instant` values. JDBC binds times
as UTC `OffsetDateTime` and maps them back to `Instant`, preserving nanoseconds on
H2. Repositories create no transaction boundaries and participate in caller-owned
transactions. H2 tests cover persisted decisions, duplicate keys, bucket creation,
exact limits, rejected updates, overflow, key isolation, technical failures, and
low-level rollback participation. Service tests are described below; no concurrent tests exist.

Migration `V1__create_velocity_schema.sql` uses a CASE expression for the
allowed-reason check. Repository tests using
fresh JDBC connections exposed H2 2.4.240 retaining a closed DDL session in the IN
constraint ([H2 issue #4291](https://github.com/h2database/h2database/issues/4291)).
The CASE form preserves the same business values after the migration connection
closes; existing schema tests still verify invalid reasons are rejected.

## Load processing (Chunk 5)

`LoadFundsService.process(LoadAttempt)` returns a `LoadOutcome.Completed` containing
an accepted/declined stored result, or a distinct `LoadOutcome.Duplicate` containing
the original stored result. A duplicate has no accepted=false interpretation.
The original data is available for later ambiguous-outcome recovery; this chunk
never retries a load or automatically recovers a technical commit failure.

`LoadFundsService` delegates to a separate Spring bean, `LoadFundsTransaction`,
whose public methods use `@Transactional` with REQUIRES_NEW and READ_COMMITTED.
The proxy completes commit/rollback before control returns to the service; calls
do not rely on self-invocation. Duplicate recovery uses a separate read-only
transaction through the same proxy.
Each evaluation commits or rolls back independently, even if its caller has an
active transaction. Repositories join that transaction. There is no SQL in the
service. The immutable configured `VelocityPolicy` supplies all limits, and an
injected UTC `Clock` supplies one processing timestamp per new evaluation. Bucket
keys always come from the event timestamp through `VelocityBuckets`.

Processing checks the composite idempotency key, creates a JDBC savepoint through
Spring's `TransactionStatus` obtained from `TransactionAspectSupport`, ensures
DAILY then WEEKLY buckets, and attempts DAILY
then WEEKLY conditional increments. The savepoint precedes bucket creation as well
as increments. Any business decline rolls back to it, including removal of newly
created empty buckets and restoration of timestamps, before inserting the declined
result and committing. Acceptance retains both increments and inserts ACCEPTED.
Savepoint creation, rollback, release, repository, and commit failures propagate;
they are never converted to a business decline.

A failed daily increment is followed by a bucket snapshot solely to resolve the
reason. Amount takes precedence over count if both are exceeded. The subtraction
comparison avoids long overflow. This assumes committed counters are not decreased
or deleted during evaluation, as is the case with the current operations; a newer
commit can affect reason precedence without authorizing an otherwise rejected load.
A missing bucket or snapshot with neither limit violated is an invariant failure,
not a fabricated decline. Weekly increments are skipped on a daily failure.

An already visible duplicate returns immediately without touching velocity. If the
final result INSERT instead discovers a duplicate, the entire attempt rolls back.
Only then does a separate lookup transaction retrieve the committed original and
return Duplicate. This is uniqueness resolution, not a second evaluation or retry
loop. If no original can be retrieved, the failure propagates. Processing timestamps
and decisions are logged at DEBUG with named fields only after transaction completion.
`LoadDecisionLoggingAspect` handles this with `@AfterReturning` on
`LoadFundsService.process`; technical failures produce no decision log. Spring AOP
is enabled by Boot with the AspectJ dependency. The service contains no logging code.
The logger category is `com.example.moneyload.observability.LoadDecisionLoggingAspect`.

Tests use real annotation-driven Spring proxies with mocked repository ports and
transaction manager/status to verify
ordering, savepoint calls, UTC keys, reason precedence, duplicates, and error paths.
H2 integration tests use real JDBC adapters and transactions to verify weekly
savepoint rollback (400000 daily / 1950000 weekly plus 100000), unchanged daily
amount/count/timestamps on declines, removal of newly created buckets, exact limits,
duplicate isolation, full technical rollback, and final-insert uniqueness handling.
No threads, REST/file adapters, or external database are added.

## Retry and technical errors (Chunk 6)

`RetryingLoadFundsService.process(LoadAttempt)` is the entry point for processing
one operation with retries. `LoadFundsService` remains the single-attempt service.
The coordinator passes the same immutable load on every invocation, preserving
`(customer_id, load_id)`. Business results return immediately; technical failures
never become `accepted=false`.

Each invocation crosses the existing `LoadFundsTransaction` Spring proxy using
REQUIRES_NEW. Commit or rollback and transaction cleanup finish before the
coordinator sees the result or exception. An existing caller transaction is
suspended for each attempt and resumed afterward; backoff never runs inside the
failed attempt's transaction. No repository statement is retried independently.

Immutable `ProcessingRetryProperties` binds `processing.retry`, separately from
business limits. Defaults are `max-attempts: 3`, `initial-backoff: 100ms`, and
`max-backoff: 1s`. Startup rejects attempts below one, negative initial backoff,
and maximum backoff below initial backoff. Zero backoff is explicitly allowed.
Spring Framework's built-in `RetryTemplate` executes the operation using a
`RetryPolicy` and `ExponentialBackOff`; there is no application retry loop.
Spring's retry count is configured as `max-attempts - 1`. Delays double after
each failure, capped at max-backoff. Durations must be whole milliseconds within
the range of a Java long, validated at startup to prevent silent truncation or
conversion overflow. There is no delay after the last attempt. The injectable
`BackoffSleeper` uses `Thread.sleep(Duration)` in production; tests use fakes.
Interruption restores the interrupt flag and stops processing immediately.
Since `RetryTemplate` has no sleeper injection point, a backoff adapter performs
the configured wait and returns zero to the template to avoid sleeping twice.
The adapter also emits the retry WARN log. Each call owns its own template and
operation state, while the configured policy is reused without mutation.

`TechnicalFailureClassifier` centralizes classification, reusing Spring/JDBC's
existing technical exception hierarchy rather than wrapping every exception.
Explicit transient/recoverable data-access failures and transaction timeouts
are retryable. Recognized transaction/connection wrappers are inspected only for
explicit transient or recoverable causes. SQL grammar, integrity, mapping,
programming, validation, unknown connection failures, and unknown commit failures
fail immediately with their original exception. A rollback failure with an
application exception also fails immediately. Exhaustion throws
`RetryExhaustedException`, retaining the last failure, attempt count, and category.
Intermediate retries emit WARN with event, customer_id, load_id, attempt,
max_attempts, failure_category, and backoff fields, without payloads or stack
traces. Exhaustion produces no additional ERROR log in this layer.

When a retry finds a stored result, the existing transaction returns before
touching velocity. The coordinator recovers that original decision as Completed,
including its original payload and processing timestamp. A first-attempt
duplicate remains Duplicate for future adapters. Recovery assumes the composite
key identifies the same logical operation throughout a coordinator call; it
cannot distinguish a pre-existing duplicate whose first lookup failed from an
uncertain commit by this call. Separate calls still preserve duplicate semantics.

Focused tests cover first success, transient recovery, exhaustion, immediate
failure, all business decisions, duplicates, capped/overflow-safe backoff,
interruption, classification, and startup validation. H2 tests additionally
prove full rollback and fresh transactions under an outer transaction, and
simulate a lost acknowledgement after actual commit for accepted and declined
results, verifying counters are applied at most once. No retry library or other
dependency is added. Implementation stops after Chunk 6.
