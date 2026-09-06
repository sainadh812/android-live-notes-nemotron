#!/usr/bin/env bash
set -euo pipefail
TASK_REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
TASK_BUILD_DIR="$(mktemp -d "${TMPDIR:-/tmp}/speech-lifecycle.XXXXXX")"
trap 'rm -rf -- "$TASK_BUILD_DIR"' EXIT

# Compile the production OS recognizer wrapper against deterministic Android
# stand-ins. No Android device, Gradle project build, or native compiler needed.
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
mkdir -p "$TASK_BUILD_DIR/classes"
java -cp "$TASK_KOTLIN_LIB/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -jvm-target 17 \
    -classpath "${TASK_STDLIB[0]}" -d "$TASK_BUILD_DIR/classes" \
    "$TASK_REPO_ROOT"/tests/host/speech/src/android/{content,os,speech}/*.kt \
    "$TASK_REPO_ROOT/app/src/main/java/com/sainadh/livenotes/stt/TranscriptUpdate.kt" \
    "$TASK_REPO_ROOT/app/src/main/java/com/sainadh/livenotes/stt/SpeechTranscriber.kt" \
    "$TASK_REPO_ROOT/tests/host/speech/src/SpeechLifecycleTest.kt"
timeout 30s java -cp "$TASK_BUILD_DIR/classes:${TASK_STDLIB[0]}" SpeechLifecycleTestKt
