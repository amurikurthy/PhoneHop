import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from environment variables (CI) or an untracked keystore.properties
// file (local builds). If neither is present, assembleRelease produces an unsigned APK.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun signingValue(env: String, prop: String): String? = System.getenv(env) ?: keystoreProps.getProperty(prop)
val keystorePath: String? = System.getenv("KEYSTORE_FILE")
    ?: keystoreProps.getProperty("storeFile")?.let { rootProject.file(it).path }

android {
    namespace = "app.phonehop"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.phonehop"
        minSdk = 29
        targetSdk = 35
        // CI passes these from the git tag (v1.2.3 -> name 1.2.3, code 10203).
        versionName = (findProperty("versionName") as String?) ?: "0.5.3"
        versionCode = ((findProperty("versionCode") as String?) ?: "503").toInt()
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = signingValue("KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // SSH client used to configure the router (maintained fork of JSch with modern algorithms).
    implementation("com.github.mwiede:jsch:0.2.20")

    testImplementation("junit:junit:4.13.2")
    // Embedded SSH server so the client can be tested without a router.
    testImplementation("org.apache.sshd:sshd-core:2.12.1")
    testImplementation("org.slf4j:slf4j-nop:2.0.13")
}
