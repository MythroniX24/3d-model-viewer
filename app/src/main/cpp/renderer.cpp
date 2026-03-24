#include "renderer.h"
#include "shader_utils.h"
#include <android/log.h>
#include <ctime>
#include <cstring>
#include <cfloat>
#include <algorithm>

#define TAG  "Renderer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ── GLSL Shaders ──────────────────────────────────────────────────────────────

static const char* kVertPhong = R"(#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNorm;
layout(location = 2) in vec2 aUV;
uniform mat4 uMVP;
uniform mat4 uModel;
uniform mat3 uNorm;
out vec3 vFragPos;
out vec3 vNormal;
out vec2 vUV;
void main() {
    vec4 worldPos = uModel * vec4(aPos, 1.0);
    vFragPos  = worldPos.xyz;
    vNormal   = normalize(uNorm * aNorm);
    vUV       = aUV;
    gl_Position = uMVP * vec4(aPos, 1.0);
}
)";

static const char* kFragPhong = R"(#version 300 es
precision mediump float;
in  vec3 vFragPos;
in  vec3 vNormal;
in  vec2 vUV;
out vec4 fragColor;
uniform vec3  uColor;
uniform vec3  uLightDir;
uniform float uAmbient;
uniform float uDiffuse;
void main() {
    vec3  norm    = normalize(vNormal);
    float diff    = max(dot(norm, -uLightDir), 0.0);
    vec3  viewDir = normalize(vec3(0.0, 0.0, 3.5) - vFragPos);
    vec3  reflDir = reflect(uLightDir, norm);
    float spec    = pow(max(dot(viewDir, reflDir), 0.0), 32.0) * 0.25;
    vec3  color   = (uAmbient + uDiffuse * diff) * uColor + spec;
    fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
)";

// Shared simple vertex shader for wireframe + bbox
static const char* kVertSimple = R"(#version 300 es
layout(location = 0) in vec3 aPos;
uniform mat4 uMVP;
void main() { gl_Position = uMVP * vec4(aPos, 1.0); }
)";

static const char* kFragWire = R"(#version 300 es
precision mediump float;
uniform vec4 uColor;
out vec4 fragColor;
void main() { fragColor = uColor; }
)";

// ── Time helper ───────────────────────────────────────────────────────────────
static int64_t nowNs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

// ── Ctor / Dtor ───────────────────────────────────────────────────────────────
Renderer::Renderer() = default;
Renderer::~Renderer() {
    if (m_vao)      glDeleteVertexArrays(1, &m_vao);
    if (m_vbo)      glDeleteBuffers(1, &m_vbo);
    if (m_ibo)      glDeleteBuffers(1, &m_ibo);
    if (m_bbVao)    glDeleteVertexArrays(1, &m_bbVao);
    if (m_bbVbo)    glDeleteBuffers(1, &m_bbVbo);
    if (m_bbIbo)    glDeleteBuffers(1, &m_bbIbo);
    if (m_mainProg) glDeleteProgram(m_mainProg);
    if (m_wireProg) glDeleteProgram(m_wireProg);
}

// ── init ─────────────────────────────────────────────────────────────────────
bool Renderer::init(int width, int height) {
    m_width = width; m_height = height;
    glViewport(0, 0, width, height);
    glClearColor(0.12f, 0.12f, 0.14f, 1.0f);
    glEnable(GL_DEPTH_TEST);
    glDepthFunc(GL_LEQUAL);
    glEnable(GL_CULL_FACE);
    glCullFace(GL_BACK);

    buildShaders();

    glGenVertexArrays(1, &m_vao);   glGenBuffers(1, &m_vbo);   glGenBuffers(1, &m_ibo);
    glGenVertexArrays(1, &m_bbVao); glGenBuffers(1, &m_bbVbo); glGenBuffers(1, &m_bbIbo);

    m_fpsTimerNs = nowNs();
    LOGI("Renderer init %dx%d", width, height);
    return !checkGLError("init");
}

void Renderer::resize(int w, int h) {
    m_width = w; m_height = h;
    glViewport(0, 0, w, h);
}

void Renderer::buildShaders() {
    if (m_mainProg) glDeleteProgram(m_mainProg);
    if (m_wireProg) glDeleteProgram(m_wireProg);
    m_mainProg = createProgram(kVertPhong,  kFragPhong);
    m_wireProg = createProgram(kVertSimple, kFragWire);
    if (!m_mainProg || !m_wireProg) LOGE("Shader build failed!");
}

