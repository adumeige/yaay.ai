plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm()
    jvmToolchain(21)
    explicitApi()

    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
