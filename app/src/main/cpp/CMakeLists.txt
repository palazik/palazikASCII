cmake_minimum_required(VERSION 3.22.1)
project("palazikascii")

add_library(
        palazikascii
        SHARED
        native-lib.cpp
)

find_library(log-lib log)

target_link_libraries(
        palazikascii
        ${log-lib}
)
