import org.graalvm.buildtools.gradle.tasks.BuildNativeImageTask
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

plugins {
    id("java")
    id("application")
    id("org.graalvm.buildtools.native") version "1.1.14"
}

group = "com.EonCuber"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// GraalVM is both the compiler JDK and the native-image builder; foojay downloads it if it's missing
val graalToolchain: JavaToolchainSpec.() -> Unit = {
    languageVersion = JavaLanguageVersion.of(25)
    vendor = JvmVendorSpec.GRAAL_VM
    nativeImageCapable = true
}

java {
    toolchain { graalToolchain() }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass = "com.EonCuber.Main"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// shaders stay in /shaders and ship on the classpath as /shaders/*.comp
tasks.processResources {
    from("shaders") { into("shaders") }
}

// Shaders are still compiled by the GPU driver at runtime; this only catches GLSL errors at build time.
abstract class ValidateShaders : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val shaders: ConfigurableFileCollection
    @get:Input
    abstract val validator: Property<String>
    @get:OutputFile
    abstract val report: RegularFileProperty
    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun validate() {
        val failures = shaders.files.sortedBy { it.name }.mapNotNull { shader ->
            val output = ByteArrayOutputStream()
            val result = try {
                exec.exec {
                    commandLine(validator.get(), shader.path)
                    standardOutput = output
                    errorOutput = output
                    isIgnoreExitValue = true
                }
            } catch (e: Exception) {
                throw GradleException(
                    "Could not run '${validator.get()}'. Install glslang: 'sudo apt install glslang-tools' (Linux), " +
                    "'brew install glslang' (macOS), or the Vulkan SDK (Windows). " +
                    "Or point -PglslangValidator=<path> at the executable.", e)
            }
            if (result.exitValue == 0) null else output.toString().trim()
        }
        if (failures.isNotEmpty()) throw GradleException("GLSL validation failed:\n\n" + failures.joinToString("\n\n"))
        report.get().asFile.writeText(shaders.files.sortedBy { it.name }.joinToString("\n") { "${it.name}: OK" } + "\n")
    }
}

val validateShaders = tasks.register<ValidateShaders>("validateShaders") {
    group = "verification"
    description = "Checks every shader in /shaders with glslangValidator."
    shaders.from(fileTree("shaders") { include("**/*.comp", "**/*.vert", "**/*.frag", "**/*.geom", "**/*.tesc", "**/*.tese") })
    // -PglslangValidator=<path> wins, then the Vulkan SDK's copy, then PATH
    validator = providers.gradleProperty("glslangValidator")
        .orElse(providers.environmentVariable("VULKAN_SDK").map { "$it/bin/glslangValidator" })
        .orElse("glslangValidator")
    report = layout.buildDirectory.file("reports/shaders/validation.txt")
}

tasks.named("check") { dependsOn(validateShaders) }

// platform naming, used for the binary name and the per-platform FFM metadata dir
val osName = System.getProperty("os.name").lowercase().let {
    when {
        it.contains("win") -> "windows"
        it.contains("mac") -> "macos"
        else -> "linux"
    }
}
val archName = when (System.getProperty("os.arch").lowercase()) {
    "amd64", "x86_64" -> "x64"
    "aarch64", "arm64" -> "arm64"
    else -> System.getProperty("os.arch").lowercase()
}
val platform = "$osName-$archName"
val platformConfigDir = layout.projectDirectory.dir("native-image-config/$platform")
val distDir = layout.projectDirectory.dir("dist")

tasks.named<Delete>("clean") { delete(distDir) }

