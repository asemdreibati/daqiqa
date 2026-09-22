plugins {
    id("daqiqa.java-conventions")
}

description = "Shared test infrastructure: throwaway PostgreSQL databases and architecture rules."

dependencies {
    api(libs.postgresql)
    api(libs.hikaricp)
    api(libs.flyway.core)
    api(libs.flyway.database.postgresql)
    api(libs.testcontainers.postgresql)
    api(libs.archunit)
    api("org.springframework:spring-test")
}
