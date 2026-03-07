import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

fun localProperty(name: String, defaultValue: String = ""): String =
    localProperties.getProperty(name, defaultValue)

android {
    namespace = "org.kvj.habtproxy"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.kvj.habtproxy"
        minSdk = 26
        //noinspection EditedTargetSdkVersion
        targetSdk = 34
        versionCode = 1001
        versionName = "0.1.1"

        buildConfigField("boolean", "DEFAULT_PROXY_ENABLED", "true")
        buildConfigField("boolean", "DEFAULT_OPTIMIZE_BACKGROUND", "false")
        buildConfigField("int", "DEFAULT_SCAN_DURATION_SECONDS", "5")
        buildConfigField("int", "DEFAULT_SCAN_INTERVAL_SECONDS", "60")
        buildConfigField("int", "DEFAULT_UPLOAD_INTERVAL_SECONDS", "60")
        buildConfigField("String", "DEFAULT_WEBHOOK", "\"${localProperty("btproxy.defaultWebhook").replace("\\", "\\\\").replace("\"", "\\\"")}\"")

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            resValue("string", "app_name", "* HA Bluetooth Proxy")
        }
        release {
            applicationIdSuffix = ".release"
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    flavorDimensions += "version"
    productFlavors {
        create("default") {
        }
        create("fdroid") {
            applicationIdSuffix = ".fdroid"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.4.3"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.preference:preference:1.2.1")
    implementation("com.google.android.material:material:1.4.+")
    val work_version = "2.8.1"

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.0")
    implementation(platform("androidx.compose:compose-bom:2023.03.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.work:work-runtime:$work_version")
    implementation("androidx.work:work-runtime-ktx:$work_version")

    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.datastore:datastore-preferences:1.0.0")


    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
