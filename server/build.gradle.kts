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
    implementation(libs.ktor.serializationJson)

    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.hikari)
    implementation(libs.postgresql)

    // Dev/test database so the server runs with no external Postgres. Never used in production.
    implementation(libs.h2)

    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.ktor.clientContentNegotiation)
    testImplementation(libs.kotlin.testJunit)
}