// ── Upload mesh ───────────────────────────────────────────────────────────────
void Renderer::uploadMesh(const ModelData& md) {
    glBindVertexArray(m_vao);
    glBindBuffer(GL_ARRAY_BUFFER, m_vbo);
    glBufferData(GL_ARRAY_BUFFER,
                 (GLsizeiptr)(md.vertices.size() * sizeof(Vertex)),
                 md.vertices.data(), GL_STATIC_DRAW);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, m_ibo);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER,
                 (GLsizeiptr)(md.indices.size() * sizeof(unsigned int)),
                 md.indices.data(), GL_STATIC_DRAW);

    constexpr GLsizei stride = sizeof(Vertex);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, stride, (void*)offsetof(Vertex, px));
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, stride, (void*)offsetof(Vertex, nx));
    glEnableVertexAttribArray(2);
    glVertexAttribPointer(2, 2, GL_FLOAT, GL_FALSE, stride, (void*)offsetof(Vertex, u));
    glBindVertexArray(0);

    m_indexCount = (GLsizei)md.indices.size();
    m_hasModel   = true;
    buildBoundingBox();
}

// ── Bounding box (12 edges of unit cube scaled to [-1,1]^3) ─────────────────
void Renderer::buildBoundingBox() {
    static const float verts[8*3] = {
        -1,-1,-1,  1,-1,-1,  1,1,-1, -1,1,-1,   // back face
        -1,-1, 1,  1,-1, 1,  1,1, 1, -1,1, 1    // front face
    };
    static const uint16_t idx[24] = {
        0,1, 1,2, 2,3, 3,0,   // back
        4,5, 5,6, 6,7, 7,4,   // front
        0,4, 1,5, 2,6, 3,7    // pillars
    };
    glBindVertexArray(m_bbVao);
    glBindBuffer(GL_ARRAY_BUFFER, m_bbVbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(verts), verts, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 12, nullptr);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, m_bbIbo);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER, sizeof(idx), idx, GL_STATIC_DRAW);
    glBindVertexArray(0);
    m_bbIndexCount = 24;
}

// ── Model matrix ─────────────────────────────────────────────────────────────
Mat4 Renderer::buildModelMatrix() const {
    return Mat4::translation(m_posX, m_posY, m_posZ)
         * Mat4::rotationZ(m_rotZ * DEG2RAD)
         * Mat4::rotationY(m_rotY * DEG2RAD)
         * Mat4::rotationX(m_rotX * DEG2RAD)
         * Mat4::scale(m_scaX, m_scaY, m_scaZ);
}

