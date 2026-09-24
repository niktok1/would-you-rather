plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ktor)
}

group = "io.ntole.wyr"
version = "1.0.0"

application {
    mainClass = "io.ntole.wyr.server.ApplicationKt"
}

dependencies {
    // Shares the wire contract with the client — the point of CLAUDE.md §2.
    api(project(":core"))

    implementation(libs.logback)
    implementation(libs.kotlinx.coroutinesCore)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation(libs.ktor.serverContentNegotiation)
    implementation(libs.ktor.serverAuth)
    implementation(libs.ktor.serverAuthJwt)
    implementation(libs.ktor.serverStatusPages)
    implementation(libs.ktor.serverCallLogging)
    implementation(libs.ktor.serverCors)
    implementation(libs.ktor.serverRateLimit)
    implementation(libs.ktor.serializationJson)

    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.hikari)
    implementation(libs.postgresql)

    // Schema migrations (CLAUDE.md §8b). H2 support is built into flyway-core; PostgreSQL's lives
    // in its own module, without which Flyway refuses a PostgreSQL database outright.
    implementation(libs.flyway.core)
    implementation(libs.flyway.databasePostgresql)

    // Dev/test database so the server runs with no external Postgres. Never used in production.
    implementation(libs.h2)

    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.ktor.clientContentNegotiation)
    testImplementation(libs.kotlin.testJunit)
}

// Both the H2 and Postgres drivers register through META-INF/services/java.sql.Driver. Shadow's
// default EXCLUDE strategy keeps only the first copy, so the fat jar knew Postgres and died on H2.
// Merging needs both halves: INCLUDE lets every copy reach the transformer, and the transformer
// joins them. Gradle does not track a filesMatching action as a task input, so after changing only
// that block, rebuild with --rerun. Flyway finds the databases it supports the same way, flyway-core
// registering H2 and flyway-database-postgresql PostgreSQL, so a lost copy there would fail only on
// PostgreSQL, which the H2 boot smoke test never reaches.
tasks.shadowJar {
    filesMatching("META-INF/services/**") {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    mergeServiceFiles()
}

tasks.test {
    // ApiFlowTest switches from H2 to this database when it is set. Declared as an input so a
    // Postgres run is never satisfied by an up-to-date or cached H2 result.
    inputs.property("testJdbcUrl", providers.environmentVariable("WYR_TEST_JDBC_URL").orElse(""))
}
