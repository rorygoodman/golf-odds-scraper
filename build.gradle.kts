plugins {
    kotlin("jvm") version "1.9.21"
    application
}

group = "com.golf.odds"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.seleniumhq.selenium:selenium-java:4.16.1")
    implementation("org.slf4j:slf4j-simple:2.0.9")
    implementation("com.google.code.gson:gson:2.10.1")
}

application {
    mainClass.set("com.golf.odds.MainKt")
}

kotlin {
    jvmToolchain(19)
}
