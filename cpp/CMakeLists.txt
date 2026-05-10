# SPDX-License-Identifier: EPL-2.0
#
# alembic patch compiler
#
# Builds Faust-generated CLAP C++ source files into loadable .clap plugin bundles.
# This is alembic's own build infrastructure — it is independent of kairos.
#
# Workflow:
#   1. alembic.compile (Clojure) writes a staged patch under patches/<name>/:
#        patches/<name>/<name>_clap.cpp   — from `faust -a clap-arch.cpp`
#        patches/<name>/plugin_metadata.h — generated from the patch graph
#   2. cmake --build cpp/build --target <name>   (or --target all)
#   3. Output: cpp/build/plugins/<name>.clap
#
# First-time setup (from the alembic repo root):
#   cmake -S cpp -B cpp/build
#   cmake --build cpp/build
#
# Adding a new patch requires a cmake reconfigure only if it was not present
# at the last configure run. alembic.compile handles this automatically.

cmake_minimum_required(VERSION 3.20)

project(alembic-patches
    VERSION     0.1.0
    DESCRIPTION "alembic compiled CLAP patch plugins"
    LANGUAGES   C CXX)

include(FetchContent)
include(cmake/AlembicPatch.cmake)

# ---------------------------------------------------------------------------
# Dependency policy — mirrors kairos conventions
# ---------------------------------------------------------------------------

set(FETCHCONTENT_UPDATES_DISCONNECTED ON CACHE BOOL
    "Skip network fetch if already populated")

# CLAP SDK — header-only. Pin matches kairos to keep both on the same ABI.
FetchContent_Declare(clap
    GIT_REPOSITORY https://github.com/free-audio/clap.git
    GIT_TAG        1.2.6
    GIT_SHALLOW    TRUE)

# clap-helpers — header-only C++ wrappers. Required by the Faust CLAP arch.
# No versioned tags; pin to a known-good commit on main.
FetchContent_Declare(clap-helpers
    GIT_REPOSITORY https://github.com/free-audio/clap-helpers.git
    GIT_TAG        3625679
    GIT_SHALLOW    FALSE)

FetchContent_MakeAvailable(clap clap-helpers)

# ---------------------------------------------------------------------------
# Faust toolchain paths — resolved at configure time from the system install
# ---------------------------------------------------------------------------

execute_process(
    COMMAND faust --includedir
    OUTPUT_VARIABLE FAUST_INCLUDE_DIR
    OUTPUT_STRIP_TRAILING_WHITESPACE
    RESULT_VARIABLE _faust_includedir_result)

execute_process(
    COMMAND faust --archdir
    OUTPUT_VARIABLE FAUST_ARCH_DIR
    OUTPUT_STRIP_TRAILING_WHITESPACE
    RESULT_VARIABLE _faust_archdir_result)

if(NOT _faust_includedir_result EQUAL 0 OR NOT _faust_archdir_result EQUAL 0)
    message(FATAL_ERROR
        "faust not found on PATH — install via: brew install faust\n"
        "Minimum required version: 2.50")
endif()

message(STATUS "Faust include dir: ${FAUST_INCLUDE_DIR}")
message(STATUS "Faust arch dir:    ${FAUST_ARCH_DIR}")

# ---------------------------------------------------------------------------
# Discover and build staged patches
#
# Each subdirectory of patches/ that contains a <name>_clap.cpp file becomes
# a cmake target. New patches are picked up on the next cmake reconfigure.
# ---------------------------------------------------------------------------

set(PATCHES_DIR "${CMAKE_CURRENT_SOURCE_DIR}/patches")

file(GLOB _patch_dirs LIST_DIRECTORIES true "${PATCHES_DIR}/*")

foreach(_patch_dir ${_patch_dirs})
    if(IS_DIRECTORY "${_patch_dir}")
        get_filename_component(_name "${_patch_dir}" NAME)
        set(_cpp "${_patch_dir}/${_name}_clap.cpp")
        if(EXISTS "${_cpp}")
            message(STATUS "Adding alembic patch: ${_name}")
            alembic_add_patch(${_name} ${_cpp} ${_patch_dir})
        endif()
    endif()
endforeach()
