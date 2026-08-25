plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":demo:shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
}

compose.desktop {
    application {
        mainClass = "tech.ryadom.jabbit.demo.desktop.MainKt"
    }
}
