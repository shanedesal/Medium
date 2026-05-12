import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.google.devtools.ksp") version "2.0.21-1.0.28"

    id("com.google.gms.google-services")
    id("androidx.navigation.safeargs.kotlin")

}

android {
    namespace = "com.connect.medium"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.connect.medium"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"


        val properties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            properties.load(localPropertiesFile.inputStream())
        }
        buildConfigField("String", "CLOUDINARY_CLOUD_NAME", "\"${properties.getProperty("CLOUDINARY_CLOUD_NAME", "")}\"")
        buildConfigField("String", "CLOUDINARY_UPLOAD_PRESET", "\"${properties.getProperty("CLOUDINARY_UPLOAD_PRESET", "")}\"")
        buildConfigField("String", "CLOUDINARY_API_KEY", "\"${properties.getProperty("CLOUDINARY_API_KEY", "")}\"")
        buildConfigField("String", "CLOUDINARY_API_SECRET", "\"${properties.getProperty("CLOUDINARY_API_SECRET", "")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    val nav_version = "2.9.7"

    implementation("androidx.navigation:navigation-fragment:${nav_version}")
    implementation("androidx.navigation:navigation-ui:${nav_version}")

    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.appcompat:appcompat:1.7.1")

    // Preferences DataStore (SharedPreferences like APIs)
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    // Alternatively - without an Android dependency.
    implementation("androidx.datastore:datastore-preferences-core:1.2.1")
    // Typed DataStore for custom data objects (for example, using Proto or JSON).
    implementation("androidx.datastore:datastore:1.2.1")

    // Alternatively - without an Android dependency.
    implementation("androidx.datastore:datastore-core:1.2.1")

    //coroutines latest
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    val lifecycle_version = "2.10.0"
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:${lifecycle_version}")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:${lifecycle_version}")

    val fragment_version = "1.8.9"

    // Java language implementation
    implementation("androidx.fragment:fragment:$fragment_version")
    implementation("androidx.fragment:fragment-ktx:${fragment_version}")

    implementation(platform("com.google.firebase:firebase-bom:34.10.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")

    // Room Database
    val room_version = "2.8.4"
    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version")
    ksp("androidx.room:room-compiler:$room_version")

    //for image uploading
    implementation("com.github.bumptech.glide:glide:5.0.5")

    // Cloudinary for image storage
    implementation("com.cloudinary:cloudinary-android:3.1.2")

    // WorkManager for background sync
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    //For CircleImageView
    implementation("de.hdodenhof:circleimageview:3.1.0")

    //For RefreshLayout
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")

    //Gson Converter for Cloudinary API
    implementation("com.google.code.gson:gson:2.13.2")

    //video player exoplayer
    val media3_version = "1.9.2"
    implementation("androidx.media3:media3-exoplayer:$media3_version")
    implementation("androidx.media3:media3-ui:${media3_version}")

    //dots indicator for pager multiple medias
    implementation("com.tbuonomo:dotsindicator:5.1.0")

    //Shimmer
    implementation("com.facebook.shimmer:shimmer:0.5.0")

    //gridlayout
    implementation("androidx.gridlayout:gridlayout:1.1.0")

    // Image cropping (gallery picker crop feature)
    implementation("com.vanniktech:android-image-cropper:4.7.0")
}