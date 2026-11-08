#!/usr/bin/env bash
# Builds llama-server for Android (arm64) and places it where Gradle packages it:
#   app/src/main/jniLibs/arm64-v8a/libllama_server.so
# The app starts it from its native-library directory ("lib...so" is the only place Android lets an app run a
# bundled program from). Used by GitHub Actions before the Gradle build; needs the Android NDK (preinstalled on
# GitHub's Ubuntu runners). If this fails, the APK still builds and the app falls back to Termux or cloud models.
#
# Environment (all optional):
#   LLAMA_CPP_REF   llama.cpp release tag to build            (default: b12099)
#   ANDROID_NDK_HOME / ANDROID_NDK_LATEST_HOME / ANDROID_NDK_ROOT   NDK location
#   LLAMA_ARM_ARCH  CPU features to compile for               (default: armv8.2-a+dotprod+fp16)
#   LLAMA_API       Android API level of the binary           (default: 28)
set -euo pipefail

REF="${LLAMA_CPP_REF:-b12099}"
ARCH="${LLAMA_ARM_ARCH:-armv8.2-a+dotprod+fp16}"
API="${LLAMA_API:-28}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="$ROOT/app/src/main/jniLibs/arm64-v8a"
OUT="$OUT_DIR/libllama_server.so"
WORK="${LLAMA_WORK_DIR:-$ROOT/build/llama-android}"

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_ROOT:-}}}"
if [ -z "$NDK" ] || [ ! -f "$NDK/build/cmake/android.toolchain.cmake" ]; then
  echo "Android NDK not found (set ANDROID_NDK_HOME). Skipping the on-device runtime build." >&2
  exit 2
fi
command -v cmake >/dev/null || { echo "cmake not found" >&2; exit 2; }

if [ -f "$OUT" ] && [ "${FORCE:-0}" != "1" ]; then
  echo "Already built: $OUT"
  exit 0
fi

mkdir -p "$WORK" "$OUT_DIR"
if [ ! -d "$WORK/llama.cpp/.git" ]; then
  git clone --depth 1 --branch "$REF" https://github.com/ggml-org/llama.cpp.git "$WORK/llama.cpp"
fi

cmake -S "$WORK/llama.cpp" -B "$WORK/build" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM="android-$API" \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=OFF \
  -DGGML_NATIVE=OFF -DGGML_CPU_ARM_ARCH="$ARCH" \
  -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF \
  -DLLAMA_OPENSSL=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF \
  -DLLAMA_BUILD_UI=OFF -DLLAMA_USE_PREBUILT_UI=OFF -DLLAMA_BUILD_APP=OFF

cmake --build "$WORK/build" --target llama-server --config Release -j "$(nproc)"

BIN="$(find "$WORK/build" -type f -name llama-server -perm -u+x | head -1)"
[ -n "$BIN" ] || { echo "llama-server was not produced" >&2; exit 1; }
cp "$BIN" "$OUT"
chmod 755 "$OUT"
echo "Built $OUT ($(du -h "$OUT" | cut -f1)) from llama.cpp $REF for $ARCH"
command -v file >/dev/null && file "$OUT" || true
