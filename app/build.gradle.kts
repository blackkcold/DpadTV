import java.util.Properties
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

plugins {
    id("com.android.application")
}

// ---------- 本机私有配置（两个文件都不入库） ----------
// local.properties  : dpadtv.defaultUrl / dpadtv.defaultPassword
// signing.properties: storeFile / storePassword / keyAlias / keyPassword
fun readProps(name: String): Properties = Properties().apply {
    val f = rootProject.file(name)
    if (f.exists()) f.inputStream().use { load(it) }
}
val localProps = readProps("local.properties")
val signingProps = readProps("signing.properties")

/**
 * 关键设计：凭据是否注入，由 **构建类型** 决定，不由命令行开关决定。
 *
 *  debug   = 自用包：注入 local.properties 里的地址与密码，打开即连
 *  release = 发行包：DEFAULT_URL / DEFAULT_PASSWORD **恒为空字符串**，代码里根本没有
 *            读取 local.properties 的分支，忘加参数也不会泄露
 *
 * 想临时给 release 注入凭据，只能显式 `-Pdpadtv.forcePrivate=true`，会打印警告。
 */
val forcePrivate = project.findProperty("dpadtv.forcePrivate") == "true"
val urlFromProps = localProps.getProperty("dpadtv.defaultUrl").orEmpty()
val pwdFromProps = localProps.getProperty("dpadtv.defaultPassword").orEmpty()
// CLI 覆盖优先于 local.properties（便于临时指向 mock 服务）
val privateUrl = (project.findProperty("dpadtv.defaultUrl") as String?) ?: urlFromProps
val privatePwd = (project.findProperty("dpadtv.defaultPassword") as String?) ?: pwdFromProps

val appName = (project.findProperty("dpadtv.appName") as String?) ?: "Libre Pro"
val appId = (project.findProperty("dpadtv.packageId") as String?) ?: "io.github.blackkcold.librepro"
val testTag = project.findProperty("dpadtv.release") != "true"

if (forcePrivate) {
    logger.warn("⚠️  -Pdpadtv.forcePrivate=true：release 包将包含本机服务器凭据，禁止对外分发")
}
// release 包注入凭据的唯一入口就是这个开关，且它不能绕过 verifyNoSecrets
val releaseUrl = if (forcePrivate) privateUrl else ""
val releasePwd = if (forcePrivate) privatePwd else ""

