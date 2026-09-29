plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm()
    jvmToolchain(25)
    explicitApi()

    sourceSets {
        commonMain.dependencies { api(libs.kotlinx.coroutines) }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// Child JVM fault tests need the same complete runtime classpath as the test worker.
tasks.withType<Test>().configureEach {
    doFirst { systemProperty("yaay.test.classpath", classpath.asPath) }
    // Randomized tests widen their seed range on request: -Pyaay.test.seeds=N -Pyaay.test.seedStart=S
    listOf("yaay.test.seeds", "yaay.test.seedStart").forEach { name -> providers.gradleProperty(name).orNull?.let { systemProperty(name, it) } }
}
