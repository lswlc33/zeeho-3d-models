import org.gradle.api.GradleException
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    id("com.android.application")
}

val appVersionCode = (project.findProperty("webnext.versionCode") as String).toInt()
val appVersionName = project.findProperty("webnext.versionName") as String

// 签名：keystore.properties 不进公开仓库（.gitignore），只在本机构建时提供。
// 没有该文件时 release 仍能编译（只是不签名），方便别人 clone 后先跑 debug。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps: Map<String, String> = if (keystorePropsFile.exists()) {
    keystorePropsFile.readLines().mapNotNull { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) return@mapNotNull null
        val eq = line.indexOf('=')
        if (eq <= 0) null else line.substring(0, eq).trim() to line.substring(eq + 1).trim()
    }.toMap()
} else {
    emptyMap()
}
val hasReleaseKey = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .all { !keystoreProps[it].isNullOrBlank() }

android {
    namespace = "io.github.lswlc33.zeehowebnext"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.lswlc33.zeehowebnext"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKey) {
                storeFile = rootProject.file(keystoreProps.getValue("storeFile"))
                storePassword = keystoreProps["storePassword"]
                keyAlias = keystoreProps["keyAlias"]
                keyPassword = keystoreProps["keyPassword"]
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// 源码里有中文注释；别让默认编码（Windows 上是 GBK）把编译搞坏。
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// 发布产物固定为 app/zeehowebnext.apk（下载地址不能变）。
// 不用 Copy 任务：目的地 app/ 同时是工程根，Gradle 会把 .gradle/build 也当成任务输出，
// 既会哈希被占用的 checksums.lock，又会和 AGP 的其它任务互相报隐式依赖。这里在 doLast 里搬一个文件。
tasks.register("exportApk") {
    group = "release"
    description = "构建 release 并导出为 app/zeehowebnext.apk"
    dependsOn("assembleRelease")
    doNotTrackState("导出到 app/ 根目录，不参与增量跟踪")
    doLast {
        if (!hasReleaseKey) {
            throw GradleException(
                "缺少 keystore.properties（storeFile/storePassword/keyAlias/keyPassword 四项），无法发布正式包"
            )
        }
        val src = layout.buildDirectory.file("outputs/apk/release/webnext-release.apk").get().asFile
        if (!src.isFile) throw GradleException("release APK 不存在：$src")
        val dst = rootProject.file("zeehowebnext.apk")
        src.copyTo(dst, overwrite = true)
        println("导出 ${src.name} -> ${dst.absolutePath}")
    }
}
