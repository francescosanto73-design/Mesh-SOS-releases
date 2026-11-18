plugins {
    kotlin("jvm") version "2.0.21"
    application
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    testImplementation(kotlin("test"))
}
application { mainClass.set("it.vault.custode.kdbx.CliKt") }
tasks.test { useJUnitPlatform() }
