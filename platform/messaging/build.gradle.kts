plugins {
    id("daqiqa.spring-module-conventions")
}

description = "Transactional outbox: reliable publication of integration events to Kafka."

dependencies {
    api(project(":platform:contracts"))
    implementation(project(":platform:shared-kernel"))
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    testImplementation(project(":platform:testing"))
    testImplementation("org.springframework.boot:spring-boot-starter-kafka-test")
}