// ── Main render ───────────────────────────────────────────────────────────────
void Renderer::draw() {
    updateFPS();
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    if (!m_hasModel || !m_mainProg || !m_wireProg) return;

    float aspect = (float)m_width / (float)(m_height > 0 ? m_height : 1);
    Mat4  proj   = Mat4::perspective(60.0f * DEG2RAD, aspect, 0.01f, 100.0f);

    float cp = cosf(m_camPitch), sp = sinf(m_camPitch);
    float cy = cosf(m_camYaw),   sy = sinf(m_camYaw);
    Vec3 eye{ m_panX + m_camDist * cp * sy,
              m_panY + m_camDist * sp,
                       m_camDist * cp * cy };
    Mat4 view  = Mat4::lookAt(eye, {m_panX, m_panY, 0}, {0,1,0});
    Mat4 model = buildModelMatrix();
    Mat4 mvp   = proj * view * model;
    Vec3 lightDir = Vec3{-0.4f, -1.0f, -0.3f}.normalized();

    // ── Solid / Wireframe pass ────────────────────────────────────────────
    if (m_wireframe) {
        // Solid dark fill first (for hidden-line removal effect)
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(1.0f, 1.0f);
        glUseProgram(m_mainProg);
        glUniformMatrix4fv(glGetUniformLocation(m_mainProg,"uMVP"),  1,GL_FALSE,mvp.m);
        glUniformMatrix4fv(glGetUniformLocation(m_mainProg,"uModel"),1,GL_FALSE,model.m);
        float nm[9]; model.toNormalMatrix(nm);
        glUniformMatrix3fv(glGetUniformLocation(m_mainProg,"uNorm"),1,GL_FALSE,nm);
        glUniform3f(glGetUniformLocation(m_mainProg,"uColor"),    0.05f,0.05f,0.08f);
        glUniform3f(glGetUniformLocation(m_mainProg,"uLightDir"), lightDir.x,lightDir.y,lightDir.z);
        glUniform1f(glGetUniformLocation(m_mainProg,"uAmbient"),  m_ambient);
        glUniform1f(glGetUniformLocation(m_mainProg,"uDiffuse"),  m_diffuse);
        glBindVertexArray(m_vao);
        glDrawElements(GL_TRIANGLES, m_indexCount, GL_UNSIGNED_INT, nullptr);
        glDisable(GL_POLYGON_OFFSET_FILL);

        // Wire overlay
        glUseProgram(m_wireProg);
        glUniformMatrix4fv(glGetUniformLocation(m_wireProg,"uMVP"),1,GL_FALSE,mvp.m);
        glUniform4f(glGetUniformLocation(m_wireProg,"uColor"), 0.2f,0.8f,1.0f,1.0f);
        glLineWidth(1.2f);
        glDrawElements(GL_LINES, m_indexCount, GL_UNSIGNED_INT, nullptr);
        glBindVertexArray(0);
    } else {
        glUseProgram(m_mainProg);
        glUniformMatrix4fv(glGetUniformLocation(m_mainProg,"uMVP"),  1,GL_FALSE,mvp.m);
        glUniformMatrix4fv(glGetUniformLocation(m_mainProg,"uModel"),1,GL_FALSE,model.m);
        float nm[9]; model.toNormalMatrix(nm);
        glUniformMatrix3fv(glGetUniformLocation(m_mainProg,"uNorm"),1,GL_FALSE,nm);
        glUniform3f(glGetUniformLocation(m_mainProg,"uColor"),    m_colorR,m_colorG,m_colorB);
        glUniform3f(glGetUniformLocation(m_mainProg,"uLightDir"), lightDir.x,lightDir.y,lightDir.z);
        glUniform1f(glGetUniformLocation(m_mainProg,"uAmbient"),  m_ambient);
        glUniform1f(glGetUniformLocation(m_mainProg,"uDiffuse"),  m_diffuse);
        glBindVertexArray(m_vao);
        glDrawElements(GL_TRIANGLES, m_indexCount, GL_UNSIGNED_INT, nullptr);
        glBindVertexArray(0);
    }

    // ── Bounding box overlay ──────────────────────────────────────────────
    if (m_showBBox && m_bbIndexCount > 0) {
        // Scale bbox to match model's normalized [-1,1] range
        Mat4 bbMvp = proj * view * model;
        glUseProgram(m_wireProg);
        glUniformMatrix4fv(glGetUniformLocation(m_wireProg,"uMVP"),1,GL_FALSE,bbMvp.m);
        glUniform4f(glGetUniformLocation(m_wireProg,"uColor"), 1.0f,0.6f,0.1f,0.8f);
        glLineWidth(1.5f);
        glBindVertexArray(m_bbVao);
        glDrawElements(GL_LINES, m_bbIndexCount, GL_UNSIGNED_SHORT, nullptr);
        glBindVertexArray(0);
    }
}

// ── FPS ───────────────────────────────────────────────────────────────────────
void Renderer::updateFPS() {
    int64_t now = nowNs();
    if (++m_frameCount >= 60) {
        float elapsed = (float)(now - m_fpsTimerNs) / 1e9f;
        m_fps = (elapsed > 0) ? (m_frameCount / elapsed) : 0;
        m_frameCount = 0;
        m_fpsTimerNs = now;
    }
}

// ── Camera ────────────────────────────────────────────────────────────────────
void Renderer::touchRotate(float dx, float dy) {
    m_camYaw   += dx * 0.005f;
    m_camPitch  = std::clamp(m_camPitch + dy * 0.005f, -PI*0.48f, PI*0.48f);
}
void Renderer::touchZoom(float factor) {
    m_camDist = std::clamp(m_camDist / factor, 0.2f, 30.0f);
}
void Renderer::touchPan(float dx, float dy) {
    float scale = m_camDist / (float)(m_height > 0 ? m_height : 1);
    m_panX -= dx * scale;
    m_panY += dy * scale;
}
void Renderer::resetCamera() {
    m_camYaw=0.4f; m_camPitch=0.3f; m_camDist=3.5f; m_panX=0; m_panY=0;
}

