plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm()
    jvmToolchain(21)
    explicitApi()

    sourceSets {
        jvmMain.dependencies { api(project(":graph")) }
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

// Child JVM fault tests need the same complete runtime classpath as the test worker.
tasks.withType<Test>().configureEach {
    doFirst { systemProperty("yaay.test.classpath", classpath.asPath) }
}
