import java.util.Locale
import java.util.Properties

val aerionLocalProperties = Properties()
val aerionLocalPropertiesFile = rootProject.file("local.properties")
if (aerionLocalPropertiesFile.isFile) {
    aerionLocalPropertiesFile.inputStream().use { aerionLocalProperties.load(it) }
}

fun aerionSdkDirectory(): File {
    aerionLocalProperties.getProperty("sdk.dir")?.let { return file(it) }
    System.getenv("ANDROID_SDK_ROOT")?.let { return file(it) }
    System.getenv("ANDROID_HOME")?.let { return file(it) }
    val localAppData = System.getenv("LOCALAPPDATA")
    if (localAppData != null) {
        return file("$localAppData/Android/Sdk")
    }
    error("Android SDK directory is not configured. Set sdk.dir in local.properties.")
}

val aerionMinAndroidApi = extensions.extraProperties["aerionMinAndroidApi"] as Int
val aerionAndroidNdkVersion = extensions.extraProperties["aerionAndroidNdkVersion"] as String
val aerionCrateDir = rootProject.file("rust/aerion-core")
val socksCompatDir = rootProject.file("rust/third_party/socks5-impl")
val aerionLibraryName = "aerion_core"
val aerionAndroidSdkDir = aerionSdkDirectory()
val aerionAndroidNdkDir = aerionAndroidSdkDir.resolve("ndk/$aerionAndroidNdkVersion")
val aerionHostOs = System.getProperty("os.name").lowercase(Locale.ROOT)
val aerionIsWindows = aerionHostOs.contains("windows")
val aerionNdkHostTag = when {
    aerionIsWindows -> "windows-x86_64"
    aerionHostOs.contains("mac") -> "darwin-x86_64"
    else -> "linux-x86_64"
}
val aerionExecutableSuffix = if (aerionIsWindows) ".cmd" else ""
val aerionToolSuffix = if (aerionIsWindows) ".exe" else ""
val aerionNdkBinDir = aerionAndroidNdkDir.resolve("toolchains/llvm/prebuilt/$aerionNdkHostTag/bin")
val aerionRustFlags = "-C link-arg=-Wl,-z,max-page-size=16384"
val aerionTargets = listOf(
    Triple("arm64-v8a", "aarch64-linux-android", "aarch64-linux-android${aerionMinAndroidApi}-clang$aerionExecutableSuffix"),
    Triple("x86_64", "x86_64-linux-android", "x86_64-linux-android${aerionMinAndroidApi}-clang$aerionExecutableSuffix"),
)

fun aerionRustEnvName(target: String): String =
    "CARGO_TARGET_${target.uppercase(Locale.ROOT).replace('-', '_')}_LINKER"

fun aerionCcEnvName(prefix: String, target: String): String =
    "${prefix}_${target.replace('-', '_')}"

aerionTargets.forEach { (abi, target, clang) ->
    val taskSuffix = abi.replace("-", "_")
    val cargoTask = tasks.register<Exec>("cargoBuildAerion$taskSuffix") {
        workingDir = aerionCrateDir
        inputs.files(
            fileTree(aerionCrateDir.resolve("src")),
            aerionCrateDir.resolve("Cargo.toml"),
            aerionCrateDir.resolve("Cargo.lock"),
            fileTree(socksCompatDir.resolve("src")),
            socksCompatDir.resolve("Cargo.toml"),
        )
        inputs.property("androidNdkVersion", aerionAndroidNdkVersion)
        inputs.property("minAndroidApi", aerionMinAndroidApi)
        inputs.property("rustTarget", target)
        inputs.property("androidRustFlags", aerionRustFlags)
        outputs.file(aerionCrateDir.resolve("target/$target/release/lib$aerionLibraryName.so"))
        val cc = aerionNdkBinDir.resolve(clang).absolutePath
        val cxx = cc.removeSuffix("clang$aerionExecutableSuffix") + "clang++$aerionExecutableSuffix"
        environment(aerionRustEnvName(target), cc)
        environment(aerionCcEnvName("CC", target), cc)
        environment(aerionCcEnvName("CXX", target), cxx)
        environment(aerionCcEnvName("AR", target), aerionNdkBinDir.resolve("llvm-ar$aerionToolSuffix").absolutePath)
        environment("ANDROID_NDK_HOME", aerionAndroidNdkDir.absolutePath)
        environment("RUSTFLAGS", aerionRustFlags)
        commandLine("cargo", "build", "--release", "--target", target)
    }

    tasks.register<Copy>("copyAerion$taskSuffix") {
        dependsOn(cargoTask)
        from(aerionCrateDir.resolve("target/$target/release/lib$aerionLibraryName.so"))
        into(layout.buildDirectory.dir("generated/aerionJniLibs/$abi"))
    }
}

val copyAerionJniLibs = tasks.register("copyAerionJniLibs") {
    dependsOn(aerionTargets.map { (abi, _, _) -> "copyAerion${abi.replace("-", "_")}" })
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(copyAerionJniLibs)
}
