cmake_minimum_required(VERSION 3.4.1)

project(giflib C)

add_library( # Specifies the name of the library.
             gif

             # Sets the library as a shared library.
             SHARED

             # Provides a relative path to source file(s).
             gif.c
             giflib/dgif_lib.c
             giflib/gifalloc.c)
 
# Include libraries needed for gif lib
target_link_libraries(gif
                      android
                      log)
