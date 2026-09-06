#!/usr/bin/env bash
set -euo pipefail
TASK_REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
TASK_BUILD_DIR="$(mktemp -d "${TMPDIR:-/tmp}/nemotron-lifecycle.XXXXXX")"
trap 'rm -rf -- "$TASK_BUILD_DIR"' EXIT

# Gradle ships the compiler used for this isolated Linux/JVM test. Run
# ./gradlew --version first, or set KOTLIN_COMPILER_LIB to its lib directory.
if [[ -n "${KOTLIN_COMPILER_LIB:-}" ]]; then
    TASK_KOTLIN_LIB="$KOTLIN_COMPILER_LIB"
else
    shopt -s nullglob
    TASK_COMPILERS=("${GRADLE_USER_HOME:-$HOME/.gradle}"/wrapper/dists/gradle-*/*/gradle-*/lib/kotlin-compiler-embeddable-*.jar)
    if (( ${#TASK_COMPILERS[@]} == 0 )); then
        echo 'No bundled Kotlin compiler found. Run ./gradlew --version first.' >&2
        exit 1
    fi
    TASK_KOTLIN_LIB="$(dirname -- "${TASK_COMPILERS[0]}")"
fi
TASK_JDK="${JAVA_HOME:-$(dirname -- "$(dirname -- "$(readlink -f -- "$(command -v javac)")")")}"
TASK_STDLIB=("$TASK_KOTLIN_LIB"/kotlin-stdlib-[0-9]*.jar)
mkdir -p "$TASK_BUILD_DIR/classes" "$TASK_BUILD_DIR/lib"
javac -d "$TASK_BUILD_DIR/classes"     "$TASK_REPO_ROOT"/tests/host/src/android/{content,media,os}/*.java     "$TASK_REPO_ROOT"/tests/host/src/check/*.java
gcc -shared -fPIC -I"$TASK_JDK/include" -I"$TASK_JDK/include/linux"     "$TASK_REPO_ROOT/tests/host/native_stubs.c" -o "$TASK_BUILD_DIR/lib/libnemotron_jni.so"
for name in ggml-base ggml ggml-cpu transcribe; do
    ln -s libnemotron_jni.so "$TASK_BUILD_DIR/lib/lib$name.so"
done
java -cp "$TASK_KOTLIN_LIB/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler     -no-stdlib -no-reflect -jvm-target 17     -classpath "$TASK_BUILD_DIR/classes:${TASK_STDLIB[0]}" -d "$TASK_BUILD_DIR/classes"     "$TASK_REPO_ROOT/app/src/main/java/com/sainadh/livenotes/stt/NemotronTranscriber.kt"     "$TASK_REPO_ROOT/app/src/main/java/com/sainadh/livenotes/stt/NativeTranscriptSegments.kt"     "$TASK_REPO_ROOT/app/src/main/java/com/sainadh/livenotes/stt/TranscriptUpdate.kt"     "$TASK_REPO_ROOT/tests/host/src/Listener.kt" "$TASK_REPO_ROOT/tests/host/src/LifecycleTest.kt"
for scenario in stop-init destroy-init stop-feed destroy-feed restart restart-failure     init-failure feed-failure finalize-failure audio-init audio-start audio-read idle-stop slow-feed-queue queued-tail overflow destroy-queue read-error-tail feed-error-partial tail-limit segments; do
    timeout 15s java -Djava.library.path="$TASK_BUILD_DIR/lib"         -cp "$TASK_BUILD_DIR/classes:${TASK_STDLIB[0]}" LifecycleTestKt "$scenario"
done