android {
    namespace = "io.github.blackkcold.librepro"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = appId
        // 23 = Android 6.0：Media3 的下限之上，老盒子都能装
        minSdk = 23
        targetSdk = 37
        versionCode = 106
        versionName = "1.3.1"
        manifestPlaceholders["appName"] = appName
        buildConfigField("String", "APP_NAME", "\"$appName\"")
    }

    signingConfigs {
        // 发行包用专用 keystore；没有 signing.properties 时 release 不签名（构建仍可通过，但不能分发）
        if (signingProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            // 自用包：注入真实凭据，装完打开就能连
            buildConfigField("String", "DEFAULT_URL", "\"$privateUrl\"")
            buildConfigField("String", "DEFAULT_PASSWORD", "\"$privatePwd\"")
            buildConfigField("String", "CHANNEL", "\"private\"")
            buildConfigField("String", "BUILD_SEAL", "\"seal:private\"")
            buildConfigField("boolean", "TEST_BUILD", if (testTag) "true" else "false")
        }
        release {
            isDebuggable = false
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.findByName("release")
            // 发行包：凭据恒为空（除非显式 forcePrivate，且会被 verifyNoSecrets 拦下）
            buildConfigField("String", "DEFAULT_URL", "\"$releaseUrl\"")
            buildConfigField("String", "DEFAULT_PASSWORD", "\"$releasePwd\"")
            buildConfigField("String", "CHANNEL", "\"public\"")
            buildConfigField("String", "BUILD_SEAL", "\"seal:public\"")
            buildConfigField("boolean", "TEST_BUILD", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

/**
 * 发行包硬闸门：解压 APK 扫描真实凭据与内网地址特征，并断言渠道封条。
 * 命中任何一项 → 构建失败，产物视为不可分发。挂在 assembleRelease 后自动执行。
 *
 * 用独立的任务类型（@Input 属性）而不是 doLast 闭包，否则 Gradle 9 的
 * configuration cache 会报 "cannot serialize Gradle script object references"。
 */
abstract class VerifyNoSecretsTask : DefaultTask() {
    @get:Input abstract val serverUrl: Property<String>
    @get:Input abstract val serverPassword: Property<String>
    @get:Internal abstract val apkDir: DirectoryProperty

    @TaskAction
    fun verify() {
        fun ByteArray.hasAscii(needle: String): Boolean {
            if (needle.isEmpty()) return false
            val n = needle.toByteArray(Charsets.UTF_8)
            if (n.size > size) return false
            outer@ for (i in 0..(size - n.size)) {
                for (j in n.indices) if (this[i + j] != n[j]) continue@outer
                return true
            }
            return false
        }

        val url = serverUrl.get()
        val pwd = serverPassword.get()
        val apk = apkDir.get().asFile.walkTopDown().filter { it.extension == "apk" }
            .maxByOrNull { it.lastModified() }
            ?: throw GradleException("没有找到 release APK，先执行 assembleRelease")

        val host = url.removePrefix("http://").removePrefix("https://")
            .substringBefore("/").substringBefore(":")
        val secrets = ArrayList<Pair<String, String>>()
        if (url.isNotEmpty()) secrets.add("服务器地址" to url)
        if (host.isNotEmpty()) secrets.add("服务器主机" to host)
        if (pwd.isNotEmpty()) secrets.add("访问密码" to pwd)

        val leaks = ArrayList<String>()
        val dexText = StringBuilder()
        val zip = ZipFile(apk)
        try {
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val n = entry.name
                if (!(n.endsWith(".dex") || n.endsWith(".arsc") || n.startsWith("res/") || n.startsWith("assets/"))) continue
                val bytes = zip.getInputStream(entry).readBytes()
                for (pair in secrets) if (bytes.hasAscii(pair.second)) leaks.add(pair.first + " 命中于 " + n)
                // 只认「完整的内网 URL 字面量」：单纯的前缀（如挑选本机 IP 时判断
                // startsWith("192.168.")）是正常代码，不能当成本机配置泄露
                for (bad in listOf("http://192.168.", "https://192.168.", "http://10.", "http://127.0.0.1:8899")) {
                    if (bytes.hasAscii(bad)) leaks.add("内网地址字面量 '" + bad + "' 命中于 " + n)
                }
                if (n.endsWith(".dex")) dexText.append(String(bytes, Charsets.ISO_8859_1))
            }
        } finally {
            zip.close()
        }
        if (leaks.isNotEmpty()) {
            throw GradleException(
                "✗ 发行包包含本机配置，已中止：\n" + leaks.joinToString("\n") +
                    "\n请执行 ./gradlew assembleRelease（不要带 -Pdpadtv.forcePrivate=true）"
            )
        }
        if (!dexText.contains("seal:public")) throw GradleException("✗ 缺少 seal:public 封条：渠道标记异常")
        if (dexText.contains("seal:private")) throw GradleException("✗ 带 seal:private 封条：这是自用包，禁止分发")
        println("✓ 发行包自检通过：无凭据 / 无内网地址 / 封条 seal:public  (" + apk.name + " " + apk.length() + " B)")
    }
}

val verifyNoSecrets by tasks.registering(VerifyNoSecretsTask::class) {
    group = "verification"
    description = "确认 release APK 不含服务器地址/密码（发行前必须通过）"
    serverUrl.set(privateUrl)
    serverPassword.set(privatePwd)
    apkDir.set(layout.buildDirectory.dir("outputs/apk/release"))
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy(verifyNoSecrets)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // 原生播放：ExoPlayer 对 HLS 的容错远好于 MediaPlayer，H.265 取决于 SoC 硬解
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    // 二维码生成（纯 Java，仅 core，不含 Android 集成包）
    implementation("com.google.zxing:core:3.5.3")
}
