import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings
import org.gradle.jvm.toolchain.JavaToolchainDownload
import org.gradle.jvm.toolchain.JavaToolchainRequest
import org.gradle.jvm.toolchain.JavaToolchainResolver
import org.gradle.jvm.toolchain.JavaToolchainResolverRegistry
import org.gradle.jvm.toolchain.JvmToolchainManagement
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.gradle.platform.Architecture
import org.gradle.platform.OperatingSystem
import java.net.URI
import java.util.Optional
import javax.inject.Inject

// Lets Gradle download Oracle GraalVM for toolchains that ask for vendor ORACLE + nativeImageCapable.
// foojay only hands out GraalVM Community (gradle/gradle#25521), which has no PGO, G1 or full -O3.
abstract class OracleGraalVMPlugin @Inject constructor(
    private val registry: JavaToolchainResolverRegistry,
) : Plugin<Settings> {
    override fun apply(settings: Settings) {
        settings.plugins.apply("jvm-toolchain-management")
        registry.register(OracleGraalVMResolver::class.java)
        settings.toolchainManagement.extensions.configure(JvmToolchainManagement::class.java) {
            javaRepositories.repository("oracleGraalVM") { resolverClass.set(OracleGraalVMResolver::class.java) }
        }
    }
}

// Oracle's "latest" links always point at the newest update of a feature release (GraalVM Free Terms and Conditions)
abstract class OracleGraalVMResolver : JavaToolchainResolver {
    override fun resolve(request: JavaToolchainRequest): Optional<JavaToolchainDownload> {
        val spec = request.javaToolchainSpec
        if (spec.vendor.get() != JvmVendorSpec.ORACLE || !spec.nativeImageCapable.getOrElse(false)) return Optional.empty()
        val version = spec.languageVersion.get().asInt()
        val os = when (request.buildPlatform.operatingSystem) {
            OperatingSystem.LINUX -> "linux"
            OperatingSystem.MAC_OS -> "macos"
            OperatingSystem.WINDOWS -> "windows"
            else -> return Optional.empty()
        }
        val arch = when (request.buildPlatform.architecture) {
            Architecture.X86_64 -> "x64"
            Architecture.AARCH64 -> "aarch64"
            else -> return Optional.empty()
        }
        val archive = if (os == "windows") "zip" else "tar.gz"
        return Optional.of(JavaToolchainDownload.fromUri(
            URI("https://download.oracle.com/graalvm/$version/latest/graalvm-jdk-${version}_${os}-${arch}_bin.$archive")))
    }
}
