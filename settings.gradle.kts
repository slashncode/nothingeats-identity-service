// Uses the sibling checkout of nothingeats-kotlin-platform when present (composite build),
// otherwise the published platform from GitHub Packages (needs GITHUB_ACTOR + GITHUB_TOKEN).
pluginManagement {
    val platformDir = file("../nothingeats-kotlin-platform").canonicalFile
    val platformVersion = providers.gradleProperty("nothingeatsPlatformVersion").get()
    if (platformDir.exists()) {
        includeBuild(platformDir.resolve("build-logic"))
    } else {
        resolutionStrategy {
            eachPlugin {
                if (requested.id.namespace == "nothingeats") useVersion(platformVersion)
            }
        }
    }
    repositories {
        gradlePluginPortal()
        mavenCentral()
        if (!platformDir.exists()) {
            maven("https://maven.pkg.github.com/slashncode/nothingeats-kotlin-platform") {
                credentials {
                    username = System.getenv("GITHUB_ACTOR")
                    password = System.getenv("GITHUB_TOKEN")
                }
            }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

val platformDir = file("../nothingeats-kotlin-platform").canonicalFile
val platformVersion = providers.gradleProperty("nothingeatsPlatformVersion").get()

if (platformDir.exists()) includeBuild(platformDir)

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        if (!platformDir.exists()) {
            maven("https://maven.pkg.github.com/slashncode/nothingeats-kotlin-platform") {
                credentials {
                    username = System.getenv("GITHUB_ACTOR")
                    password = System.getenv("GITHUB_TOKEN")
                }
            }
        }
    }
    versionCatalogs {
        create("libs") {
            if (platformDir.exists()) {
                from(files(platformDir.resolve("gradle/libs.versions.toml")))
            } else {
                from("io.github.slashncode.nothingeats:version-catalog:$platformVersion")
            }
        }
    }
}

rootProject.name = "nothingeats-identity-service"

include("domain", "application", "adapters", "app")
