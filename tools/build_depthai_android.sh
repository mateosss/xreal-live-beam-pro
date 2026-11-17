#!/bin/bash
# Builds depthai-core (Luxonis OAK cameras) for Android arm64-v8a and vendors it into the
# app: libraries into app/src/main/jniLibs/arm64-v8a, headers into
# third_party/depthai-android/include. The app's JNI glue (app/src/main/cpp) links them.
#
#   tools/build_depthai_android.sh [WORK_DIR]
#
# Needs git, cmake ≥ 3.20, ninja and the Android NDK (ANDROID_NDK or the SDK's ndk/27.2.12479018).
# Hunter downloads depthai-core's dependencies and the device firmware (~10 min the first time).
set -euo pipefail
VERSION=v2.34.0
REPO=$(cd "$(dirname "$0")/.." && pwd)
WORK=${1:-$REPO/build/depthai-android}
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk.dir=//p' "$REPO/local.properties" 2>/dev/null)}}
NDK=${ANDROID_NDK:-$SDK/ndk/27.2.12479018}
ABI=arm64-v8a

mkdir -p "$WORK"
if [ ! -d "$WORK/core" ]; then
  git clone --depth 1 --branch $VERSION https://github.com/luxonis/depthai-core.git "$WORK/core"
  git -C "$WORK/core" submodule update --init --recursive --depth 1
fi
export HUNTER_ROOT=$WORK/hunter
cmake -S "$WORK/core" -B "$WORK/build" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-31 -DANDROID_STL=c++_shared \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON \
  -DDEPTHAI_OPENCV_SUPPORT=OFF -DDEPTHAI_ENABLE_CURL=OFF -DDEPTHAI_BUILD_EXAMPLES=OFF \
  -DDEPTHAI_BUILD_TESTS=OFF -DDEPTHAI_CLANG_FORMAT=OFF -DDEPTHAI_ENABLE_BACKWARD=OFF \
  -DCMAKE_INSTALL_PREFIX="$WORK/install"
cmake --build "$WORK/build"
cmake --install "$WORK/build"

STRIP=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip
LIBS=$REPO/app/src/main/jniLibs/$ABI
mkdir -p "$LIBS"
"$STRIP" --strip-unneeded -o "$LIBS/libdepthai-core.so" "$WORK/install/lib/libdepthai-core.so"
"$STRIP" --strip-unneeded -o "$LIBS/libusb-1.0.so" "$WORK/install/lib/cmake/depthai/dependencies/lib/libusb-1.0.so"
INC=$REPO/third_party/depthai-android/include
rm -rf "$INC"
mkdir -p "$INC/dependencies"
cp -r "$WORK/install/include/." "$INC/"
cp -r "$WORK/install/lib/cmake/depthai/dependencies/include/." "$INC/dependencies/"
echo "$VERSION" > "$REPO/third_party/depthai-android/VERSION"
echo "Vendored depthai-core $VERSION for $ABI"
