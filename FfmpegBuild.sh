#!/bin/bash
set -e
set -o pipefail

PROJECT_ROOT="$(pwd)"

echo "Searching for Android NDK toolchain..."

# Priority 1: ANDROID_NDK_HOME
if [ -n "$ANDROID_NDK_HOME" ] && [ -d "$ANDROID_NDK_HOME" ]; then
    NDK="$ANDROID_NDK_HOME"
fi

# Priority 2: Deep search in /opt (standard for CI)
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
    echo "Searching /opt for clang..."
    CLANG_PATH=$(find /opt -name "clang" -type f 2>/dev/null | grep "toolchains/llvm/prebuilt" | head -n 1 || true)
    if [ -n "$CLANG_PATH" ]; then
        TOOLCHAIN=$(dirname $(dirname "$CLANG_PATH"))
        NDK=$(echo "$TOOLCHAIN" | sed 's|/toolchains/.*||')
    fi
fi

# Priority 3: Local fallback (Only if not in Docker/CI)
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
    if [ -d "/repo" ]; then
        echo "Error: NDK not found in /opt inside Docker container."
        exit 1
    fi
    NDK=$HOME/Android/Sdk/ndk/27.0.12077973
fi

if [ ! -d "$NDK" ]; then
    echo "Error: Android NDK not found at $NDK"
    exit 1
fi

echo "Using NDK: $NDK"

# Find the prebuilt toolchain directory
if [ -z "$TOOLCHAIN" ]; then
    TOOLCHAIN=$(find "$NDK" -name "llvm" -type d -prune)/prebuilt/linux-x86_64
    if [ ! -d "$TOOLCHAIN" ]; then
        TOOLCHAIN=$(find "$NDK" -path "*/prebuilt/*" -type d -maxdepth 4 | head -n 1)
    fi
fi

echo "Toolchain path: $TOOLCHAIN"
CC=$TOOLCHAIN/bin/clang
CXX=$TOOLCHAIN/bin/clang++
AR=$TOOLCHAIN/bin/llvm-ar
NM=$TOOLCHAIN/bin/llvm-nm
RANLIB=$TOOLCHAIN/bin/llvm-ranlib
STRIP=$TOOLCHAIN/bin/llvm-strip

API=24
TARGET=aarch64-linux-android

# Use first argument as FFmpeg source path
FFMPEG_SRC="${1:-ffmpeg-src}"
OUTPUT_DIR="$(pwd)/build/arm64-v8a"
mkdir -p "$OUTPUT_DIR"

# 2. Get FFmpeg Source if missing
if [ ! -d "$FFMPEG_SRC" ]; then
    echo "Cloning FFmpeg source..."
    git clone --depth 1 --branch n6.1 https://github.com/FFmpeg/FFmpeg.git "$FFMPEG_SRC"
fi

echo "Cleaning FFmpeg source tree..."
cd "$FFMPEG_SRC" || exit 1
git clean -dfx
git reset --hard

# 3. Configure
echo "Configuring FFmpeg..."
./configure \
--prefix="$OUTPUT_DIR" \
--target-os=android \
--arch=aarch64 \
--cpu=armv8-a \
--enable-cross-compile \
--cc=$CC \
--cxx=$CXX \
--ar=$AR \
--as=$CC \
--nm=$NM \
--ranlib=$RANLIB \
--strip=$STRIP \
--extra-cflags="-target $TARGET$API -fPIC -ffile-prefix-map=$(pwd)=/build -ffile-prefix-map=$NDK=/ndk" \
--extra-ldflags="-target $TARGET$API -lz" \
--disable-static \
--enable-shared \
--enable-pic \
--disable-doc \
--disable-debug \
--disable-network \
--disable-autodetect \
--disable-everything \
--disable-avdevice \
--enable-ffmpeg \
--enable-avcodec \
--enable-avformat \
--enable-avutil \
--enable-swscale \
--enable-avfilter \
--enable-swresample \
--enable-zlib \
--enable-decoder=h264,hevc,mpeg4,mjpeg,gif,png,aac,mp3,pcm_s16le \
--enable-demuxer=mov,avi,gif,mp3,wav,image2,concat \
--enable-parser=h264,hevc,mpeg4,mjpeg,png,gif \
--enable-encoder=png,mpeg4,pcm_s16le \
--enable-muxer=image2,wav,mp4,mov \
--enable-protocol=file,pipe \
--enable-filter=scale,fps,pad,null,format || { tail -n 50 ffbuild/config.log; exit 1; }

# Normalize embedded configuration string (removes machine-specific absolute paths)
sed -i 's/^#define FFMPEG_CONFIGURATION.*/#define FFMPEG_CONFIGURATION "reproducible-build"/' config.h

# 4. Build
echo "Building FFmpeg..."
make -j1
make install

cd "$PROJECT_ROOT"

# 5. Copy results to App Assets

ASSETS_DIR="app/src/main/assets"
echo "Copying binaries to $ASSETS_DIR..."
mkdir -p "$ASSETS_DIR"

for lib in avcodec avfilter avformat avutil swresample swscale; do
    src="$OUTPUT_DIR/lib/lib${lib}.so"
    if [ ! -f "$src" ]; then
        echo "ERROR: $src not found"
        exit 1
    fi
    cp "$src" "$ASSETS_DIR/"
done

cp "$OUTPUT_DIR/bin/ffmpeg" "$ASSETS_DIR/ffmpeg-bin"
echo "Finished"