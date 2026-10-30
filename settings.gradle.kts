pluginManagement {
    includeBuild("build-logic")
}

plugins {
    // lets the java toolchain below download Oracle GraalVM automatically
    id("avis.oracle-graalvm")
}

rootProject.name = "AvisV2"
