plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.tony.wetypedark"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tony.wetypedark"
        // COUI-Kit 要求 minSdk >= 31；1119 原来是 27
        minSdk = 31
        targetSdk = 37
        versionCode = 2
        versionName = "1.0"
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

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }
}

dependencies {
    compileOnly(files("libs/libxposed-api-102.jar"))
    implementation("androidx.core:core-ktx:1.13.1")

    // Compose + COUI-Kit：主界面按 ColorOS 17 凝光视效重做
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom-alpha:2026.08.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation:1.12.0")
    implementation("androidx.compose.runtime:runtime:1.12.0")
    implementation("androidx.compose.material3:material3")
    implementation("io.github.itoseo:coui:0.0.1-alpha12")
}
