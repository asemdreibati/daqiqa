plugins {
    id("daqiqa.spring-boot-application")
}

description = "The Daqiqa server: every bounded context deployed together as one modular monolith (ADR-0002)."

dependencies {
    implementation(project(":platform:messaging"))
    implementation(project(":modules:network"))
    implementation(project(":modules:catalog"))
    implementation(project(":modules:inventory"))
    implementation(project(":modules:promise"))
    implementation(project(":modules:ordering"))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation(libs.flyway.database.postgresql)
    implementation(libs.springdoc.openapi.webmvc.ui)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.micrometer.registry.prometheus)

    testImplementation(project(":platform:testing"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.archunit)
}
