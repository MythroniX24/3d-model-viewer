#include "shader_utils.h"
#include <android/log.h>
#include <vector>

#define TAG "ModelViewer"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Compile a shader stage and return handle
GLuint compileShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    if (!shader) {
        LOGE("glCreateShader failed type=%d", type);
        return 0;
    }
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);

    GLint compiled = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        GLint len = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &len);
        std::vector<char> log(len + 1);
        glGetShaderInfoLog(shader, len, nullptr, log.data());
        LOGE("Shader compile error:\n%s", log.data());
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

// Link vert + frag into a program
GLuint createProgram(const char* vertSrc, const char* fragSrc) {
    GLuint vert = compileShader(GL_VERTEX_SHADER,   vertSrc);
    GLuint frag = compileShader(GL_FRAGMENT_SHADER, fragSrc);
    if (!vert || !frag) {
        if (vert) glDeleteShader(vert);
        if (frag) glDeleteShader(frag);
        return 0;
    }
    GLuint prog = glCreateProgram();
    glAttachShader(prog, vert);
    glAttachShader(prog, frag);
    glLinkProgram(prog);
    glDeleteShader(vert);
    glDeleteShader(frag);

    GLint linked = 0;
    glGetProgramiv(prog, GL_LINK_STATUS, &linked);
    if (!linked) {
        GLint len = 0;
        glGetProgramiv(prog, GL_INFO_LOG_LENGTH, &len);
        std::vector<char> log(len + 1);
        glGetProgramInfoLog(prog, len, nullptr, log.data());
        LOGE("Program link error:\n%s", log.data());
        glDeleteProgram(prog);
        return 0;
    }
    return prog;
}

// Log and return true if there's an active GL error
bool checkGLError(const char* tag) {
    GLenum err = glGetError();
    if (err != GL_NO_ERROR) {
        LOGE("[%s] GL Error: 0x%04x", tag, err);
        return true;
    }
    return false;
}
