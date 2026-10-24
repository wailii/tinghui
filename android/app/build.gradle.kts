import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 阿里百炼的 key 放在仓库外层的 secrets.properties，不进代码
val secrets = Properties().apply {
    val f = rootProject.file("../secrets.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.xiaodeng.tinghui"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.xiaodeng.tinghui"
        minSdk = 31
        targetSdk = 36
        versionCode = 22
        versionName = "2.11"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // mine：自己手机用，带上各种 Key 和名字；share：发给别人，什么都不带，打开后自己填
    flavorDimensions += "who"
    productFlavors {
        create("mine") {
            dimension = "who"
            buildConfigField("String", "DASHSCOPE_KEY", "\"${secrets.getProperty("DASHSCOPE_API_KEY", "")}\"")
            buildConfigField("String", "LLM_URL", "\"${secrets.getProperty("LLM_URL", "")}\"")
            buildConfigField("String", "LLM_MODEL", "\"${secrets.getProperty("LLM_MODEL", "")}\"")
            buildConfigField("String", "LLM_KEY", "\"${secrets.getProperty("LLM_KEY", "")}\"")
            buildConfigField("String", "CALDAV_USER", "\"${secrets.getProperty("CALDAV_USER", "")}\"")
            buildConfigField("String", "CALDAV_PASS", "\"${secrets.getProperty("CALDAV_PASS", "")}\"")
            // 飞书：CodeX 应用、你的 open_id、「听会」空间（JSON），自己装的包直接带上
            listOf("FEISHU_APP_ID", "FEISHU_APP_SECRET", "FEISHU_OPEN_ID", "FEISHU_SPACE").forEach { k ->
                val v = secrets.getProperty(k, "").replace("\\", "\\\\").replace("\"", "\\\"")
                buildConfigField("String", k, "\"$v\"")
            }
            buildConfigField("String", "FEISHU_USER_NAME", "\"${String(secrets.getProperty("FEISHU_USER_NAME", "").toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)}\"")
            // 助手的名字：自己用的叫「小灯」，分享出去的叫「听会助理」
            buildConfigField("String", "ASSISTANT", "\"小灯\"")
            // 热词：常一起开会的人、产品名，逗号隔开
            buildConfigField("String", "HOTWORDS", "\"${String(secrets.getProperty("HOTWORDS", "").toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)}\"")
        }
        create("share") {
            dimension = "who"
            listOf("DASHSCOPE_KEY", "LLM_URL", "LLM_MODEL", "LLM_KEY", "FEISHU_USER_NAME", "CALDAV_USER", "CALDAV_PASS", "FEISHU_APP_ID", "FEISHU_APP_SECRET", "FEISHU_OPEN_ID", "FEISHU_SPACE", "HOTWORDS").forEach {
                buildConfigField("String", it, "\"\"")
            }
            buildConfigField("String", "ASSISTANT", "\"听会助理\"")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        noCompress += "onnx"
    }

    packaging {
        jniLibs { useLegacyPackaging = false }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(files("libs/sherpa-onnx-static-1.13.8.aar"))
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.3")
    // CalDAV 要发 PROPFIND / REPORT，系统自带的 HttpURLConnection 不让
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
