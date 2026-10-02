cmake_minimum_required(VERSION 3.22)

project(qwen3_tts_android LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(QWEN3_TTS_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../external/qwen3-tts.cpp")
set(GGML_DIR "${QWEN3_TTS_ROOT}/ggml")

set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
if (NOT DEFINED QWEN3_ANDROID_OPENMP)
    set(QWEN3_ANDROID_OPENMP OFF)
endif()
if (NOT DEFINED QWEN3_ANDROID_VULKAN)
    set(QWEN3_ANDROID_VULKAN OFF)
endif()
if (NOT DEFINED QWEN3_ANDROID_OPENCL)
    set(QWEN3_ANDROID_OPENCL OFF)
endif()
set(GGML_OPENMP ${QWEN3_ANDROID_OPENMP} CACHE BOOL "" FORCE)
set(GGML_VULKAN ${QWEN3_ANDROID_VULKAN} CACHE BOOL "" FORCE)
set(GGML_OPENCL ${QWEN3_ANDROID_OPENCL} CACHE BOOL "" FORCE)
if (QWEN3_ANDROID_VULKAN AND CMAKE_HOST_WIN32 AND NOT DEFINED GGML_VULKAN_HOST_C_COMPILER)
    file(GLOB _qwen3_msvc_cl
        "C:/Program Files (x86)/Microsoft Visual Studio/2022/BuildTools/VC/Tools/MSVC/*/bin/Hostx64/x64/cl.exe"
        "C:/Program Files/Microsoft Visual Studio/2022/BuildTools/VC/Tools/MSVC/*/bin/Hostx64/x64/cl.exe"
    )
    list(SORT _qwen3_msvc_cl)
    list(REVERSE _qwen3_msvc_cl)
    if (_qwen3_msvc_cl)
        list(GET _qwen3_msvc_cl 0 _qwen3_host_cl)
        get_filename_component(_qwen3_host_cl_dir "${_qwen3_host_cl}" DIRECTORY)
        set(GGML_VULKAN_HOST_C_COMPILER "${_qwen3_host_cl}" CACHE FILEPATH "" FORCE)
        set(GGML_VULKAN_HOST_CXX_COMPILER "${_qwen3_host_cl}" CACHE FILEPATH "" FORCE)
        set(GGML_VULKAN_HOST_LINKER "${_qwen3_host_cl_dir}/link.exe" CACHE FILEPATH "" FORCE)
    endif()
endif()
if (QWEN3_ANDROID_VULKAN AND CMAKE_HOST_WIN32)
    set(_qwen3_glslc "${CMAKE_ANDROID_NDK}/shader-tools/windows-x86_64/glslc.exe")
    if (EXISTS "${_qwen3_glslc}")
        set(Vulkan_GLSLC_EXECUTABLE "${_qwen3_glslc}" CACHE FILEPATH "" FORCE)
    endif()
endif()

if (QWEN3_ANDROID_VULKAN AND DEFINED QWEN3_ANDROID_VULKAN_HEADERS_DIR)
    if (EXISTS "${QWEN3_ANDROID_VULKAN_HEADERS_DIR}/vulkan/vulkan.hpp")
        include_directories(SYSTEM "${QWEN3_ANDROID_VULKAN_HEADERS_DIR}")
        message(STATUS "Using Android Vulkan C++ headers: ${QWEN3_ANDROID_VULKAN_HEADERS_DIR}")
    else()
        message(FATAL_ERROR "Vulkan C++ headers not found at ${QWEN3_ANDROID_VULKAN_HEADERS_DIR}")
    endif()
endif()

add_subdirectory("${GGML_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/ggml")

find_package(Threads REQUIRED)

function(qwen3_link_ggml target_name)
    target_link_libraries(${target_name} PUBLIC ggml ggml-base ggml-cpu)
    if (QWEN3_ANDROID_VULKAN)
        target_link_libraries(${target_name} PUBLIC ggml-vulkan)
        target_compile_definitions(${target_name} PUBLIC QWEN3_TTS_VULKAN_ENABLED)
    endif()
    if (QWEN3_ANDROID_OPENCL)
        target_link_libraries(${target_name} PUBLIC ggml-opencl)
        target_compile_definitions(${target_name} PUBLIC QWEN3_TTS_OPENCL_ENABLED)
    endif()
endfunction()

add_library(text_tokenizer STATIC
    "${QWEN3_TTS_ROOT}/src/text_tokenizer.cpp"
    "${QWEN3_TTS_ROOT}/src/gguf_loader.cpp"
    "${QWEN3_TTS_ROOT}/src/tokenizer_unicode.cpp"
    "${QWEN3_TTS_ROOT}/src/tokenizer_unicode_data.cpp"
)
target_include_directories(text_tokenizer PUBLIC "${QWEN3_TTS_ROOT}/src" "${GGML_DIR}/include")
qwen3_link_ggml(text_tokenizer)
target_link_libraries(text_tokenizer PUBLIC Threads::Threads)

add_library(tts_transformer STATIC
    "${QWEN3_TTS_ROOT}/src/gguf_loader.cpp"
    "${QWEN3_TTS_ROOT}/src/tts_transformer.cpp"
    "${QWEN3_TTS_ROOT}/src/transformer/transformer_cache.cpp"
    "${QWEN3_TTS_ROOT}/src/transformer/transformer_debug.cpp"
    "${QWEN3_TTS_ROOT}/src/transformer/transformer_embeddings.cpp"
    "${QWEN3_TTS_ROOT}/src/transformer/transformer_graph_code_pred.cpp"
    "${QWEN3_TTS_ROOT}/src/transformer/transformer_graph_talker.cpp"
    "${QWEN3_TTS_ROOT}/src/transformer/transformer_loader.cpp"
    "${QWEN3_TTS_ROOT}/src/transformer/transformer_loader_metadata.cpp"
    "${QWEN3_TTS_ROOT}/src/transformer/transformer_loader_tensors.cpp"
    "${QWEN3_TTS_ROOT}/src/transformer/transformer_runtime.cpp"
    "${QWEN3_TTS_ROOT}/src/transformer/transformer_runtime_code_pred.cpp"
    "${QWEN3_TTS_ROOT}/src/transformer/transformer_generate.cpp"
    "${QWEN3_TTS_ROOT}/src/coreml_code_predictor_stub.cpp"
)
target_include_directories(tts_transformer PUBLIC "${QWEN3_TTS_ROOT}/src" "${GGML_DIR}/include")
qwen3_link_ggml(tts_transformer)
target_link_libraries(tts_transformer PUBLIC Threads::Threads)

add_library(audio_tokenizer_encoder STATIC
    "${QWEN3_TTS_ROOT}/src/audio_tokenizer_encoder.cpp"
    "${QWEN3_TTS_ROOT}/src/encoder/encoder_frontend.cpp"
    "${QWEN3_TTS_ROOT}/src/encoder/encoder_graph.cpp"
    "${QWEN3_TTS_ROOT}/src/encoder/encoder_loader.cpp"
    "${QWEN3_TTS_ROOT}/src/encoder/encoder_runtime.cpp"
    "${QWEN3_TTS_ROOT}/src/gguf_loader.cpp"
)
target_include_directories(audio_tokenizer_encoder PUBLIC "${QWEN3_TTS_ROOT}/src" "${GGML_DIR}/include")
qwen3_link_ggml(audio_tokenizer_encoder)
target_link_libraries(audio_tokenizer_encoder PUBLIC Threads::Threads)

add_library(speech_tokenizer_encoder STATIC
    "${QWEN3_TTS_ROOT}/src/speech_tokenizer_encoder.cpp"
    "${QWEN3_TTS_ROOT}/src/gguf_loader.cpp"
)
target_include_directories(speech_tokenizer_encoder PUBLIC "${QWEN3_TTS_ROOT}/src" "${GGML_DIR}/include")
qwen3_link_ggml(speech_tokenizer_encoder)
target_link_libraries(speech_tokenizer_encoder PUBLIC Threads::Threads)

add_library(audio_tokenizer_decoder STATIC
    "${QWEN3_TTS_ROOT}/src/decoder/decoder_cache.cpp"
    "${QWEN3_TTS_ROOT}/src/decoder/decoder_graph.cpp"
    "${QWEN3_TTS_ROOT}/src/decoder/decoder_layers.cpp"
    "${QWEN3_TTS_ROOT}/src/decoder/decoder_loader.cpp"
    "${QWEN3_TTS_ROOT}/src/decoder/decoder_runtime.cpp"
    "${QWEN3_TTS_ROOT}/src/gguf_loader.cpp"
)
target_include_directories(audio_tokenizer_decoder PUBLIC "${QWEN3_TTS_ROOT}/src" "${GGML_DIR}/include")
qwen3_link_ggml(audio_tokenizer_decoder)
target_link_libraries(audio_tokenizer_decoder PUBLIC Threads::Threads)

add_library(qwen3_tts STATIC
    "${QWEN3_TTS_ROOT}/src/common/audio_io.cpp"
    "${QWEN3_TTS_ROOT}/src/common/icl_prompt_io.cpp"
    "${QWEN3_TTS_ROOT}/src/common/speaker_embedding_io.cpp"
    "${QWEN3_TTS_ROOT}/src/pipeline/pipeline_models.cpp"
    "${QWEN3_TTS_ROOT}/src/pipeline/pipeline_runtime.cpp"
    "${QWEN3_TTS_ROOT}/src/pipeline/pipeline_synthesize.cpp"
    "${QWEN3_TTS_ROOT}/src/qwen3_tts.cpp"
    "${QWEN3_TTS_ROOT}/src/qwen3_tts_c.cpp"
)
target_include_directories(qwen3_tts PUBLIC "${QWEN3_TTS_ROOT}/src" "${GGML_DIR}/include")
target_link_libraries(qwen3_tts PUBLIC
    text_tokenizer
    tts_transformer
    audio_tokenizer_encoder
    speech_tokenizer_encoder
    audio_tokenizer_decoder
    Threads::Threads
)
target_compile_definitions(qwen3_tts PRIVATE QWEN3_TTS_EXPORT)

add_library(qwen3_tts_jni SHARED
    "${QWEN3_TTS_ROOT}/src/qwen3_tts_jni.cpp"
)
target_include_directories(qwen3_tts_jni PRIVATE "${QWEN3_TTS_ROOT}/src")
target_compile_definitions(qwen3_tts_jni PRIVATE QWEN3_TTS_STATIC)
target_link_libraries(qwen3_tts_jni PRIVATE qwen3_tts log)
