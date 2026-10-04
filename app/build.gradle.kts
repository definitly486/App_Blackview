import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}




android {
    namespace = "com.example.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.app"
        minSdk = 35
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")

        val gitBranch = safeGitLabel(gitBranch())
        val gitCommitShort = safeGitLabel(gitCommitShort())
        val gitCommitFull = safeGitLabel(gitCommitFull())

        versionNameSuffix = "-$gitBranch"

        buildConfigField("String", "GIT_BRANCH", quoteBuildConfig(gitBranch))
        buildConfigField("String", "GIT_COMMIT_SHORT", quoteBuildConfig(gitCommitShort))
        buildConfigField("String", "GIT_COMMIT_FULL", quoteBuildConfig(gitCommitFull))
        buildConfigField("String", "VERSION_NAME_SUFFIX", quoteBuildConfig("-$gitBranch"))
        buildConfigField("String", "FULL_VERSION_NAME", quoteBuildConfig("$versionName-$gitBranch"))

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
        }

      release {
    signingConfig = signingConfigs.getByName("debug")

    isMinifyEnabled = true
    isShrinkResources = true
    isDebuggable = false
    applicationIdSuffix = null
    versionNameSuffix = null

    proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
    )
}
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    // В APK остаются только ресурсы библиотек (Material, AppCompat) на этих языках
    androidResources {
        localeFilters += listOf("ru", "en")
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.version",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/versions/**",
                "META-INF/**/module-info.class",
                "**/*.kotlin_module",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
                // английский JGitText.properties остаётся, переводы не нужны
                "org/eclipse/jgit/internal/JGitText_*.properties"
            )
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        aidl = true
    }
}

android.applicationVariants.all {
    outputs.all {

        val date = getDate()
        val commit = gitCommitShort()
        val buildTypeName = buildType.name
        val version = versionName

        (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
            "app_Blackview_${buildTypeName}_v${version}_${date}_${commit}.apk"
    }
}

fun getDate(): String =
    SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())

private fun gitBranch() = runGitCommand("rev-parse", "--abbrev-ref", "HEAD")
private fun gitCommitShort() = runGitCommand("rev-parse", "--short", "HEAD")
private fun gitCommitFull() = runGitCommand("rev-parse", "HEAD")

private fun runGitCommand(vararg args: String): String =
    try {
        providers.exec {
            commandLine("git", *args)
            workingDir = project.rootProject.projectDir
        }.standardOutput.asText.get().trim().takeIf {
            it.isNotEmpty() && it != "HEAD"
        } ?: "unknown"
    } catch (_: Exception) {
        "unknown"
    }

private fun safeGitLabel(value: String): String =
    value.replace(Regex("[^A-Za-z0-9._-]"), "-").trim('-').ifBlank { "unknown" }

private fun quoteBuildConfig(value: String): String = "\"" + value + "\""


dependencies {
    implementation(libs.bouncycastle.provider)
    implementation(libs.bouncycastle.pgp)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material)
    implementation(libs.org.eclipse.jgit)
    implementation(libs.androidx.viewpager2)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)

    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