// ── Transforms ────────────────────────────────────────────────────────────────
void Renderer::setRotation(float x,float y,float z)    {m_rotX=x;m_rotY=y;m_rotZ=z;}
void Renderer::setTranslation(float x,float y,float z) {m_posX=x;m_posY=y;m_posZ=z;}
void Renderer::setScale(float x,float y,float z)       {m_scaX=x;m_scaY=y;m_scaZ=z;}
void Renderer::mirrorX() {m_scaX=-m_scaX;}
void Renderer::mirrorY() {m_scaY=-m_scaY;}
void Renderer::mirrorZ() {m_scaZ=-m_scaZ;}
void Renderer::resetTransform() {
    m_rotX=m_rotY=m_rotZ=0;
    m_posX=m_posY=m_posZ=0;
    m_scaX=m_scaY=m_scaZ=1;
}

// ── Visual ────────────────────────────────────────────────────────────────────
void Renderer::setColor(float r,float g,float b)  {m_colorR=r;m_colorG=g;m_colorB=b;}
void Renderer::setAmbient(float a) {m_ambient=std::clamp(a,0.0f,1.0f);}
void Renderer::setDiffuse(float d) {m_diffuse=std::clamp(d,0.0f,1.0f);}
void Renderer::setWireframe(bool on)     {m_wireframe=on;}
void Renderer::setShowBoundingBox(bool on) {m_showBBox=on;}

// ── Load ─────────────────────────────────────────────────────────────────────
bool Renderer::loadModel(const std::string& path) {
    ModelData md;
    if (!ModelLoader::load(path, md)) return false;
    uploadMesh(md);
    resetTransform();
    resetCamera();
    return true;
}

// ── Undo/Redo ────────────────────────────────────────────────────────────────
void Renderer::pushUndoState() {
    m_undoStack.push_back(getTransform());
    if ((int)m_undoStack.size() > MAX_UNDO)
        m_undoStack.erase(m_undoStack.begin());
    m_redoStack.clear();
}
void Renderer::undo() {
    if (m_undoStack.empty()) return;
    m_redoStack.push_back(getTransform());
    auto s=m_undoStack.back(); m_undoStack.pop_back();
    m_rotX=s.rotX;m_rotY=s.rotY;m_rotZ=s.rotZ;
    m_posX=s.posX;m_posY=s.posY;m_posZ=s.posZ;
    m_scaX=s.scaX;m_scaY=s.scaY;m_scaZ=s.scaZ;
}
void Renderer::redo() {
    if (m_redoStack.empty()) return;
    m_undoStack.push_back(getTransform());
    auto s=m_redoStack.back(); m_redoStack.pop_back();
    m_rotX=s.rotX;m_rotY=s.rotY;m_rotZ=s.rotZ;
    m_posX=s.posX;m_posY=s.posY;m_posZ=s.posZ;
    m_scaX=s.scaX;m_scaY=s.scaY;m_scaZ=s.scaZ;
}
TransformState Renderer::getTransform() const {
    return {m_rotX,m_rotY,m_rotZ,m_posX,m_posY,m_posZ,m_scaX,m_scaY,m_scaZ};
}

// ── Screenshot ───────────────────────────────────────────────────────────────
std::vector<uint8_t> Renderer::takeScreenshot() {
    std::vector<uint8_t> pixels((size_t)m_width * m_height * 4);
    glReadPixels(0,0,m_width,m_height,GL_RGBA,GL_UNSIGNED_BYTE,pixels.data());
    // Flip Y (GL origin is bottom-left)
    int rowBytes = m_width * 4;
    std::vector<uint8_t> row((size_t)rowBytes);
    for (int y = 0; y < m_height/2; ++y) {
        uint8_t* top = pixels.data() + y * rowBytes;
        uint8_t* bot = pixels.data() + (m_height-1-y) * rowBytes;
        memcpy(row.data(), top, rowBytes);
        memcpy(top, bot, rowBytes);
        memcpy(bot, row.data(), rowBytes);
    }
    return pixels;
}
