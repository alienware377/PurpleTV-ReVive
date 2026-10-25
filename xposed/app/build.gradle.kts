import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tv.purple.xp"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.alienware377.purpletvrevive"
        minSdk = 26
        targetSdk = 36
        versionCode = 17
        versionName = "0.9.0-twitch31.1.2"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // Built-in overlay textures: the repo's textures folder is copied in as Java resources under
    // "textures/", with an index of file names, so the module can read them from its own class
    // loader whether LSPosed or LSPatch loads it.
    sourceSets["main"].resources.srcDir(layout.buildDirectory.dir("generated/ptvTextures"))
}

val packTextures by tasks.registering {
    val src = rootProject.file("../textures")
    val out = layout.buildDirectory.dir("generated/ptvTextures/textures")
    inputs.dir(src).optional(true)
    outputs.dir(out)
    doLast {
        val dst = out.get().asFile
        dst.deleteRecursively(); dst.mkdirs()
        val exts = setOf("jpg", "jpeg", "png", "webp")
        val files = (src.listFiles() ?: emptyArray()).filter { it.isFile && it.extension.lowercase() in exts }.sortedBy { it.name }
        files.forEach { it.copyTo(File(dst, it.name), overwrite = true) }
        File(src, "manifest.json").takeIf { it.exists() }?.copyTo(File(dst, "manifest.json"), overwrite = true)
        File(dst, "index.txt").writeText(files.joinToString(System.lineSeparator()) { it.name })
    }
}
tasks.named("preBuild") { dependsOn(packTextures) }

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // org.json ships in android.jar at runtime — compileOnly avoids a duplicate-class dex error.
    compileOnly("org.json:json:20240303")
}
