plugins { alias(libs.plugins.kotlin.multiplatform) }

kotlin {
    jvm()
    jvmToolchain(21)
    explicitApi()
    sourceSets {
        commonMain.dependencies {
            api(project(":crdt"))
            api(libs.kotlinx.coroutines)
        }
        jvmMain.dependencies {
            implementation("org.slf4j:slf4j-api:2.0.17")
            runtimeOnly("org.slf4j:slf4j-jdk14:2.0.17")
            // Immutable timestamped publication of the embedded, shaded distribution.
            implementation("io.youtrackdb:youtrackdb-embedded:0.5.0-20260924.231536-66569b2-dev-20260924.232310-1") {
                isTransitive = false
            }
        }
        commonTest.dependencies { implementation(libs.kotlin.test) }
        jvmTest.dependencies { implementation(project(":documents")) }
    }
}

// Child JVM fault tests need the same complete runtime classpath as the test worker.
tasks.withType<Test>().configureEach {
    doFirst { systemProperty("yaay.test.classpath", classpath.asPath) }
}
