import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Properties
import java.util.zip.Adler32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    id("com.android.application")
}

val appMinSdk = 31

fun readIntLe(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xff) or
        ((bytes[offset + 1].toInt() and 0xff) shl 8) or
        ((bytes[offset + 2].toInt() and 0xff) shl 16) or
        ((bytes[offset + 3].toInt() and 0xff) shl 24)

fun writeIntLe(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = value.toByte()
    bytes[offset + 1] = (value shr 8).toByte()
    bytes[offset + 2] = (value shr 16).toByte()
    bytes[offset + 3] = (value shr 24).toByte()
}

fun readUleb128(bytes: ByteArray, offsetRef: IntArray): Int {
    var result = 0
    var shift = 0
    var offset = offsetRef[0]
    repeat(5) {
        check(offset < bytes.size) { "Truncated DEX ULEB128 value" }
        val value = bytes[offset++].toInt() and 0xff
        result = result or ((value and 0x7f) shl shift)
        if ((value and 0x80) == 0) {
            offsetRef[0] = offset
            return result
        }
        shift += 7
    }
    error("Invalid DEX ULEB128 value")
}

fun updateDexHashes(bytes: ByteArray) {
    val sha1 = MessageDigest.getInstance("SHA-1")
    sha1.update(bytes, 32, bytes.size - 32)
    System.arraycopy(sha1.digest(), 0, bytes, 12, 20)

    val adler32 = Adler32()
    adler32.update(bytes, 12, bytes.size - 12)
    writeIntLe(bytes, 8, adler32.value.toInt())
}

fun forEachDexDebugReference(bytes: ByteArray, visit: (offset: Int, clearedValue: Int) -> Unit) {
    check(bytes.size >= 112 && bytes.copyOfRange(0, 4).contentEquals("dex\n".toByteArray())) {
        "Invalid DEX input"
    }
    val classDefsSize = readIntLe(bytes, 0x60)
    val classDefsOff = readIntLe(bytes, 0x64)

    for (classIndex in 0 until classDefsSize) {
        val classDefOff = classDefsOff + classIndex * 32
        visit(classDefOff + 16, -1)

        val classDataOff = readIntLe(bytes, classDefOff + 24)
        if (classDataOff == 0) continue

        val cursor = intArrayOf(classDataOff)
        val staticFieldsSize = readUleb128(bytes, cursor)
        val instanceFieldsSize = readUleb128(bytes, cursor)
        val directMethodsSize = readUleb128(bytes, cursor)
        val virtualMethodsSize = readUleb128(bytes, cursor)

        repeat(staticFieldsSize + instanceFieldsSize) {
            readUleb128(bytes, cursor)
            readUleb128(bytes, cursor)
        }

        repeat(directMethodsSize + virtualMethodsSize) {
            readUleb128(bytes, cursor)
            readUleb128(bytes, cursor)
            val codeOff = readUleb128(bytes, cursor)
            if (codeOff != 0) {
                visit(codeOff + 8, 0)
            }
        }
    }
}

fun stripDexDebugInfo(input: ByteArray): ByteArray = input.clone().also { bytes ->
    forEachDexDebugReference(bytes) { offset, value -> writeIntLe(bytes, offset, value) }
    updateDexHashes(bytes)
}

fun verifyDexDebugInfoRemoved(bytes: ByteArray) {
    forEachDexDebugReference(bytes) { offset, value ->
        check(readIntLe(bytes, offset) == value) { "DEX still contains a debug reference" }
    }
    val mapOffset = readIntLe(bytes, 0x34)
    repeat(readIntLe(bytes, mapOffset)) { index ->
        val type = readIntLe(bytes, mapOffset + 4 + index * 12) and 0xffff
        check(type != 0x2003) { "DEX still contains debug_info_item data" }
    }
}

fun dexEntryOrder(name: String): Int {
    val suffix = Regex("""classes(\d*)\.dex""").matchEntire(name)?.groupValues?.get(1)
    return suffix?.takeIf { it.isNotEmpty() }?.toInt() ?: 1
}

