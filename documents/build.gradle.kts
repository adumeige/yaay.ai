plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm()
    jvmToolchain(21)
    explicitApi()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":crdt"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// Standalone JVM peer for transport/recovery verification and headless deployments.
tasks.register<JavaExec>("runPeer") {
    val compilation = kotlin.targets.getByName("jvm").compilations.getByName("main")
    dependsOn(compilation.compileTaskProvider)
    classpath(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    mainClass.set("ai.yaay.documents.PeerMain")
    standardInput = System.`in`
}
