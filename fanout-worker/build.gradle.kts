plugins {
    java
    jacoco
    id("info.solidsoft.pitest") version "1.15.0"
}

group = "com.caseware.templateupdates"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

if (projectDir.path.contains("OneDrive")) {
    layout.buildDirectory = file(System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir")).resolve("fanout-worker-build")
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

pitest {
    jvmPath = javaToolchains.launcherFor(java.toolchain).map { it.executablePath.asFile }
    junit5PluginVersion = "1.2.1"
    pitestVersion = "1.17.4"
    targetClasses = setOf("com.caseware.templateupdates.*")
    mutationThreshold = 90
    timestampedReports = false
    excludedGroups = setOf("component")
    outputFormats = setOf("HTML", "XML")
}
