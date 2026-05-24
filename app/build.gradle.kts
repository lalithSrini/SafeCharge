import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // REMOVED: id("com.google.gms.google-services")
    // Firebase google-services plugin is no longer needed.
    // Cloudinary uses plain HTTP — no Google Services plugin required.
}

// ── Load credentials from local.properties (never commit that file) ──────────
val localProps = Properties().also { props ->
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use(props::load)
}
fun localProp(key: String) = localProps.getProperty(key, "")

android {
    namespace = "com.example.safecharge"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.safecharge"
        minSdk = 24
        targetSdk = 36
        versionCode = 5
        versionName = "1.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ── EmailJS credentials ───────────────────────────────────────────────
        // Values come from local.properties — never commit that file.
        buildConfigField("String", "EMAILJS_SERVICE_ID",  "\"${localProp("EMAILJS_SERVICE_ID")}\"")
        buildConfigField("String", "EMAILJS_TEMPLATE_ID", "\"${localProp("EMAILJS_TEMPLATE_ID")}\"")
        buildConfigField("String", "EMAILJS_PUBLIC_KEY",  "\"${localProp("EMAILJS_PUBLIC_KEY")}\"")

        // ── Cloudinary credentials ────────────────────────────────────────────
        // 1. Sign up free at https://cloudinary.com/users/register/free
        // 2. Dashboard → Settings → Upload → Upload presets → Add upload preset
        //      Signing mode : Unsigned
        //      Folder       : thief-photos   (optional)
        //      Save and note the preset name
        // 3. Note your Cloud Name from the dashboard top-left
        // 4. Add to local.properties:
        //      CLOUDINARY_CLOUD_NAME=dxxxxxxxx
        //      CLOUDINARY_UPLOAD_PRESET=thief_preset
        buildConfigField("String", "CLOUDINARY_CLOUD_NAME",    "\"${localProp("CLOUDINARY_CLOUD_NAME")}\"")
        buildConfigField("String", "CLOUDINARY_UPLOAD_PRESET", "\"${localProp("CLOUDINARY_UPLOAD_PRESET")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.biometric)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.extensions)
    implementation(libs.glide)

    // Location — for including GPS coordinates in the alert email
    implementation(libs.play.services.location)

    // REMOVED: Firebase Storage + Firebase Auth
    // Firebase required the Blaze billing plan. If the billing account
    // becomes delinquent, download URLs return HTTP 402 and photos cannot
    // be viewed. Replaced with Cloudinary free tier (no billing required).
    //
    // implementation(platform("com.google.firebase:firebase-bom:34.12.0"))
    // implementation("com.google.firebase:firebase-storage")
    // implementation("com.google.firebase:firebase-auth")

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}