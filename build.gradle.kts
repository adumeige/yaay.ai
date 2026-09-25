plugins {
    base
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
}

allprojects {
    group = "ai.yaay"
    version = "0.1.0-SNAPSHOT"
}

tasks.named("assemble") {
    dependsOn(":crdt:assemble", ":documents:assemble", ":desktopApp:assemble", ":graph:assemble")
}

tasks.named("check") {
    dependsOn(":crdt:check", ":documents:check", ":desktopApp:check", ":graph:check")
}

tasks.named("clean") {
    dependsOn(":crdt:clean", ":documents:clean", ":desktopApp:clean", ":graph:clean")
}
