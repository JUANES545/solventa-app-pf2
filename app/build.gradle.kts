import java.util.Properties
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

plugins {
    jacoco
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

jacoco {
    toolVersion = "0.8.13"
}

tasks.withType<Test>().configureEach {
    extensions.configure<JacocoTaskExtension> {
        // Robolectric loads application classes through an isolated Android classloader.
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

tasks.register<JacocoReport>("jacocoDebugUnitTestReport") {
    dependsOn("testDebugUnitTest")
    executionData(fileTree(layout.buildDirectory) {
        include("jacoco/testDebugUnitTest.exec", "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
    })
    sourceDirectories.setFrom(files("src/main/java", "src/main/kotlin"))
    val generated = listOf(
        "**/R.class", "**/R\$*.class", "**/BuildConfig.*", "**/Manifest*.*",
        "**/Hilt_*.*", "**/*_Factory.*", "**/*_Factory\$*.*", "**/*_MembersInjector.*",
        "**/*_HiltModules*.*", "**/*_HiltComponents*.*", "**/*_GeneratedInjector.*",
        "**/hilt_aggregated_deps/**", "**/dagger/hilt/internal/**",
    )
    classDirectories.setFrom(files(
        // Hilt rewrites entry points before tests execute them.
        fileTree(layout.buildDirectory.dir("intermediates/classes/debug/transformDebugClassesWithAsm/dirs")) {
            exclude(generated)
        },
    ))
    reports {
        xml.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/coverage/unit/coverage.xml"))
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/coverage/unit/html"))
        csv.required.set(false)
    }
}

val appVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use(::load)
}

android {
    namespace = "co.solventa.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "co.solventa.mobile.pf2"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersion.getProperty("VERSION_CODE").toInt()
        versionName = appVersion.getProperty("VERSION_NAME")
        buildConfigField("String", "AUTH_BASE_URL", "\"https://65qgzhody9.execute-api.us-east-2.amazonaws.com/movil/v1\"")
        buildConfigField("String", "AUTH_ISSUER", "\"https://65qgzhody9.execute-api.us-east-2.amazonaws.com\"")
        buildConfigField("boolean", "BIOMETRIC_LOGIN_ENABLED", "true")
        buildConfigField("boolean", "PHOTO_REVIEW_ENABLED", "true")
        testInstrumentationRunner = "co.solventa.mobile.espresso.SolventaTestRunner"
        val backendE2e = providers.gradleProperty("backendE2e").orNull == "true"
        testInstrumentationRunnerArguments[if (backendE2e) "annotation" else "notAnnotation"] =
            if (backendE2e && providers.gradleProperty("evidenceDeviceTests").orNull == "true") {
                "co.solventa.mobile.espresso.EvidenceDeviceE2e"
            } else if (backendE2e) {
                "co.solventa.mobile.espresso.BackendE2e"
            } else {
                "co.solventa.mobile.espresso.BackendE2e,co.solventa.mobile.espresso.EvidenceDeviceE2e"
            }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    lint {
        abortOnError = true
    }
    buildTypes.create("preview") {
        initWith(buildTypes.getByName("release"))
        signingConfig = signingConfigs.getByName("debug")
        matchingFallbacks += "release"
    }
    buildTypes.getByName("debug") {
        enableUnitTestCoverage = true
    }
    buildTypes.create("biometricTest") {
        initWith(buildTypes.getByName("debug"))
        applicationIdSuffix = ".biometrictest"
        matchingFallbacks += "debug"
        buildConfigField("String", "AUTH_BASE_URL", "\"http://127.0.0.1:18717/movil/v1\"")
        buildConfigField("String", "AUTH_ISSUER", "\"https://identidad.solventa.test\"")
        buildConfigField("boolean", "BIOMETRIC_LOGIN_ENABLED", "true")
        buildConfigField("boolean", "PHOTO_REVIEW_ENABLED", "true")
    }
    sourceSets.getByName("biometricTest") {
        res.srcDir("src/debug/res")
    }
    buildTypes.create("evidenceTest") {
        initWith(buildTypes.getByName("debug"))
        applicationIdSuffix = ".evidencetest"
        matchingFallbacks += "debug"
        buildConfigField("String", "AUTH_BASE_URL", "\"http://127.0.0.1:18719/movil/v1\"")
        buildConfigField("String", "AUTH_ISSUER", "\"urn:solventa:claims-local\"")
    }
    sourceSets.getByName("evidenceTest") {
        res.srcDir("src/debug/res")
    }
    if (providers.gradleProperty("evidenceDeviceTests").orNull == "true") {
        testBuildType = "evidenceTest"
    }
    testCoverage {
        jacocoVersion = "0.8.13"
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.hilt.android)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.kotlinx.coroutines.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockito)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(libs.mockwebserver)
    androidTestImplementation(libs.hilt.testing)
    kspAndroidTest(libs.hilt.compiler)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.video)
    implementation(libs.mlkit.face)
    "biometricTestImplementation"(libs.androidx.compose.ui.test.manifest)
    "evidenceTestImplementation"(libs.androidx.compose.ui.test.manifest)
}
