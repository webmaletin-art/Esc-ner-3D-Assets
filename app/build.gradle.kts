import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ---- Firma ----
// Las credenciales llegan por variables de entorno (GitHub Actions secrets) o por keystore.properties local (no versionado).
// NUNCA se guardan claves ni contraseñas en el repositorio.
val localSigning = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(env: String, prop: String): String? = System.getenv(env)?.takeIf { it.isNotBlank() } ?: localSigning.getProperty(prop)
val ksPath = signingValue("ANDROID_KEYSTORE_PATH", "storeFile")
val ksPass = signingValue("ANDROID_KEYSTORE_PASSWORD", "storePassword")
val ksAlias = signingValue("ANDROID_KEY_ALIAS", "keyAlias")
val ksKeyPass = signingValue("ANDROID_KEY_PASSWORD", "keyPassword") ?: ksPass
val hasReleaseSigning = ksPath != null && ksPass != null && ksAlias != null && file(ksPath).exists()

// versionCode estrictamente creciente en CI (número de ejecución del workflow) para permitir actualizar sobre la versión anterior.
val ciRun = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
val computedVersionCode = ciRun ?: 1
val baseVersion = "1.0"

android {
    namespace = "app.universal3d.inspector"
    compileSdk = 36

    defaultConfig {
        // NO CAMBIAR: el applicationId identifica la app para las actualizaciones.
        applicationId = "app.universal3d.inspector"
        minSdk = 26
        targetSdk = 36
        versionCode = computedVersionCode
        versionName = "$baseVersion.$computedVersionCode"
        vectorDrawables.useSupportLibrary = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("distribution") {
                storeFile = file(ksPath!!)
                storePassword = ksPass
                keyAlias = ksAlias
                keyPassword = ksKeyPass
            }
        }
    }

    buildTypes {
        debug {
            // Si hay clave de distribución configurada, el APK de depuración usa la misma firma para poder actualizarse sin desinstalar.
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("distribution")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sin credenciales, la variante release queda SIN FIRMAR (app-release-unsigned.apk) y se informa en el workflow.
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("distribution")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources { excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST") }
    }
    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
        baseline = file("lint-baseline.xml")
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(project(":core3d"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.webkit)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
