plugins {
    id("daqiqa.spring-module-conventions")
}

description = "Ordering context: idempotent checkout that turns a verified promise and a reservation into an order."

dependencies {
    api(project(":platform:shared-kernel"))
    implementation(project(":platform:messaging"))
    implementation(project(":modules:catalog"))
    implementation(project(":modules:inventory"))
    implementation(project(":modules:promise"))
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    testImplementation(project(":platform:testing"))
    testImplementation(testFixtures(project(":platform:messaging")))
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation(libs.jqwik)
}
