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

// platform naming, used for the binary name, the per-platform FFM metadata dir and the LWJGL natives
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
val lwjglNatives = "natives-$osName" + if (archName == "arm64") "-arm64" else ""
// oldest CPU the binary runs on. x64: AVX2 (Intel Haswell 2013+, AMD Excavator 2015+). Every Apple Silicon Mac has ARMv8.1+
val cpuBaseline = when {
    archName == "x64" -> "x86-64-v3"
    archName == "arm64" && osName == "macos" -> "armv8.1-a"
    else -> "compatibility"
}

dependencies {
    implementation("com.formdev:flatlaf:3.7.2")

    implementation(platform("org.lwjgl:lwjgl-bom:3.4.3"))
    implementation("org.lwjgl:lwjgl")
    implementation("org.lwjgl:lwjgl-vulkan")
    implementation("org.lwjgl:lwjgl-opengl")
    runtimeOnly("org.lwjgl:lwjgl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-opengl::$lwjglNatives")
    if (osName == "macos") runtimeOnly("org.lwjgl:lwjgl-vulkan::$lwjglNatives") // MoltenVK; elsewhere the system loader is used

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Oracle GraalVM is both the compiler JDK and the native-image builder; build-logic's resolver downloads it if it's missing
val graalToolchain: JavaToolchainSpec.() -> Unit = {
    languageVersion = JavaLanguageVersion.of(25)
    vendor = JvmVendorSpec.ORACLE // with nativeImageCapable this only matches Oracle GraalVM, not GraalVM Community
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

// Each graphics backend has its own shader folder, built its own way, and keeps that folder on the classpath:
//   shaders/OpenGL/Pass1/QFT.comp -> /shaders/OpenGL/Pass1/QFT.comp      GLSL, checked here, compiled by the GL driver at runtime
//   shaders/Vulkan/Pass1/QFT.comp -> /shaders/Vulkan/Pass1/QFT.comp.spv  compiled to SPIR-V here; the GLSL doesn't ship
abstract class BuildShaders : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty
    @get:Input
    abstract val compiler: Property<String>
    // set: compile to SPIR-V for this Vulkan version. unset: only check the GLSL (OpenGL rules) and ship it as is
    @get:Input @get:Optional
    abstract val vulkanTarget: Property<String>
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty
    @get:OutputFile
    abstract val report: RegularFileProperty
    @get:Inject
    abstract val exec: ExecOperations
    @get:Inject
    abstract val fs: FileSystemOperations
    @get:Inject
    abstract val objects: ObjectFactory

    @TaskAction
    fun build() {
        fs.delete { delete(outputDir) } // so a deleted or renamed shader doesn't leave its old output behind
        val shaders = mutableListOf<Pair<String, File>>()
        objects.fileTree().from(sourceDir)
            .matching { include("**/*.comp", "**/*.vert", "**/*.frag", "**/*.geom", "**/*.tesc", "**/*.tese") }
            .visit { if (!isDirectory) shaders += path to file }
        shaders.sortBy { it.first }
        val spirv = vulkanTarget.isPresent

        val failures = shaders.mapNotNull { (path, shader) ->
            val out = outputDir.file(if (spirv) "$path.spv" else path).get().asFile
            out.parentFile.mkdirs()
            val output = ByteArrayOutputStream()
            val result = try {
                exec.exec {
                    if (spirv) commandLine(compiler.get(), "-V", "--target-env", vulkanTarget.get(), "-o", out.path, shader.path)
                    else commandLine(compiler.get(), shader.path)
                    standardOutput = output
                    errorOutput = output
                    isIgnoreExitValue = true
                }
            } catch (e: Exception) {
                throw GradleException(
                    "Could not run '${compiler.get()}'. Install glslang: 'sudo apt install glslang-tools' (Linux), " +
                    "'brew install glslang' (macOS), or the Vulkan SDK (Windows). " +
                    "Or point -PglslangValidator=<path> at the executable.", e)
            }
            if (result.exitValue != 0) return@mapNotNull output.toString().trim()
            if (!spirv) shader.copyTo(out, overwrite = true)
            null
        }
        val backend = sourceDir.get().asFile.name
        if (failures.isNotEmpty()) {
            throw GradleException("$backend shaders failed ${if (spirv) "SPIR-V compilation" else "GLSL validation"}:\n\n" +
                failures.joinToString("\n\n"))
        }
        report.get().asFile.writeText(shaders.joinToString("") { (path, _) ->
            if (spirv) "$backend/$path -> $backend/$path.spv\n" else "$backend/$path: OK\n"
        })
    }
}

// -PglslangValidator=<path> wins, then the Vulkan SDK's copy, then PATH
val glslangValidator = providers.gradleProperty("glslangValidator")
    .orElse(providers.environmentVariable("VULKAN_SDK").map { "$it/bin/glslangValidator" })
    .orElse("glslangValidator")

val openGLShaders = tasks.register<BuildShaders>("openGLShaders") {
    description = "Checks the GLSL in /shaders/OpenGL with glslangValidator."
    sourceDir = layout.projectDirectory.dir("shaders/OpenGL")
    compiler = glslangValidator
    outputDir = layout.buildDirectory.dir("generated/shaders/OpenGL")
    report = layout.buildDirectory.file("reports/shaders/OpenGL.txt")
}

val vulkanShaders = tasks.register<BuildShaders>("vulkanShaders") {
    description = "Compiles the GLSL in /shaders/Vulkan to SPIR-V with glslangValidator."
    sourceDir = layout.projectDirectory.dir("shaders/Vulkan")
    compiler = glslangValidator
    vulkanTarget = "vulkan1.2" // SPIR-V 1.5; RADV, NVIDIA and MoltenVK all support it
    outputDir = layout.buildDirectory.dir("generated/shaders/Vulkan")
    report = layout.buildDirectory.file("reports/shaders/Vulkan.txt")
}

tasks.register("compileShaders") {
    group = "build"
    description = "Builds every backend's shaders (OpenGL checked, Vulkan compiled to SPIR-V)."
    dependsOn(openGLShaders, vulkanShaders)
}

tasks.processResources {
    from(openGLShaders.flatMap { it.outputDir }) { into("shaders/OpenGL") }
    from(vulkanShaders.flatMap { it.outputDir }) { into("shaders/Vulkan") }
}

val platformConfigDir = layout.projectDirectory.dir("native-image-config/$platform")
val pgoProfileDir = layout.projectDirectory.dir("pgo-profiles/$platform")
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
            "-march=$cpuBaseline",
        )
        val pgoInstrumenting = providers.gradleProperty("pgoInstrument").isPresent
        if (!pgoInstrumenting) buildArgs.add("-O3") // best peak performance (bigger binary than -Os); instrumented builds pick their own
        if (osName == "linux") buildArgs.add("--gc=G1") // shorter pauses than the Serial GC; native images only have G1 on Linux
        // every .iprof in here feeds profile-guided optimisation; -PpgoInstrument builds the profiling binary instead
        pgoProfilesDirectory = providers.provider { pgoProfileDir.takeIf { it.asFile.isDirectory } } // none until the first --pgo run
        pgoInstrument = pgoInstrumenting
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

tasks.named<BuildNativeImageTask>("nativeCompile") { dependsOn(repairGraalVM) }

// PGO: ./gradlew -PpgoInstrument nativeRun, use Avis and quit it normally, then ./gradlew pgoCopy (build.sh --pgo does both)
val pgoProfile = layout.buildDirectory.file("native/nativeCompile/default.iprof") // where the instrumented binary writes it on exit
tasks.named("nativeRun") {
    doFirst { pgoProfile.get().asFile.delete() } // so pgoCopy can't pick up an older run's profile
}
tasks.register("pgoCopy") {
    description = "Saves the profile from the last instrumented run into pgo-profiles/<os>-<arch>/."
    val target = pgoProfileDir.file("default.iprof").asFile
    doLast {
        val profile = pgoProfile.get().asFile
        if (!profile.isFile) {
            throw GradleException("No profile at $profile. Run ./gradlew -PpgoInstrument nativeRun and quit Avis normally first.")
        }
        profile.copyTo(target, overwrite = true)
        logger.lifecycle("Saved PGO profile to $target")
    }
}

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
