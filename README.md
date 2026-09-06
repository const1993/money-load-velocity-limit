# Money-load velocity-limit service

The application processes loads through REST or sequential JSON-lines file uploads.

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
│   │   └── rest
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
low-level rollback participation. Service and concurrency tests are described below.

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
and decisions are logged at INFO with named fields only after transaction completion.
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

## Technical failures

Each request invokes `LoadFundsService` once. There are no automatic retries,
backoff delays, or retry configuration. A transient failure returns HTTP 503
immediately; unexpected internal failures return 500. Neither becomes a business
decline. The classifier recognizes explicit Spring/JDBC transient categories.

Transactions and idempotency remain unchanged. If commit succeeds but its
acknowledgement is lost, the request fails. A subsequent request with the same
customer/load key retrieves the stored decision without applying velocity again.

## REST API (Chunk 8)

`POST /v1/loads` consumes and produces `application/json` and processes exactly
one load per request. Start normally with `./gradlew bootRun` or the executable
jar, then submit:

```sh
curl -i http://localhost:8080/v1/loads \
  -H 'Content-Type: application/json' -H 'X-Request-Id: example-123' \
  --data '{"id":"1","customer_id":"customer","load_amount":"$100.00","time":"2018-01-01T00:00:00Z"}'
```

Acceptance and velocity decline both return HTTP 200 with exactly `id`,
`customer_id`, and boolean `accepted`. REST idempotent replay returns the original
committed decision, even if the new valid payload has a different amount/time.
The controller invokes `LoadFundsService`; no controller business logic,
transactions, repository calls, or additional retries exist.

`LoadRequest` and `LoadResponse` are separate from the domain.
Mapping reuses `Money.parse`, domain ID validation, and offset-aware timestamp
parsing. Invalid/missing fields, malformed JSON, timezone-less timestamps,
fractional cents, scalar-to-string coercion, extra fields, duplicate JSON keys,
arrays, and trailing JSON objects fail validation. Mapping errors alone become client errors; the same
exception type raised by service internals remains a server error.

Central advice returns a stable `ApiError` with `code`, `message`, and `request_id`:

| Outcome | HTTP | Code |
| --- | --- | --- |
| Invalid JSON or load data | 400 | INVALID_REQUEST |
| Transient technical failure | 503 | SERVICE_UNAVAILABLE |
| Unexpected internal failure | 500 | INTERNAL_ERROR |

Framework errors retain their HTTP status (for example 405/415) with the same
error shape. Responses never expose SQL, exception names, internal decline reasons,
counters, limits, or stack traces. Technical failures are logged once at ERROR
by `LoadExceptionHandler`, which centrally logs technical failures and maps
exceptions to HTTP responses, including malformed JSON before controller invocation.
No controller error-logging aspect or deduplication marker is needed. Logs exclude
raw exception messages and request bodies. Request ID is inherited from MDC.
The shared decision aspect emits one INFO log per completed service outcome.

The correlation filter accepts one `X-Request-Id` header containing 1–128 ASCII
letters/digits or `.`, `_`, `:`, `-`, beginning with a letter/digit. Absent, invalid,
or multiple values are replaced by a generated UUID. The ID is returned in the
response header, included in error bodies, and scoped to MDC `request_id` during
processing. The previous MDC value is restored in `finally`, or removed when
none existed. The endpoint is synchronous; no asynchronous processing is added.

MVC tests use a mocked application service; one HTTP integration test exercises
the real service and H2 and verifies replay increments counters only once.
`/actuator/health` exposure is unchanged.
No authentication, OpenAPI, PostgreSQL, Testcontainers, retention, concurrency
hardening, business metrics, or checkpoint infrastructure is added. Implementation
stops after Chunk 8.

## H2 concurrency verification

`LoadFundsConcurrencyTests` runs 30 isolated race scenarios across daily amount,
daily count, weekly amount/savepoint rollback, duplicate keys, independent
customers, and initial bucket creation. Winners are not predetermined; returned
decisions are checked against committed load results and daily/weekly counters.

Tests use real JDBC repositories and Spring `REQUIRES_NEW` transactions. A
test-only aspect waits at a barrier inside each transaction before business SQL;
distinct H2 session IDs verify separate simultaneous database connections.
Each repetition has a fresh database and committed setup. The test-only Hikari
pool allows 12 connections, with 5-second connection and H2 lock timeouts.
Barriers, future waits, executor shutdown, and overall tests have bounded waits.
Production connection settings and processing code are unchanged.

These tests validate application invariants on H2, not performance or identical
PostgreSQL locking/isolation behavior. PostgreSQL-specific concurrency verification
remains future production hardening. No retries, explicit pessimistic locks,
application-local locks, or production thread pools are introduced.

## Minimal file adapter

File processing is triggered only by the upload endpoints below. Application startup
does not read or write load files.

Input is UTF-8, one complete JSON object per line (no array). The adapter uses
buffered sequential I/O, `Money.parse`, and the same offset-aware timestamp parsing
as REST. Each completed load emits exactly `id`, `customer_id`, and `accepted`,
as compact JSON followed by a newline. Duplicates emit nothing, including duplicates
already stored by an earlier invocation. REST continues replaying their original decisions.

Invalid input (including blank lines) stops at the reported line number. Technical
failures also stop processing and never become declines. Earlier loads remain
committed and output may be partial; there are no retries, checkpoints, or restart
recovery. No raw input lines or per-load decision logs are added by the adapter.

## File upload endpoints

Both endpoints accept a multipart field named `file`. They reuse the sequential file processor and return UTF-8
`application/x-ndjson` (one compact decision per line, no JSON array).

```sh
# Download output.txt
curl --fail-with-body -F 'file=@input.txt' \
  http://localhost:8080/v1/loads/file/download -o output.txt

# Return JSON lines directly in the response body
curl --fail-with-body -F 'file=@input.txt' http://localhost:8080/v1/loads/file
```

The download endpoint adds `Content-Disposition: attachment; filename="output.txt"`.
By default duplicates emit nothing; uploading the same loads again can return an empty body.
An empty file succeeds. Missing uploads and invalid lines return HTTP 400 through
the existing error handler; technical failures return 500/503. Processing stops at
the failure, but earlier decisions remain committed. Output is buffered on a temporary
file before sending a success response so failures do not produce a partial HTTP 200.
The temporary output is deleted on success or failure; no checkpoint or batch metadata
is retained. Existing Spring multipart size limits apply and can be configured through
`spring.servlet.multipart.max-file-size` and `spring.servlet.multipart.max-request-size`.
The original `POST /v1/loads` behavior is unchanged.

Both upload endpoints accept `includeDuplicates` (default `false`). Use
`?includeDuplicates=true` to emit the original stored decision for every duplicate
in input order, with the same `id`, `customer_id`, and `accepted` fields. This does
not reevaluate the load or update its velocity counters. For example:

```sh
curl --fail-with-body -F 'file=@input.txt' \
  'http://localhost:8080/v1/loads/file/download?includeDuplicates=true' -o output.txt
```
