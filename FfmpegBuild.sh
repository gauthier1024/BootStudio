#!/bin/bash
# FFmpeg Build Script for BootStudio (F-Droid Compatible)

# 1. Setup Environment
# F-Droid provides ANDROID_NDK_HOME. Fallback to your local path for development.
NDK=${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/27.0.12077973}
TOOLCHAIN=$NDK/toolchains/llvm/prebuilt/linux-x86_64
API=24
FFMPEG_SRC="ffmpeg-src"
OUTPUT_DIR="$(pwd)/build/arm64-v8a"

echo "Using NDK: $NDK"

# 2. Get FFmpeg Source if missing
if [ ! -d "$FFMPEG_SRC" ]; then
    echo "Cloning FFmpeg source..."
    git clone --depth 1 --branch n6.1 https://github.com/FFmpeg/FFmpeg.git "$FFMPEG_SRC"
fi

cd "$FFMPEG_SRC" || exit 1
make clean

# 3. Configure
echo "Configuring FFmpeg..."
./configure \
--prefix="$OUTPUT_DIR" \
--target-os=android \
--arch=aarch64 \
--cpu=armv8-a \
--enable-cross-compile \
--cc=$TOOLCHAIN/bin/aarch64-linux-android$API-clang \
--cxx=$TOOLCHAIN/bin/aarch64-linux-android$API-clang++ \
--ar=$TOOLCHAIN/bin/llvm-ar \
--nm=$TOOLCHAIN/bin/llvm-nm \
--ranlib=$TOOLCHAIN/bin/llvm-ranlib \
--strip=$TOOLCHAIN/bin/llvm-strip \
--disable-static \
--enable-shared \
--enable-pic \
--disable-doc \
--disable-debug \
--disable-network \
--disable-autodetect \
--disable-everything \
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
--enable-filter=scale,fps,pad,null,format \
--extra-ldflags="-L$TOOLCHAIN/sysroot/usr/lib/aarch64-linux-android/$API -lz"

# 4. Build
echo "Building FFmpeg..."
make -j$(nproc)
make install
cd ..

# 5. Copy results to App Assets
ASSETS_DIR="app/src/main/assets"
echo "Copying binaries to $ASSETS_DIR..."
mkdir -p "$ASSETS_DIR"
cp build/arm64-v8a/lib/libavcodec.so "$ASSETS_DIR/"
cp build/arm64-v8a/lib/libavfilter.so "$ASSETS_DIR/"
cp build/arm64-v8a/lib/libavformat.so "$ASSETS_DIR/"
cp build/arm64-v8a/lib/libavutil.so "$ASSETS_DIR/"
cp build/arm64-v8a/lib/libswresample.so "$ASSETS_DIR/"
cp build/arm64-v8a/lib/libswscale.so "$ASSETS_DIR/"
# Note: libavdevice might not be built if not enabled, check if needed
if [ -f "build/arm64-v8a/lib/libavdevice.so" ]; then
    cp build/arm64-v8a/lib/libavdevice.so "$ASSETS_DIR/"
fi
cp build/arm64-v8a/bin/ffmpeg "$ASSETS_DIR/ffmpeg-bin"

echo "Finished"
