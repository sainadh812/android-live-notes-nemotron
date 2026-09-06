#!/usr/bin/env bash
set -euo pipefail
TASK_REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
TASK_BUILD_DIR="$(mktemp -d "${TMPDIR:-/tmp}/listening-service.XXXXXX")"
trap 'rm -rf -- "$TASK_BUILD_DIR"' EXIT

# Compile the real service and OS wrapper against Android stand-ins and the real
# coroutines runtime. A test Main dispatcher lets tests drain lifecycle callbacks.
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
TASK_STDLIB=("$TASK_KOTLIN_LIB"/kotlin-stdlib-[0-9]*.jar)
TASK_COROUTINES=("$TASK_KOTLIN_LIB"/kotlinx-coroutines-core-jvm-*.jar)
TASK_CLASSPATH="${TASK_STDLIB[0]}:${TASK_COROUTINES[0]}"
mkdir -p "$TASK_BUILD_DIR/classes/META-INF/services"
printf '%s\n' check.TestMainDispatcherFactory > "$TASK_BUILD_DIR/classes/META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory"
mapfile -t TASK_STUBS < <(find "$TASK_REPO_ROOT/tests/host/service/src" -name '*.kt' -print)
java -cp "$TASK_KOTLIN_LIB/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -jvm-target 17 \
    -classpath "$TASK_CLASSPATH" -d "$TASK_BUILD_DIR/classes" \
    "${TASK_STUBS[@]}" \
    "$TASK_REPO_ROOT/tests/host/speech/src/android/os/Handler.kt" \
    "$TASK_REPO_ROOT/tests/host/speech/src/android/speech/Speech.kt" \
    "$TASK_REPO_ROOT/app/src/main/java/com/sainadh/livenotes/stt/SpeechTranscriber.kt" \
    "$TASK_REPO_ROOT/app/src/main/java/com/sainadh/livenotes/stt/TranscriptUpdate.kt" \
    "$TASK_REPO_ROOT/app/src/main/java/com/sainadh/livenotes/service/ForegroundListeningService.kt"
timeout 30s java -Dkotlinx.coroutines.fast.service.loader=false \
    -cp "$TASK_BUILD_DIR/classes:$TASK_CLASSPATH" ServiceLifecycleTestKt
