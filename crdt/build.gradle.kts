plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm()
    jvmToolchain(21)
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
}
