cmake_minimum_required(VERSION 3.22.1)
project(localllm LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---- llama.cpp configuration ----
set(BUILD_SHARED_LIBS     ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON    ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP       OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL         OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE           OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE        OFF CACHE BOOL "" FORCE)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    # Build every ARM CPU variant (dotprod, i8mm, sve, ...) and pick the best one at runtime.
    set(GGML_BACKEND_DL       ON  CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS ON  CACHE BOOL "" FORCE)
    set(GGML_CPU_KLEIDIAI     ON  CACHE BOOL "" FORCE)
    set(GGML_OPENMP           ON  CACHE BOOL "" FORCE)
elseif(ANDROID_ABI STREQUAL "x86_64")
    # x86_64 is only used for emulators; one SSE4.2/AVX build (no AVX2: the stock emulator CPU lacks it) keeps compile times sane.
    set(GGML_BACKEND_DL       OFF CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP           OFF CACHE BOOL "" FORCE)
    set(GGML_SSE42            ON  CACHE BOOL "" FORCE)
    set(GGML_AVX              ON  CACHE BOOL "" FORCE)
    set(GGML_AVX2             OFF CACHE BOOL "" FORCE)
    set(GGML_FMA              OFF CACHE BOOL "" FORCE)
    set(GGML_F16C             ON  CACHE BOOL "" FORCE)
else()
    message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
endif()

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/llama.cpp)
add_subdirectory(${LLAMA_SRC} build-llama)

# ---- JNI bridge ----
add_library(localllm SHARED llama_jni.cpp)

target_include_directories(localllm PRIVATE
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/ggml/include)

target_link_libraries(localllm
        llama
        llama-common
        android
        log)
