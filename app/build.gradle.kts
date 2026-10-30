plugins {
    id("phoneapi.android.application")
    alias(libs.plugins.kotlin.serialization)
}

val tagVersion: String? = providers.environmentVariable("PHONEAPI_VERSION").orNull

fun versionCodeOf(version: String): Int {
    val (major, minor, patch) =
        version.removePrefix("v").substringBefore('-').split('.').map(String::toInt).let {
            Triple(it[0], it.getOrElse(1) { 0 }, it.getOrElse(2) { 0 })
        }
    return major * 1_000_000 + minor * 1_000 + patch
}

android {
    namespace = "net.die.phoneapi"

    defaultConfig {
        applicationId = providers.gradleProperty("phoneapi.applicationId").get()
        versionName = tagVersion?.removePrefix("v") ?: "0.0.0-dev"
        versionCode = tagVersion?.let(::versionCodeOf) ?: 1
    }

    signingConfigs {
        create("release") {
            val storePath = providers.environmentVariable("PHONEAPI_KEYSTORE_PATH").orNull
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = providers.environmentVariable("PHONEAPI_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("PHONEAPI_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("PHONEAPI_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (providers.environmentVariable("PHONEAPI_KEYSTORE_PATH").isPresent) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    packaging {
        resources {
            excludes +=
                setOf(
                    "META-INF/INDEX.LIST",
                    "META-INF/io.netty.versions.properties",
                    "META-INF/DEPENDENCIES",
                    "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                    "META-INF/LICENSE*",
                    "META-INF/NOTICE*",
                    "META-INF/license/**",
                )
        }
    }
}

configurations.configureEach {
    // Desktop-native Netty transports and HTTP/3 codecs can't load on Android; NIO is used instead.
    exclude(group = "io.netty", module = "netty-codec-native-quic")
    exclude(group = "io.netty", module = "netty-codec-classes-quic")
    exclude(group = "io.netty", module = "netty-codec-http3")
    exclude(group = "io.netty", module = "netty-transport-native-epoll")
    exclude(group = "io.netty", module = "netty-transport-native-kqueue")
    exclude(group = "io.netty", module = "netty-transport-native-io_uring")
}

dependencies {
    constraints {
        // activity 1.13 still requests core-ktx 1.18. Those Kotlin extensions now live in core
        // 1.19, so the older jar duplicates them.
        implementation(libs.androidx.core.ktx)
    }
    implementation(project(":api-model"))
    implementation(project(":helper"))

    implementation(libs.androidx.core)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.common)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.kotlinx.coroutines.core)
    runtimeOnly(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.io.core)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.http)
    implementation(libs.ktor.io)
    implementation(libs.ktor.utils)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.events)
    implementation(libs.ktor.http.cio)
    implementation(libs.jspecify)
    implementation(libs.slf4j.api)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.websockets)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization)
    implementation(libs.ktor.serialization.json)
    implementation(libs.mcp.server)
    implementation(libs.mcp.core)

    implementation(libs.kadb)
    implementation(libs.kadb.mdns)
    // AdbStream.source and AdbStream.sink are Okio types.
    implementation(libs.okio)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mcp.client)
}
