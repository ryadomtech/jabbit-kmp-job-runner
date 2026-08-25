plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinMultiplatformLibrary)
}

kotlin {
    applyDefaultHierarchyTemplate()

    jvmToolchain(21)

    android {
        namespace = "tech.ryadom.jabbit.demo.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    jvm()

    js {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":jabbit"))
        }
    }
}
