plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

val libraryGroup = providers.gradleProperty("GROUP").get()
val libraryArtifactId = providers.gradleProperty("ARTIFACT_ID").get()
val libraryVersion = providers.gradleProperty("VERSION_NAME").get()

group = libraryGroup
version = libraryVersion

android {
    namespace = "com.lielu.githubupdater"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    // Fails the build if a declaration is public by accident: the public API is deliberate.
    explicitApi()
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.coroutines.core) // Flow / StateFlow appear in the public API
    implementation(libs.androidx.core) // FileProvider

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json) // org.json is a stub in android.jar on the JVM
}

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                from(components["release"])
                groupId = libraryGroup
                artifactId = libraryArtifactId
                version = libraryVersion

                pom {
                    name.set(providers.gradleProperty("POM_NAME"))
                    description.set(providers.gradleProperty("POM_DESCRIPTION"))
                    url.set(providers.gradleProperty("POM_URL"))
                    licenses {
                        license {
                            name.set(providers.gradleProperty("POM_LICENSE_NAME"))
                            url.set(providers.gradleProperty("POM_LICENSE_URL"))
                        }
                    }
                    developers {
                        developer {
                            id.set(providers.gradleProperty("POM_DEVELOPER_ID"))
                            name.set(providers.gradleProperty("POM_DEVELOPER_NAME"))
                        }
                    }
                    scm {
                        url.set(providers.gradleProperty("POM_SCM_URL"))
                        connection.set(providers.gradleProperty("POM_SCM_CONNECTION"))
                        developerConnection.set(providers.gradleProperty("POM_SCM_DEV_CONNECTION"))
                    }
                }
            }
        }

        // Remote repository: GitHub Packages. Credentials come from the environment
        // (GITHUB_ACTOR / GITHUB_TOKEN in CI). `publishToMavenLocal` needs none.
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri(providers.gradleProperty("PUBLISH_REPO_URL").get())
                credentials {
                    username = System.getenv("GITHUB_ACTOR")
                    password = System.getenv("GITHUB_TOKEN")
                }
            }
        }
    }
}
