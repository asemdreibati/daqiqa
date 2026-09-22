// Conventions for bounded-context modules that contain Spring adapters (web, persistence, messaging).

plugins {
    id("daqiqa.java-conventions")
    `java-test-fixtures`
}

val libs = the<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = libs.findLibrary(alias).get()

dependencies {
    testFixturesImplementation(platform(lib("spring-boot-dependencies")))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(lib("archunit"))
}
