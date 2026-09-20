cmake_minimum_required(VERSION 3.22.1)
project(ohmpty LANGUAGES C CXX)

add_library(ohmpty SHARED pty.cpp)
target_compile_features(ohmpty PRIVATE cxx_std_17)
target_compile_options(ohmpty PRIVATE -Wall -Wextra -Werror)

# Rebuild Termux's JNI shim with the current NDK so every packaged native
# library supports Android's 16 KB memory pages. The Maven artifact bundles an
# older 4 KB-aligned libtermux.so that Play rejects for target SDK 35+.
add_library(termux SHARED termux.c)
target_compile_options(termux PRIVATE -Wall -Wextra -Werror)
