// Composition root and entry point. The only module that knows every other module.
plugins {
    id("nothingeats.kotlin-service")
}

application {
    mainClass = "io.github.slashncode.nothingeats.identity.app.MainKt"
}

dependencies {
    implementation(platform(libs.nothingeats.platform.bom))
    implementation(project(":adapters"))

    testImplementation(libs.nothingeats.platform.testing)
    testImplementation(libs.konsist)
}
