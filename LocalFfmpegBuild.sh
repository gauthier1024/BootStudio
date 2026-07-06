#!/bin/bash
set -e
set -o pipefail

# --- Variables ---
#NDK="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/27.0.12077973}"
NDK="/opt/android-sdk/ndk/27.0.12077973"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
API=24
TARGET=aarch64-linux-android

CC="$TOOLCHAIN/bin/clang"
CXX="$TOOLCHAIN/bin/clang++"
AR="$TOOLCHAIN/bin/llvm-ar"
NM="$TOOLCHAIN/bin/llvm-nm"
RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
STRIP="$TOOLCHAIN/bin/llvm-strip"

FFMPEG_SRC="${1:-ffmpeg-src}"
OUTPUT_DIR="$(pwd)/build/arm64-v8a"
mkdir -p "$OUTPUT_DIR"

cd "$FFMPEG_SRC"

# --- Configure ---
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
--enable-avcodec \
--enable-avformat \
--enable-avutil \
--enable-swscale \
--enable-avfilter \
--enable-swresample \
--enable-zlib \
--enable-ffmpeg \
--enable-decoder=h264,hevc,mpeg4,mjpeg,gif,png,aac,mp3,pcm_s16le \
--enable-demuxer=mov,avi,gif,mp3,wav,image2,concat \
--enable-parser=h264,hevc,mpeg4,mjpeg,png,gif \
--enable-encoder=png,mpeg4,pcm_s16le \
--enable-muxer=image2,wav,mp4,mov \
--enable-protocol=file,pipe \
--enable-filter=scale,fps,pad,null,format

sed -i 's/^#define FFMPEG_CONFIGURATION.*/#define FFMPEG_CONFIGURATION "reproducible-build"/' config.h

# --- Build ---
make -j1
make install
