// HTTP routes, persistence and other technology live here, behind the application's ports.
plugins {
    id("nothingeats.kotlin-base")
    id("nothingeats.kotlinx-serialization")
}

dependencies {
    implementation(platform(libs.nothingeats.platform.bom))
    api(project(":application"))
    api(libs.nothingeats.platform.http)
    api(libs.nothingeats.platform.persistence)
    api(libs.nothingeats.ktor.posthog)
    api(libs.nothingeats.platform.kafka)
    implementation(libs.bouncycastle.prov)
    implementation(libs.java.jwt)
    implementation(libs.exposed.java.time)

    testImplementation(testFixtures(project(":application")))
    testImplementation(libs.nothingeats.platform.testing)
    testImplementation(libs.ktor.server.test.host)
}
