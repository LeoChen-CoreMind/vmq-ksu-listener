cmake_minimum_required(VERSION 3.22)
project(vmqd C)
add_executable(vmqd src/vmqd.c)
target_compile_options(vmqd PRIVATE -Wall -Wextra -Werror -O2)