fun shouldDropApkEntry(entry: ZipEntry, dexEntryName: Regex): Boolean {
    val name = entry.name
    return dexEntryName.matches(name) ||
        (name == "resources.arsc" && entry.size == 40L) ||
        name == "META-INF/MANIFEST.MF" ||
        name.startsWith("META-INF/com/") ||
        (name.startsWith("META-INF/") &&
            (name.endsWith(".RSA") || name.endsWith(".DSA") ||
                name.endsWith(".EC") || name.endsWith(".SF")))
}

val localProperties = Properties().apply {
    val file = project.rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

android {
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "ka.xpomni"
        minSdk = appMinSdk
        targetSdk = 37
        versionCode = 16
        versionName = "1.3.3"
    }

    signingConfigs {
        if (localProperties.getProperty("storeFile") != null) {
            create("config") {
                storeFile = project.rootProject.file(localProperties.getProperty("storeFile"))
                storePassword = localProperties.getProperty("storePassword")
                keyAlias = localProperties.getProperty("keyAlias")
                keyPassword = localProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        configureEach {
            vcsInfo.include = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            if (localProperties.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.getByName("config")
            }
        }
        release {
            signingConfig = if (localProperties.getProperty("storeFile") != null) {
                signingConfigs.getByName("config")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    lint {
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += setOf(
                "kotlin/**",
                "META-INF/com/**",
                "META-INF/*.kotlin_module",
                "META-INF/kotlin*",
                "META-INF/versions/**",
                "DebugProbesKt.bin",
            )
        }
    }

    dependenciesInfo.includeInApk = false
    enableKotlin = true
    namespace = "ka.xpomni"
}

dependencies {
    compileOnly("androidx.annotation:annotation:1.9.1")
    compileOnly("io.github.libxposed:api:102.0.0")
}

val rawReleaseApk = layout.buildDirectory.file("outputs/apk/release/app-release.apk")
val processedReleaseApk = layout.buildDirectory.file("outputs/apk/minimalRelease/app-release.apk")
val windows = System.getProperty("os.name").lowercase(Locale.ROOT).contains("windows")
val buildTools = androidComponents.sdkComponents.sdkDirectory.map {
    it.dir("build-tools/${android.buildToolsVersion}")
}
val d8Jar = buildTools.map { it.file("lib/d8.jar") }
val zipalign = buildTools.map { it.file(if (windows) "zipalign.exe" else "zipalign") }
val apksigner = buildTools.map { it.file(if (windows) "apksigner.bat" else "apksigner") }
val javaExecutable = File(System.getProperty("java.home"), "bin/${if (windows) "java.exe" else "java"}")
val releaseSigning = android.buildTypes.getByName("release").signingConfig

val stripReleaseDexDebugInfo = tasks.register("stripReleaseDexDebugInfo") {
    dependsOn("packageRelease")
    inputs.file(rawReleaseApk)
    inputs.file(d8Jar)
    inputs.file(zipalign)
    inputs.file(apksigner)
    inputs.file(buildTools.map { it.file("lib/apksigner.jar") })
    inputs.property("minSdk", appMinSdk)
    inputs.property("javaRuntime", System.getProperty("java.runtime.version"))
    releaseSigning?.let { signing ->
        signing.storeFile?.let { inputs.file(it) }
        inputs.property("keyAlias", signing.keyAlias.orEmpty())
        inputs.property("storeType", signing.storeType.orEmpty())
    }
    outputs.file(processedReleaseApk)

    doLast {
        val apk = rawReleaseApk.get().asFile
        val processedApk = processedReleaseApk.get().asFile
        processedApk.parentFile.mkdirs()
        val workDir = temporaryDir
        check(workDir.canonicalFile.toPath().startsWith(layout.buildDirectory.get().asFile.canonicalFile.toPath()))
        delete(workDir)
        workDir.mkdirs()

        val unsignedApk = workDir.resolve("unsigned.apk")
        val alignedApk = workDir.resolve("aligned.apk")
        val strippedDexInputDir = workDir.resolve("stripped-dex-input")
        val compactedDexDir = workDir.resolve("compacted-dex")
        strippedDexInputDir.mkdirs()
        compactedDexDir.mkdirs()
        val strippedDexInputs = mutableListOf<File>()
        var dexTimestamp = 0L
        val dexEntryName = Regex("""classes(\d*)?\.dex""")

        ZipFile(apk).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                if (dexEntryName.matches(entry.name)) {
                    if (strippedDexInputs.isEmpty()) dexTimestamp = entry.time
                    zip.getInputStream(entry).use { input ->
                        val strippedDex = strippedDexInputDir.resolve(entry.name)
                        strippedDex.writeBytes(stripDexDebugInfo(input.readBytes()))
                        strippedDexInputs += strippedDex
                    }
                }
            }
        }

        if (strippedDexInputs.isNotEmpty()) {
            providers.exec {
                commandLine(
                    listOf(
                        javaExecutable.absolutePath,
                        "-cp",
                        d8Jar.get().asFile.absolutePath,
                        "com.android.tools.r8.D8",
                        "--release",
                        "--min-api",
                        appMinSdk.toString(),
                        "--output",
                        compactedDexDir.absolutePath,
                    ) + strippedDexInputs.map { it.absolutePath },
                )
            }.result.get().assertNormalExitValue()
        }

        val compactedDex = compactedDexDir.listFiles { file ->
            file.isFile && dexEntryName.matches(file.name)
        }?.sortedBy { dexEntryOrder(it.name) }.orEmpty()

        if (strippedDexInputs.isNotEmpty() && compactedDex.isEmpty()) {
            throw GradleException("D8 did not emit any compacted dex files.")
        }
        compactedDex.forEach { verifyDexDebugInfoRemoved(it.readBytes()) }

        ZipOutputStream(BufferedOutputStream(FileOutputStream(unsignedApk))).use { output ->
            ZipFile(apk).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    if (!shouldDropApkEntry(entry, dexEntryName)) {
                        output.putNextEntry(ZipEntry(entry.name).apply { time = entry.time })
                        if (!entry.isDirectory) {
                            zip.getInputStream(entry).use { input ->
                                input.copyTo(output)
                            }
                        }
                        output.closeEntry()
                    }
                }
            }

            compactedDex.forEach { file ->
                output.putNextEntry(ZipEntry(file.name).apply { time = dexTimestamp })
                file.inputStream().use { input ->
                    input.copyTo(output)
                }
                output.closeEntry()
            }
        }

        providers.exec {
            commandLine(zipalign.get().asFile.absolutePath, "-f", "-p", "4", unsignedApk.absolutePath, alignedApk.absolutePath)
        }.result.get().assertNormalExitValue()

        val signing = releaseSigning
        if (signing == null || signing.storeFile == null) {
            throw GradleException("Release signing config is required after stripping dex debug info.")
        }

        val signCommand = mutableListOf(
            apksigner.get().asFile.absolutePath,
            "sign",
            "--ks",
            signing.storeFile!!.absolutePath,
        )
        signing.storePassword?.let {
            signCommand += listOf("--ks-pass", "pass:$it")
        }
        signing.keyAlias?.let {
            signCommand += listOf("--ks-key-alias", it)
        }
        signing.keyPassword?.let {
            signCommand += listOf("--key-pass", "pass:$it")
        }
        signing.storeType?.let {
            signCommand += listOf("--ks-type", it)
        }
        signCommand += listOf("--out", processedApk.absolutePath, alignedApk.absolutePath)

        providers.exec {
            commandLine(signCommand)
        }.result.get().assertNormalExitValue()
    }
}

val exportReleaseApk = tasks.register("exportReleaseApk") {
    dependsOn(stripReleaseDexDebugInfo)
    doLast {
        val apk = processedReleaseApk.get().asFile
        val versionName = android.defaultConfig.versionName ?: "dev"
        val versionCode = android.defaultConfig.versionCode ?: 0
        val timestamp = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss")
            .format(LocalDateTime.now())
        val namedApk = apk.parentFile.resolve("Xpomni-v$versionName-$versionCode-$timestamp.apk")
        apk.copyTo(namedApk, overwrite = true)
    }
}

afterEvaluate {
    tasks.named("assembleRelease").configure {
        finalizedBy(exportReleaseApk)
    }
}
