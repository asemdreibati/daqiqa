plugins {
    id("daqiqa.spring-module-conventions")
}

description = "Inventory context: stock per store and SKU, reservations that can never oversell."

dependencies {
    api(project(":platform:shared-kernel"))
    implementation(project(":platform:messaging"))
    implementation(project(":modules:network"))
    implementation(project(":modules:catalog"))
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    testImplementation(project(":platform:testing"))
    testImplementation(testFixtures(project(":platform:messaging")))
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation(libs.jqwik)
}
