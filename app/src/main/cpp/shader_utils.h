#pragma once
#include <GLES3/gl3.h>
#include <string>

// Compile a single shader stage; returns 0 on failure
GLuint compileShader(GLenum type, const char* source);

// Link vertex + fragment into a program; returns 0 on failure
GLuint createProgram(const char* vertSrc, const char* fragSrc);

// Check GL error, log with tag; returns true if error found
bool checkGLError(const char* tag);
