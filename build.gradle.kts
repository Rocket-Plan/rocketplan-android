import java.util.Properties

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    id("androidx.navigation.safeargs.kotlin") version "2.6.0" apply false
    id("org.cyclonedx.bom") version "2.2.0"
    id("org.owasp.dependencycheck") version "12.1.0"
}

group = "com.example.rocketplan_android"
version = "1.0.0"

// Load local.properties for NVD API key
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

// OWASP Dependency-Check configuration
dependencyCheck {
    // Fail build on HIGH or CRITICAL vulnerabilities
    failBuildOnCVSS = 7.0f

    // Output formats
    formats = listOf("HTML", "JSON")

    // NVD API key for faster updates (get one at https://nvd.nist.gov/developers/request-an-api-key)
    nvd.apiKey = localProperties.getProperty("nvd.api.key") ?: System.getenv("NVD_API_KEY") ?: ""

    // Suppress false positives (add file if needed)
    // suppressionFile = "config/dependency-check-suppression.xml"

    // Skip test dependencies to focus on runtime vulnerabilities
    skipConfigurations = listOf(
        "testImplementation",
        "testCompileOnly",
        "androidTestImplementation",
        "androidTestCompileOnly"
    )
}