graalvmNative {
    toolchainDetection = true // build with the GraalVM toolchain, not whatever JDK is in JAVA_HOME
    binaries.named("main") {
        imageName = if (osName == "macos") "avis" else "avis-$platform"
        mainClass = application.mainClass
        javaLauncher = javaToolchains.launcherFor { graalToolchain() }
        buildArgs.addAll(
            "--enable-native-access=ALL-UNNAMED",
            "-Os",                  // optimise for binary size
            "-march=compatibility", // run on any CPU of this arch, not just the build machine's generation
        )
        if (osName == "windows" && !providers.gradleProperty("console").isPresent) {
            // GUI app: no console window (pass -Pconsole to keep one for debugging)
            buildArgs.addAll("-H:NativeLinkerOption=/SUBSYSTEM:WINDOWS", "-H:NativeLinkerOption=/ENTRY:mainCRTStartup")
        }
        configurationFileDirectories.from(
            layout.projectDirectory.dir("native-image-config/common"),
            platformConfigDir,
        )
    }
    agent {
        // ./gradlew -Pagent run, then ./gradlew metadataCopy (build.sh --trace does both)
        metadataCopy {
            inputTaskNames.add("run")
            outputDirectories.add(platformConfigDir.asFile.path)
            mergeWithExisting = true
        }
    }
}

// Gradle's toolchain download unpacks GraalVM's symlinks as empty files, leaving bin/native-image a 0-byte stub.
// Put the links back (macOS/Linux only; the Windows zip has no symlinks).
val graalSymlinks = mapOf(
    "bin/native-image" to "../lib/svm/bin/native-image",
    "bin/native-image-configure" to "../lib/svm/bin/native-image-configure",
    "bin/native-image-utils" to "../lib/svm/bin/native-image-utils",
    "lib/svm/bin/native-image-configure" to "native-image-utils",
    "LICENSE_NATIVEIMAGE.txt" to "lib/svm/LICENSE_NATIVEIMAGE.txt",
)
val repairGraalVM = tasks.register("repairGraalVM") {
    description = "Restores the symlinks Gradle's JDK provisioning drops from GraalVM."
    val graalHome = javaToolchains.launcherFor { graalToolchain() }.map { it.metadata.installationPath.asFile.toPath() }
    onlyIf { osName != "windows" }
    doLast {
        val home = graalHome.get()
        graalSymlinks.forEach { (link, target) ->
            val path = home.resolve(link)
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) == 0L) {
                Files.delete(path)
                Files.createSymbolicLink(path, Path.of(target))
                logger.lifecycle("Restored GraalVM symlink $link -> $target")
            }
        }
    }
}

tasks.named<BuildNativeImageTask>("nativeCompile") { dependsOn(repairGraalVM, validateShaders) }

// the agent run (-Pagent) only looks at GRAALVM_HOME/JAVA_HOME, so the build scripts ask for this path first
tasks.register("graalHome") {
    description = "Prints the GraalVM toolchain's home directory."
    dependsOn(repairGraalVM)
    val graalHome = javaToolchains.launcherFor { graalToolchain() }.map { it.metadata.installationPath.asFile.path }
    doLast { println(graalHome.get()) }
}

val nativeBinary = tasks.named<BuildNativeImageTask>("nativeCompile").flatMap { it.outputFile }

val macAppBundle = tasks.register<Sync>("macAppBundle") {
    description = "Assembles dist/Avis.app around the native binary."
    into(distDir.dir("Avis.app/Contents"))
    from(nativeBinary) { into("MacOS") }
    from("packaging/macos/Info.plist") {
        filter { it.replace("@VERSION@", project.version.toString().removeSuffix("-SNAPSHOT")) }
    }
}

val macApp = tasks.register<Exec>("macApp") {
    description = "Ad-hoc signs dist/Avis.app so macOS keeps its audio/screen capture permissions."
    dependsOn(macAppBundle)
    commandLine("codesign", "--force", "--sign", "-", distDir.dir("Avis.app").asFile.path)
}

val copyNativeBinary = tasks.register<Copy>("copyNativeBinary") {
    from(nativeBinary)
    into(distDir)
}

tasks.register("dist") {
    group = "distribution"
    description = "Builds the standalone native executable into dist/."
    dependsOn(if (osName == "macos") macApp else copyNativeBinary)
}
