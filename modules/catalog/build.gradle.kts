plugins {
    id("daqiqa.spring-module-conventions")
}

description = "Catalog context: what we sell, in English and Arabic, at what price. Reference module for conventions."

dependencies {
    api(project(":platform:shared-kernel"))
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    testImplementation(project(":platform:testing"))
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
}
