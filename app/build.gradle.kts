import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
}

base {
    archivesName = "kashem"
}

android {
    namespace = "org.fossify.messages"
    compileSdk = libs.versions.app.build.compileSDKVersion.get().toInt()

    defaultConfig {
        applicationId = "com.kashem.shaikh"
        minSdk = libs.versions.app.build.minimumSDK.get().toInt()
        targetSdk = libs.versions.app.build.targetSDK.get().toInt()
        versionName = "1.8.0"
        versionCode = 20

        buildConfigField("String", "APP_ID", ""com.kashem.shaikh"")
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    sourceSets {
        getByName("main").java.directories.add("src/main/kotlin")
    }

    compileOptions {
        val javaVersion = JavaVersion.valueOf(libs.versions.app.build.javaVersion.get())
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }

    tasks.withType<KotlinCompile> {
        compilerOptions.jvmTarget.set(
            JvmTarget.fromTarget(libs.versions.app.build.kotlinJVMTarget.get())
        )
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = true
        warningsAsErrors = false
    }

    bundle {
        language {
            enableSplit = false
        }
    }

    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
    }

    dependenciesInfo {
        includeInApk = false
    }
}

dependencies {
    // Bundled local modules - these no longer come from GitHub/JitPack.
    implementation(project(":commons"))
    implementation(project(":mmslib"))
    implementation(project(":indicator-fast-scroll"))

    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.process)

    implementation(libs.eventbus)
    implementation(libs.ez.vcard)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.bundles.room)
    ksp(libs.androidx.room.compiler)

    implementation(libs.gson)

    implementation(libs.glide)
    ksp(libs.glide.compiler)

    implementation("com.google.android.material:material:${libs.versions.material.get()}")

    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    val cameraxVersion = "1.5.0-alpha02"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("androidx.camera:camera-video:$cameraxVersion")

    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
}
