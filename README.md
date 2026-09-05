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
enabled with its default `classpath:db/migration` location. No migrations or
business tables are defined yet; Flyway initializes its schema history on startup.
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

Packages are documented here rather than populated with placeholder classes.
This bootstrap contains no money-load business logic.

The smoke test uses JUnit 5 and starts/closes Spring Boot directly because Spring
Boot 4.1's Spring test extension requires JUnit 6. It also checks JDBC connectivity
and Flyway initialization.
