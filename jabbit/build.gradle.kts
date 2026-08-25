import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.publishing)
}

kotlin {
    applyDefaultHierarchyTemplate()

    explicitApi()

    jvmToolchain(21)

    jvm()

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
                "jvmMain" -> dependsOn(standaloneMain)
                "jvmTest" -> dependsOn(standaloneTest)
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

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)

    signAllPublications()

    coordinates(
        groupId = "tech.ryadom",
        artifactId = "jabbit",
        version = "1.1.0"
    )

    pom {
        name.set("Jabbit")
        description.set("Kotlin Multiplatform job runner for Android, iOS, the desktop and the browser, with an API modelled after androidx.work.WorkManager.")
        inceptionYear.set("2026")
        url.set("https://github.com/ryadomtech/jabbit-kmp-job-runner")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0")
                distribution.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }

        developers {
            developer {
                id.set("adkozlovskiy")
                name.set("Alexey Kozlovsky")
                email.set("adkozlovskiy@gmail.com")
            }
        }

        scm {
            url.set("https://github.com/ryadomtech/jabbit-kmp-job-runner")
            connection.set("scm:git:git://github.com/ryadomtech/jabbit-kmp-job-runner.git")
            developerConnection.set("scm:git:ssh://git@github.com/ryadomtech/jabbit-kmp-job-runner.git")
        }
    }
}

