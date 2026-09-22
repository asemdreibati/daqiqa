// Conventions shared by every Java module: compiler settings, BOM alignment, formatting, tests, coverage.

import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    `java-library`
    jacoco
    id("com.diffplug.spotless")
}

val libs = the<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = libs.findLibrary(alias).get()

group = "io.daqiqa"

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial,-this-escape,-try"))
}

dependencies {
    // Every configuration is aligned on the Spring Boot BOM, so managed libraries need no version.
    val bom = platform(lib("spring-boot-dependencies"))
    implementation(bom)
    annotationProcessor(bom)
    testImplementation(bom)

    api(lib("jspecify"))

    testImplementation(lib("junit-jupiter"))
    testImplementation(lib("assertj-core"))
    testRuntimeOnly(lib("junit-platform-launcher"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Mockito's inline mock maker attaches an agent; make that explicit on JDK 21+.
    jvmArgs("-XX:+EnableDynamicAgentLoading", "-Xshare:off")
    systemProperty("user.timezone", "UTC")
    systemProperty("file.encoding", "UTF-8")
    // External test infrastructure (see platform/testing): forwarded when present, Testcontainers otherwise.
    listOf("DAQIQA_TEST_PG_URL", "DAQIQA_TEST_PG_USER", "DAQIQA_TEST_PG_PASSWORD").forEach { name ->
        providers.environmentVariable(name).orNull?.let { environment(name, it) }
    }
    testLogging {
        events("skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = false
    }
    finalizedBy(tasks.named("jacocoTestReport"))
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.withType<Test>())
    reports {
        xml.required = true
        html.required = true
    }
}

spotless {
    java {
        target("src/*/java/**/*.java")
        palantirJavaFormat(libs.findVersion("palantir-java-format").get().requiredVersion)
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}
