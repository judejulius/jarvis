#!/usr/bin/env bash
# Build a standalone llama-server executable for each APK. Android extracts and allows execution
# from the native-library directory when the executable is packaged with a lib*.so name.
# Pass ABI names to build a subset. LLAMA_CPP_REF, LLAMA_WORK_DIR, LLAMA_API, LLAMA_ARM_ARCH
# and CMAKE_BUILD_PARALLEL_LEVEL can override the defaults.
set -euo pipefail

REF="${LLAMA_CPP_REF:-b12099}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${LLAMA_WORK_DIR:-$ROOT/build/llama-android}"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_ROOT:-}}}"
if [[ ! -f "$NDK/build/cmake/android.toolchain.cmake" ]]; then
  echo "Set ANDROID_NDK_HOME to an installed Android NDK." >&2
  exit 2
fi
if [[ $# == 0 ]]; then set -- arm64-v8a armeabi-v7a x86_64; fi

mkdir -p "$WORK"
if [[ ! -d "$WORK/llama.cpp/.git" ]]; then
  git clone --depth 1 --branch "$REF" https://github.com/ggml-org/llama.cpp.git "$WORK/llama.cpp"
fi

build_abi() (
  set -e
  local abi="$1" build="$WORK/build-$1"
  local out="$ROOT/app/src/main/jniLibs/$abi/libllama_server.so"
  local flags=()
  case "$abi" in
    arm64-v8a) flags+=("-DGGML_CPU_ARM_ARCH=${LLAMA_ARM_ARCH:-armv8.2-a+dotprod+fp16}") ;;
    armeabi-v7a) ;;
    x86_64) flags+=(-DGGML_AVX=OFF -DGGML_AVX2=OFF -DGGML_BMI2=OFF -DGGML_FMA=OFF -DGGML_F16C=OFF) ;;
    *) echo "Unknown ABI $abi" >&2; exit 2 ;;
  esac
  cmake -S "$WORK/llama.cpp" -B "$build" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" -DANDROID_PLATFORM="android-${LLAMA_API:-26}" \
    -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release -DCMAKE_EXE_LINKER_FLAGS_RELEASE=-s -DBUILD_SHARED_LIBS=OFF \
    -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF "${flags[@]}" \
    -DLLAMA_OPENSSL=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF \
    -DLLAMA_BUILD_UI=OFF -DLLAMA_USE_PREBUILT_UI=OFF -DLLAMA_BUILD_APP=OFF
  cmake --build "$build" --target llama-server --parallel "${CMAKE_BUILD_PARALLEL_LEVEL:-$(nproc)}"
  install -Dm755 "$build/bin/llama-server" "$out"
)

# Try every ABI so the optional runtime can still ship wherever it built successfully.
failed=0
for abi in "$@"; do
  set +e
  build_abi "$abi"
  status=$?
  set -e
  if [[ "$status" != 0 ]]; then failed=1; fi
done
exit "$failed"
