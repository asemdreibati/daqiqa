plugins {
    id("daqiqa.java-conventions")
}

description = "Published language: integration events exchanged between bounded contexts and with the outside world."

// Deliberately dependency-free (JDK types only): contracts must outlive any internal refactoring.
