cmake_minimum_required(VERSION 3.10.2)
project(moka_dsp LANGUAGES CXX)

add_library(moka_dsp SHARED moka_dsp.cpp)

target_compile_features(moka_dsp PRIVATE cxx_std_17)
target_compile_options(moka_dsp PRIVATE -O3 -fno-math-errno)

find_library(log-lib log)
target_link_libraries(moka_dsp PRIVATE ${log-lib})
