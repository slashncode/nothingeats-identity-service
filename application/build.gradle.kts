// Use cases and ports. Knows the domain and the platform error model, nothing about HTTP or SQL.
plugins {
    id("nothingeats.kotlin-base")
    `java-test-fixtures`
}

dependencies {
    implementation(platform(libs.nothingeats.platform.bom))
    api(project(":domain"))
    api(libs.nothingeats.platform.core)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.nothingeats.platform.testing)
}
