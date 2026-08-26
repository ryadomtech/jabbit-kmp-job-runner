import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.publishing)
    alias(libs.plugins.dokka)
}

kotlin {
    applyDefaultHierarchyTemplate()

    explicitApi()

    jvmToolchain(21)

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
            freeCompilerArgs.add("-Xjdk-release=11")
        }
    }

    android {
        namespace = "tech.ryadom.jabbit"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        withHostTest {
            isIncludeAndroidResources = true
        }
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
            api(libs.kotlinx.serialization.core)
            implementation(libs.kotlinx.serialization.json)
        }

        androidMain.dependencies {
            api(libs.androidx.work.runtime)
            implementation(libs.kotlinx.coroutines.android)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }

        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
            implementation(libs.robolectric)
            implementation(libs.androidx.test.core)
            implementation(libs.androidx.work.testing)
        }
    }
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)

    signAllPublications()

    coordinates(
        groupId = "tech.ryadom",
        artifactId = "jabbit",
        version = "2.0.0"
    )

    pom {
        name.set("Jabbit")
        description.set("Kotlin Multiplatform job runner for Android, iOS, the desktop and the browser, with an API like in androidx.work.WorkManager.")
        inceptionYear.set("2026")
        url.set("https://github.com/ryadomtech/jabbit-kmp-job-runner")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/license/mit")
                distribution.set("https://opensource.org/license/mit")
            }
        }

        developers {
            developer {
                id.set("adkozlovskiy")
                name.set("Aleksei Kozlovskiy")
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

dokka {
    moduleName.set("Jabbit")

    dokkaSourceSets.configureEach {
        includes.from("module.md")

        perPackageOption {
            matchingRegex.set(".*\\.internal.*")
            suppress.set(true)
        }

        sourceLink {
            localDirectory.set(file("src"))
            remoteUrl("https://github.com/ryadomtech/jabbit-kmp-job-runner/tree/main/jabbit/src")
            remoteLineSuffix.set("#L")
        }
    }

    dokkaPublications.html {
        outputDirectory.set(rootProject.layout.buildDirectory.dir("dokka/html"))
    }
}
