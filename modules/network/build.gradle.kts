plugins {
    id("daqiqa.spring-module-conventions")
}

description = "Network context: dark stores, their status, and which areas each one can serve (H3 coverage)."

dependencies {
    api(project(":platform:shared-kernel"))
    implementation(libs.h3)
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    testImplementation(project(":platform:testing"))
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation(libs.jqwik)
}
