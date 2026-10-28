#!/usr/bin/env bash
# Builds the standalone Avis executable for this machine (Linux or macOS) into dist/.
#
#   ./build.sh           native build -> dist/avis-linux-<arch>  or  dist/Avis.app
#   ./build.sh --trace   run Avis on the JVM under the GraalVM tracing agent and save the FFM
#                        metadata it records into native-image-config/<os>-<arch>/ (commit it)
#   ./build.sh --clean   delete build/ and dist/ first, then build (combines with --trace)
#   ./build.sh --glsl-check
#                        only build the shaders with glslangValidator (check shaders/OpenGL, compile
#                        shaders/Vulkan to SPIR-V), no build
#
# Needs: a JDK 17+ to start Gradle (GraalVM 25 itself is downloaded automatically),
#        Linux: gcc + zlib headers, macOS: Xcode command line tools,
#        and glslangValidator to build the shaders.
set -euo pipefail
cd "$(dirname "$0")"

die() { printf '\nerror: %s\n' "$*" >&2; exit 1; }

java_major() {
    "$1" -version 2>&1 | awk -F'"' '/version/ { split($2, v, "."); print (v[1] == "1" ? v[2] : v[1]); exit }'
}

# Gradle 9 needs Java 17+ to start; the project's own JDK (GraalVM 25) is provisioned by Gradle
find_gradle_java() {
    local candidates=()
    [[ -n "${JAVA_HOME:-}" ]] && candidates+=("$JAVA_HOME/bin/java")
    command -v java >/dev/null 2>&1 && candidates+=("$(command -v java)")
    if [[ -x /usr/libexec/java_home ]]; then
        local mac_home
        mac_home=$(/usr/libexec/java_home -v 17+ 2>/dev/null || true)
        [[ -n "$mac_home" ]] && candidates+=("$mac_home/bin/java")
    fi
    local dir
    for dir in "$HOME"/.jdks/* "$HOME"/.gradle/jdks/* /usr/lib/jvm/* /Library/Java/JavaVirtualMachines/*/Contents/Home; do
        [[ -x "$dir/bin/java" ]] && candidates+=("$dir/bin/java")
        [[ -x "$dir/Contents/Home/bin/java" ]] && candidates+=("$dir/Contents/Home/bin/java")
    done

    local java major
    for java in "${candidates[@]}"; do
        major=$(java_major "$java" 2>/dev/null || true)
        if [[ -n "$major" && "$major" -ge 17 ]]; then
            JAVA_HOME=$(cd "$(dirname "$java")/.." && pwd)
            export JAVA_HOME
            return 0
        fi
    done
    return 1
}

check_native_toolchain() {
    case "$(uname -s)" in
        Linux)
            command -v gcc >/dev/null 2>&1 \
                || die "gcc not found. Install it, e.g. 'sudo apt install build-essential zlib1g-dev' or 'sudo dnf install gcc glibc-devel zlib-devel'."
            echo '#include <zlib.h>' | gcc -E -x c - >/dev/null 2>&1 \
                || die "zlib headers not found. Install them, e.g. 'sudo apt install zlib1g-dev' or 'sudo dnf install zlib-devel'."
            ;;
        Darwin)
            xcode-select -p >/dev/null 2>&1 \
                || die "Xcode command line tools not found. Install them with 'xcode-select --install'."
            ;;
        *)
            die "unsupported OS '$(uname -s)'. On Windows run build.bat instead."
            ;;
    esac
}

check_shader_validator() {
    command -v glslangValidator >/dev/null 2>&1 && return 0
    [[ -n "${VULKAN_SDK:-}" && -x "$VULKAN_SDK/bin/glslangValidator" ]] && return 0
    for arg in ${gradle_args[@]+"${gradle_args[@]}"}; do
        [[ "$arg" == -PglslangValidator=* ]] && return 0
    done
    if [[ "$(uname -s)" == Darwin ]]; then
        die "glslangValidator not found (it builds the shaders). Install it with 'brew install glslang'."
    fi
    die "glslangValidator not found (it builds the shaders). Install it, e.g. 'sudo apt install glslang-tools' or 'sudo dnf install glslang'."
}

trace=false
glsl_check=false
clean_task=""
gradle_args=()
for arg in "$@"; do
    case "$arg" in
        --trace) trace=true ;;
        --glsl-check) glsl_check=true ;;
        --clean) clean_task="clean" ;;
        -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
        *) gradle_args+=("$arg") ;;   # anything else goes straight to Gradle
    esac
done

$trace && $glsl_check && die "--trace and --glsl-check can't be used together."

find_gradle_java || die "no JDK 17+ found to run Gradle. Install one (any vendor) or point JAVA_HOME at it."
echo "Using $JAVA_HOME to run Gradle"

if $glsl_check; then
    check_shader_validator
    # --rerun-tasks so the shaders are always rebuilt, even when Gradle thinks the task is up to date;
    # --continue so one backend's errors don't hide the other's
    ./gradlew $clean_task compileShaders --rerun-tasks --continue ${gradle_args[@]+"${gradle_args[@]}"}
    echo
    echo "Shaders:"
    cat build/reports/shaders/OpenGL.txt build/reports/shaders/Vulkan.txt | sed 's/^/  /'
    exit 0
fi

if $trace; then
    # the agent run uses GRAALVM_HOME, so point it at the GraalVM 25 toolchain
    GRAALVM_HOME=$(./gradlew -q graalHome | tail -n 1)
    export GRAALVM_HOME
    ./gradlew $clean_task -Pagent run ${gradle_args[@]+"${gradle_args[@]}"}
    ./gradlew metadataCopy --task run ${gradle_args[@]+"${gradle_args[@]}"}
    echo
    echo "FFM metadata saved under native-image-config/. Commit it, then run ./build.sh"
    exit 0
fi

check_native_toolchain
check_shader_validator
./gradlew $clean_task dist ${gradle_args[@]+"${gradle_args[@]}"}

echo
echo "Built:"
if [[ -d dist/Avis.app ]]; then
    du -sh dist/Avis.app
else
    ls -lh dist/avis-* | awk '{ print "  " $NF "  (" $5 ")" }'
fi
