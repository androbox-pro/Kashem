plugins {
    id("com.android.library")
    `maven-publish`
}

group = "org.fossify"
version = "1.0.0"

android {
    namespace = "com.klinker.android.send_message"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    lint {
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    @Suppress("DEPRECATION")
    useLibrary("org.apache.http.legacy")

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    implementation("com.klinkerapps:logger:1.0.3")
    implementation("com.squareup.okhttp:okhttp:2.5.0")
    implementation("com.squareup.okhttp:okhttp-urlconnection:2.5.0")
}
