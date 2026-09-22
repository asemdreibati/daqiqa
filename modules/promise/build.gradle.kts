plugins {
    id("daqiqa.spring-module-conventions")
}

description = "Promise context: how fast can we deliver this basket here, and a signed token that holds us to it."

dependencies {
    api(project(":platform:shared-kernel"))
    implementation(project(":modules:network"))
    implementation(project(":modules:inventory"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    testImplementation(project(":platform:testing"))
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation(libs.jqwik)
}
