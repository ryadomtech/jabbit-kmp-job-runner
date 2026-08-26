import kotlinx.validation.ExperimentalBCVApi

plugins {
    alias(libs.plugins.kotlinMultiplatform).apply(false)
    alias(libs.plugins.kotlinJvm).apply(false)
    alias(libs.plugins.kotlinMultiplatformLibrary).apply(false)
    alias(libs.plugins.kotlinSerialization).apply(false)
    alias(libs.plugins.androidApplication).apply(false)
    alias(libs.plugins.composeMultiplatform).apply(false)
    alias(libs.plugins.composeCompiler).apply(false)
    alias(libs.plugins.binaryCompatibilityValidator)
    alias(libs.plugins.publishing).apply(false)
}

apiValidation {
    ignoredProjects += listOf("shared", "androidApp", "desktopApp", "webApp")

    @OptIn(ExperimentalBCVApi::class)
    klib {
        enabled = true
    }
}
