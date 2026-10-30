// settings plugins used by the main build (see ../settings.gradle.kts)
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

gradlePlugin {
    plugins {
        register("oracleGraalVM") {
            id = "avis.oracle-graalvm"
            implementationClass = "OracleGraalVMPlugin"
        }
    }
}
