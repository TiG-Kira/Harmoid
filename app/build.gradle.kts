plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// UI 设计体系与 KiTerminal-UX 保持一致：Miuix 版本被强制钉死，
// 避免传递依赖把 miuix 拉到不兼容的版本导致玻璃材质 API 漂移。
configurations.all {
    resolutionStrategy {
        force("top.yukonga.miuix.kmp:miuix-ui-android:${libs.versions.miuix.get()}")
        // guava 的 listenablefuture 与 androidx.concurrent-futures 存在 duplicate class
        eachDependency {
            if (requested.group == "com.google.guava" && requested.name == "listenablefuture") {
                useTarget("com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava")
            }
        }
    }
}

android {
    namespace = "com.kira.harmoid"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.kira.harmoid"
        minSdk = 30
        targetSdk = 36
        // 版本号编制规则:
        //   versionName = YY.M.D.B  (年.月.日.当日构建次数)
        //   versionCode = YYMMDDBB  (年年月月日日次次，共 9 位)
        // 示例: 26.5.29.1 → 260529001 (2026-05-29 第 1 次构建)
        versionCode = 261021002
        versionName = "26.10.21.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            // 【安全】签名材料与 KiTerminal-UX 同款（同一私钥库、同一 alias / 同一口令来源）。
            // 私钥本身不入库（见 .gitignore），CI 用 Secrets.JKS_BASE64 解码到 app/ 下；
            // 口令一律外置：Gradle 属性 → 环境变量 → 空。
            // CI 由 Secrets 注入同名环境变量（见 .github/workflows/release_apk.yml）；
            // 本地可 export KI_TERMINAL_STORE_PASSWORD / KI_TERMINAL_KEY_PASSWORD，
            // 或 -Ptermux.storePassword=... -Ptermux.keyPassword=...
            // 口令留空时不污染 debug 构建，但 release 签名任务会直接失败 —— 这是刻意的。
            storeFile = file("ki-terminal-release.jks")
            storePassword = (project.findProperty("termux.storePassword")
                ?: System.getenv("KI_TERMINAL_STORE_PASSWORD")
                ?: "").toString()
            keyAlias = (project.findProperty("termux.keyAlias")
                ?: System.getenv("KI_TERMINAL_KEY_ALIAS")
                ?: "ki-terminal").toString()
            keyPassword = (project.findProperty("termux.keyPassword")
                ?: System.getenv("KI_TERMINAL_KEY_PASSWORD")
                ?: "").toString()
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        viewBinding = true
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += listOf("**/*.so")
        }
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.coroutines)

    // Compose + Miuix（与 KiTerminal-UX 同一套版本 / 同一套组件）
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.compose)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.preference)
    implementation(libs.androidx.navigationevent)
    // 关于页开发者头像（Coil 异步图片）
    implementation(libs.coil.compose)
    // Liquid Glass 取景层：排除 Compose Multiplatform 产物，统一走 androidx.compose
    implementation(libs.kyant.backdrop) {
        exclude(group = "org.jetbrains.compose")
        exclude(group = "org.jetbrains.compose.ui")
        exclude(group = "org.jetbrains.compose.foundation")
    }
    implementation(libs.kyant.shapes) {
        exclude(group = "org.jetbrains.compose")
        exclude(group = "org.jetbrains.compose.ui")
        exclude(group = "org.jetbrains.compose.foundation")
    }

    testImplementation(libs.junit)
    testImplementation("org.json:json:20231013")
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
