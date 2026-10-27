import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// AGP 9 は Kotlin を内蔵しているので org.jetbrains.kotlin.android は付けない
// (Kotlin の版はルートの build.gradle.kts で指定している)
plugins {
    id("com.android.application")
}

/**
 * リリース署名の設定ファイル (storeFile / storePassword / keyAlias / keyPassword)。
 * 鍵とパスワードはリポジトリに入れず、local.properties の signing.properties でファイルの場所を指す。
 * 無いまま APK を作るリリース (assembleRelease / bundleRelease) は失敗にする (署名なしの APK を配る事故を防ぐ)。
 * 手元の確認で署名なしが欲しいときだけ -Padblocker.allowUnsigned=true で通す。lint やテストは鍵が無くても動く。
 */
val releaseSigning: Properties? = run {
    val local = Properties()
    rootProject.file("local.properties").takeIf { it.exists() }?.reader()?.use { local.load(it) }
    val path = local.getProperty("signing.properties") ?: return@run null
    val file = file(path).takeIf { it.exists() } ?: return@run null
    Properties().apply { file.reader().use { load(it) } }
}

if (releaseSigning == null && gradle.startParameter.taskNames.any { Regex("(assemble|bundle|package)[A-Za-z]*Release", RegexOption.IGNORE_CASE).containsMatchIn(it) }
    && findProperty("adblocker.allowUnsigned") != "true"
) {
    throw GradleException("署名の設定が見つかりません (local.properties の signing.properties)。リリースを作れません (署名なしの APK は配ると更新できなくなります)。" +
        "鍵の設定を用意するか、確認だけなら -Padblocker.allowUnsigned=true を付けてください")
}

android {
    namespace = "io.github.tonbo2339.adblocker"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.tonbo2339.adblocker"
        minSdk = 26
        targetSdk = 37
        versionCode = 16
        versionName = "0.9.0"
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
                // v3 を付けておくと、将来鍵を替えるときに既存のアプリを上書き更新できる (鍵ローテーション)
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        // debug 版は別のアプリとして入る (パッケージ名に .debug、名前は「AdBlocker (debug)」)。リリース版と並べて入れられる。
        // 設定・ログ・VPN の許可はリリース版と別。アプリ内の更新は署名が合わないので使えない (使わない)。
        // shortcuts.xml だけパッケージ名を直書きしているので、src/debug/res/xml/shortcuts.xml で上書きしている
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            // 使っていないコードとリソースを除いて APK を小さくする
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // 端末の設定 (Android 13 以上) でアプリごとに言語を選べるようにする
    androidResources {
        generateLocaleConfig = true
        // アプリが対応している言語だけ残す (ライブラリに入っている他の言語の翻訳を外す)
        localeFilters += listOf("en", "ja")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")

    testImplementation("junit:junit:4.13.2")
}
