plugins {
    java
    application
    id("com.gradleup.shadow") version "9.0.0"
}

group = "com.remitly"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    
    // Property-based testing (jqwik)
    testImplementation("net.jqwik:jqwik:1.9.2")
}

tasks.test {
    useJUnitPlatform {
        includeEngines("junit-jupiter", "jqwik")
    }
}

tasks.withType<JavaCompile> {
}

tasks.withType<JavaExec> {
}

application {
    mainClass.set("com.remitly.stockexchange.Application")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.remitly.stockexchange.Application"
    }
}
