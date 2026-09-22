plugins {
    id("daqiqa.java-conventions")
}

description = "Shared kernel: identifiers and value objects every bounded context speaks. No frameworks, no I/O."

dependencies {
    implementation(libs.java.uuid.generator)
    testImplementation(libs.jqwik)
}
