import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    applyDefaultHierarchyTemplate()

    explicitApi()

    jvmToolchain(21)

    android {
        namespace = "tech.ryadom.jabbit"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        withHostTest {}
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            baseName = "Jabbit"
            isStatic = true
        }
    }

    js {
        browser()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        val standaloneMain = create("standaloneMain") {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.kotlinx.serialization.json)
            }
        }

        val standaloneTest = create("standaloneTest") {
            dependsOn(commonTest.get())
        }

        val browserMain = create("browserMain") {
            dependsOn(standaloneMain)
            dependencies {
                implementation(libs.kotlinx.browser)
            }
        }

        val browserTest = create("browserTest") {
            dependsOn(standaloneTest)
        }

        configureEach {
            when (name) {
                "appleMain" -> dependsOn(standaloneMain)
                "appleTest" -> dependsOn(standaloneTest)
                "jsMain", "wasmJsMain" -> dependsOn(browserMain)
                "jsTest", "wasmJsTest" -> dependsOn(browserTest)
            }
        }

        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.core)
        }

        androidMain.dependencies {
            api(libs.androidx.work.runtime)
            implementation(libs.kotlinx.coroutines.android)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
