cmake_minimum_required(VERSION 3.22.1)
project(ohmpty LANGUAGES CXX)

add_library(ohmpty SHARED pty.cpp)
target_compile_features(ohmpty PRIVATE cxx_std_17)
target_compile_options(ohmpty PRIVATE -Wall -Wextra -Werror)
