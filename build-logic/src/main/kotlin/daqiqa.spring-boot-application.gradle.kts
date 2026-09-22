// Conventions for deployable Spring Boot applications.

plugins {
    id("daqiqa.java-conventions")
    id("org.springframework.boot")
}

springBoot {
    buildInfo()
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName = "${project.name}.jar"
}

// A Boot application is not a library: no plain jar next to the executable one.
tasks.named<Jar>("jar") {
    enabled = false
}
