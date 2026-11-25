import java.net.URI
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

val testCoveragePost by tasks.registering(JavaExec::class) {
    classpath = configurations.detachedConfiguration(dependencies.localGroovy())
    mainClass.set("groovy.ui.GroovyMain")
    workingDir(rootProject.projectDir)
    args("infra/jenkins/test-coverage-post.groovy", "Jenkinsfile")
}

tasks.withType<Test>().configureEach {
    if (name == "testDebugUnitTest") {
        dependsOn(testCoveragePost)
    }
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

fun environmentValue(name: String) =
    providers.gradleProperty(name).orElse(providers.environmentVariable(name)).orNull.orEmpty()

fun publicHttps(value: String): URI = URI(value).also { uri ->
    val host = uri.host.orEmpty().lowercase()
    require(uri.scheme == "https" && uri.port in setOf(-1, 443) && uri.rawUserInfo == null &&
        uri.rawQuery == null && uri.rawFragment == null && host.matches(Regex("[a-z0-9.-]+\\.[a-z]{2,}")) &&
        listOf(".localhost", ".local", ".test", ".internal", ".invalid").none(host::endsWith)) {
        "Se requiere una URL HTTPS pública sin credenciales, parámetros ni fragmento."
    }
}

val gateway = environmentValue("SOLVENTA_API_ENDPOINT").trimEnd('/').also {
    if (it.isNotEmpty()) require(!publicHttps(it).path.endsWith("/movil/v1")) {
        "SOLVENTA_API_ENDPOINT debe ser la salida api_endpoint sin el prefijo móvil."
    }
}
val issuer = environmentValue("SOLVENTA_JWT_ISSUER").also {
    if (it.isNotEmpty() && !it.startsWith("urn:")) publicHttps(it)
    if (it.startsWith("urn:")) require(URI(it).isOpaque && URI(it).rawFragment == null)
}
val recoveryWeb = environmentValue("RECOVERY_WEB_URL").trimEnd('/').also {
    if (it.isNotEmpty()) publicHttps(it)
}
val requireReleaseGateway = tasks.register("requireReleaseGateway") {
    doLast {
        require(gateway.isNotEmpty() && issuer.isNotEmpty()) {
            "Configura SOLVENTA_API_ENDPOINT y SOLVENTA_JWT_ISSUER con las salidas del entorno."
        }
    }
}
tasks.matching { it.name in setOf("preReleaseBuild", "prePreviewBuild") }
    .configureEach { dependsOn(requireReleaseGateway) }

android {
    namespace = "co.solventa.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "co.solventa.mobile.pf2"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersion.getProperty("VERSION_CODE").toInt()
        versionName = appVersion.getProperty("VERSION_NAME")
        buildConfigField("String", "AUTH_BASE_URL", "\"${if (gateway.isEmpty()) "" else "$gateway/movil/v1"}\"")
        buildConfigField("String", "AUTH_ISSUER", "\"$issuer\"")
        buildConfigField("boolean", "BIOMETRIC_LOGIN_ENABLED", "true")
        buildConfigField("boolean", "PHOTO_REVIEW_ENABLED", "true")
        buildConfigField("boolean", "SCREEN_CAPTURE_ALLOWED", "false")
        buildConfigField("String", "RECOVERY_LINK_ENTRY", "\"\"")
        buildConfigField("String", "RECOVERY_API_BASE", "\"\"")
        buildConfigField("String", "REGISTRATION_API_BASE", "\"\"")
        buildConfigField("String", "ASSISTANCE_API_BASE", "\"\"")
        testInstrumentationRunner = "co.solventa.mobile.espresso.SolventaTestRunner"
        val backendE2e = providers.gradleProperty("backendE2e").orNull == "true"
        testInstrumentationRunnerArguments[if (backendE2e) "annotation" else "notAnnotation"] =
            if (backendE2e && providers.gradleProperty("claimDeviceTests").orNull == "true") {
                "co.solventa.mobile.espresso.ClaimDeviceE2e"
            } else if (backendE2e && providers.gradleProperty("evidenceDeviceTests").orNull == "true") {
                "co.solventa.mobile.espresso.EvidenceDeviceE2e"
            } else if (backendE2e) {
                "co.solventa.mobile.espresso.BackendE2e"
            } else {
                "co.solventa.mobile.espresso.BackendE2e,co.solventa.mobile.espresso.EvidenceDeviceE2e,co.solventa.mobile.espresso.ClaimDeviceE2e"
            }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildTypes.getByName("release") {
        buildConfigField("String", "REGISTRATION_API_BASE", "AUTH_BASE_URL")
        buildConfigField("String", "RECOVERY_LINK_ENTRY", "\"${if (recoveryWeb.isEmpty()) "" else "$recoveryWeb/"}\"")
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
        buildConfigField("boolean", "SCREEN_CAPTURE_ALLOWED", "true")
    }
    buildTypes.getByName("debug") {
        enableUnitTestCoverage = true
        buildConfigField("boolean", "SCREEN_CAPTURE_ALLOWED", "true")
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
    buildTypes.create("claimTest") {
        initWith(buildTypes.getByName("debug"))
        applicationIdSuffix = ".claimtest"
        matchingFallbacks += "debug"
        buildConfigField("String", "AUTH_BASE_URL", "\"http://127.0.0.1:18721/movil/v1\"")
        buildConfigField("String", "AUTH_ISSUER", "\"urn:solventa:claims-local\"")
    }
    sourceSets.getByName("claimTest") { res.srcDir("src/debug/res") }
    mapOf("recoveryTest" to 45445, "travelTest" to 45446, "autoTest" to 45447, "notificationTest" to 45448, "videoTest" to 45449).forEach { (variant, port) ->
        val certificate = providers.gradleProperty("${variant}Ca").orNull ?: return@forEach
        buildTypes.create(variant) {
            initWith(buildTypes.getByName("debug"))
            applicationIdSuffix = ".${variant.lowercase()}"
            matchingFallbacks += "debug"
            buildConfigField("String", "AUTH_BASE_URL", "\"https://localhost:$port/movil/v1\"")
            val issuer = if (variant != "recoveryTest") "https://identity.local.solventa.test" else "urn:solventa:claims-local"
            buildConfigField("String", "AUTH_ISSUER", "\"$issuer\"")
            if (variant in setOf("recoveryTest", "videoTest")) {
                buildConfigField("String", "RECOVERY_LINK_ENTRY", "\"https://localhost:$port/\"")
            }
            if (variant == "videoTest") buildConfigField("String", "RECOVERY_API_BASE", "\"https://localhost:45450/movil/v1\"")
            if (variant == "videoTest") buildConfigField("String", "REGISTRATION_API_BASE", "\"https://localhost:45451/movil/v1\"")
            if (variant == "videoTest") buildConfigField("String", "ASSISTANCE_API_BASE", "\"https://localhost:45453/movil/v1\"")
        }
        val capitalized = variant.replaceFirstChar { it.uppercase() }
        val output = layout.buildDirectory.dir("generated/$variant-ca")
        val copyCa = tasks.register<Sync>("prepare${capitalized}Ca") {
            from(file(certificate)) { rename { "recovery_ca.crt" } }
            into(output.map { it.dir("raw") })
            doFirst {
                require(file(certificate).readText().trim().matches(
                    Regex("-----BEGIN CERTIFICATE-----[A-Za-z0-9+/=\\s]+-----END CERTIFICATE-----")
                )) { "Se requiere un único certificado público PEM de recuperación." }
            }
        }
        sourceSets.getByName(variant).apply {
            manifest.srcFile(if (variant in setOf("notificationTest","videoTest")) "src/notificationTest/AndroidManifest.xml" else "src/recoveryTest/AndroidManifest.xml")
            res.setSrcDirs(listOf("src/recoveryTest/res", output))
        }
        tasks.matching { it.name == "pre${capitalized}Build" }.configureEach { dependsOn(copyCa) }
    }
    if (providers.gradleProperty("claimDeviceTests").orNull == "true") {
        testBuildType = "claimTest"
    }
    buildTypes.create("awsTest") {
        initWith(buildTypes.getByName("debug"))
        applicationIdSuffix = ".awstest"
        matchingFallbacks += "debug"
    }
    if (providers.gradleProperty("awsDeviceTests").orNull == "true") {
        testBuildType = "awsTest"
        defaultConfig.testInstrumentationRunnerArguments["class"] = "co.solventa.mobile.espresso.AwsAcceptanceDeviceTest"
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
    "claimTestImplementation"(libs.androidx.compose.ui.test.manifest)
    "awsTestImplementation"(libs.androidx.compose.ui.test.manifest)
}
