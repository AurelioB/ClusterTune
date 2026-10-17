plugins { id("com.android.application") }

android {
    namespace = "com.aure.clustertune.testapps"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.aure.clustertune.fixture"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    flavorDimensions += "letter"
    productFlavors {
        listOf("A", "B", "C", "D").forEach { letter ->
            create(letter.lowercase()) {
                dimension = "letter"
                applicationIdSuffix = ".${letter.lowercase()}"
                resValue("string", "app_name", "ClusterTune Test $letter")
                resValue("string", "app_letter", letter)
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
