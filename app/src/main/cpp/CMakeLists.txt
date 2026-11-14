# Pocketpad++ native library: Scintilla + Lexilla + the Android platform layer, and Lua for scripts.
# Also builds on a desktop JDK (see hosttest/) because the platform layer only talks JNI.
cmake_minimum_required(VERSION 3.22.1)
project(nppscintilla CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

get_filename_component(THIRD_PARTY "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party" ABSOLUTE)
set(SCINTILLA_DIR "${THIRD_PARTY}/scintilla")
set(LEXILLA_DIR "${THIRD_PARTY}/lexilla")
set(LUA_DIR "${THIRD_PARTY}/lua/src")

file(GLOB SCINTILLA_SOURCES CONFIGURE_DEPENDS "${SCINTILLA_DIR}/src/*.cxx")
file(GLOB LEXILLA_SOURCES CONFIGURE_DEPENDS
    "${LEXILLA_DIR}/lexlib/*.cxx"
    "${LEXILLA_DIR}/lexers/*.cxx"
    "${LEXILLA_DIR}/src/Lexilla.cxx")
# Lua is built as C++ so that its errors are exceptions, which pass safely through the code around it.
file(GLOB LUA_SOURCES CONFIGURE_DEPENDS "${LUA_DIR}/*.c")
set_source_files_properties(${LUA_SOURCES} PROPERTIES LANGUAGE CXX)

add_library(nppscintilla SHARED
    Bridge.cpp
    PlatAndroid.cpp
    ScintillaAndroid.cpp
    JniEntry.cpp
    LuaBridge.cpp
    ${SCINTILLA_SOURCES}
    ${LEXILLA_SOURCES}
    ${LUA_SOURCES})

target_include_directories(nppscintilla PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"
    "${SCINTILLA_DIR}/include"
    "${SCINTILLA_DIR}/src"
    "${LEXILLA_DIR}/include"
    "${LEXILLA_DIR}/lexlib"
    "${LUA_DIR}")

# Notepad++'s user-defined-language lexer includes <windows.h>; compat/ supplies the little it uses.
set_source_files_properties("${LEXILLA_DIR}/lexers/LexUser.cxx" PROPERTIES
    INCLUDE_DIRECTORIES "${CMAKE_CURRENT_SOURCE_DIR}/compat")

target_compile_definitions(nppscintilla PRIVATE NDEBUG SCI_LEXER LUA_USE_POSIX)
# Notepad++'s copy of Sci_Position.h uses intptr_t without including <cstdint>.
target_compile_options(nppscintilla PRIVATE -fvisibility=hidden -include cstdint)

if(ANDROID)
    target_link_libraries(nppscintilla PRIVATE log)
    # Required for devices with 16 KB memory pages (Android 15+).
    target_link_options(nppscintilla PRIVATE "-Wl,-z,max-page-size=16384")
else()
    find_package(JNI REQUIRED)
    target_include_directories(nppscintilla PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_options(nppscintilla PRIVATE "-Wl,--no-undefined")
endif()
