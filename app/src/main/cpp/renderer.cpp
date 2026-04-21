#include "renderer.h"
#include "mesh_separator.h"
#include "shader_utils.h"
#include <android/log.h>
#include <ctime>
#include <cstring>
#include <cfloat>
#include <algorithm>
#include <cmath>
#include <fstream>
#include <sstream>
#include <numeric>
// unordered_map removed — using MeshSeparator now

#define TAG "Renderer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG,__VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG,__VA_ARGS__)

// ── Shaders ───────────────────────────────────────────────────────────────────
static const char* kVertPhong = R"(#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aNorm;
layout(location=2) in vec2 aUV;
uniform mat4 uMVP, uModel;
uniform mat3 uNorm;
out vec3 vFragPos, vNormal;
void main(){
    vec4 wp = uModel * vec4(aPos,1.0);
    vFragPos  = wp.xyz;
    vNormal   = normalize(uNorm * aNorm);
    gl_Position = uMVP * vec4(aPos,1.0);
})";

static const char* kFragPhong = R"(#version 300 es
precision highp float;
in vec3 vFragPos, vNormal;
out vec4 fragColor;
uniform vec3  uColor, uLightDir;
uniform float uAmbient, uDiffuse;
uniform int   uSelected;
uniform vec3  uCamPos;

// Reinhard tonemapping — keeps bright areas from blowing out
vec3 tonemap(vec3 c){ return c / (c + vec3(1.0)); }

void main(){
    vec3 N = normalize(vNormal);
    vec3 V = normalize(uCamPos - vFragPos);

    // Key light: warm white, upper-left-front
    vec3 L1  = normalize(vec3(-0.5, 1.2, 1.0));
    float d1 = max(dot(N, L1), 0.0);
    vec3 H1  = normalize(L1 + V);
    float s1 = pow(max(dot(N, H1), 0.0), 64.0) * 0.6;

    // Fill light: cool blue, right side
    vec3 L2  = normalize(vec3(1.0, 0.2, -0.3));
    float d2 = max(dot(N, L2), 0.0) * 0.35;

    // Back/rim light: creates silhouette edge
    float rim = pow(1.0 - max(dot(N, V), 0.0), 3.0) * 0.22;

    // Ground bounce: subtle warm upwelling
    float bounce = max(dot(N, vec3(0.0, -1.0, 0.0)), 0.0) * 0.08;

    vec3 keyContrib  = (d1 * uColor + vec3(s1 * 0.9, s1 * 0.95, s1)) * vec3(1.0, 0.97, 0.92);
    vec3 fillContrib = d2 * uColor * vec3(0.7, 0.82, 1.0);
    vec3 rimContrib  = rim * vec3(0.4, 0.65, 1.0);
    vec3 ambContrib  = uAmbient * uColor;
    vec3 bncContrib  = bounce * uColor * vec3(1.0, 0.9, 0.7);

    vec3 c = ambContrib
           + uDiffuse * keyContrib
           + uDiffuse * fillContrib
           + rimContrib
           + bncContrib;

    // Gamma correction (linear → sRGB)
    c = tonemap(c);
    c = pow(clamp(c, 0.0, 1.0), vec3(1.0 / 2.2));

    if(uSelected == 1) c = mix(c, vec3(0.1, 0.9, 1.0), 0.35);

    fragColor = vec4(c, 1.0);
})";

static const char* kVertSimple = R"(#version 300 es
layout(location=0) in vec3 aPos;
uniform mat4 uMVP;
uniform float uPointSize;
void main(){
    gl_Position  = uMVP * vec4(aPos,1.0);
    gl_PointSize = uPointSize;
})";

static const char* kFragSimple = R"(#version 300 es
precision mediump float;
uniform vec4 uColor;
out vec4 fragColor;
void main(){ fragColor = uColor; })";

// ── Helpers ───────────────────────────────────────────────────────────────────
static int64_t nowNs(){
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC,&ts);
    return (int64_t)ts.tv_sec*1000000000LL+(int64_t)ts.tv_nsec;
}

// ── Ctor/Dtor ────────────────────────────────────────────────────────────────
Renderer::Renderer()=default;
Renderer::~Renderer(){
    delete m_pendingData;
    for(auto& mo:m_meshes){
        if(mo.vao) glDeleteVertexArrays(1,&mo.vao);
        if(mo.vbo) glDeleteBuffers(1,&mo.vbo);
        if(mo.ibo) glDeleteBuffers(1,&mo.ibo);
    }
    if(m_bbVao)    glDeleteVertexArrays(1,&m_bbVao);
    if(m_bbVbo)    glDeleteBuffers(1,&m_bbVbo);
    if(m_bbIbo)    glDeleteBuffers(1,&m_bbIbo);
    if(m_rulerVao) glDeleteVertexArrays(1,&m_rulerVao);
    if(m_rulerVbo) glDeleteBuffers(1,&m_rulerVbo);
    if(m_mainProg) glDeleteProgram(m_mainProg);
    if(m_wireProg) glDeleteProgram(m_wireProg);
}

// ── Init ────────────────────────────────────────────────────────────────────
bool Renderer::init(int w,int h){
    m_width=w; m_height=h;
    glViewport(0,0,w,h);
    glClearColor(0.05f,0.05f,0.08f,1.0f);
    glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LEQUAL);
    glEnable(GL_CULL_FACE);  glCullFace(GL_BACK);

    buildShaders();

    // Bounding box
    glGenVertexArrays(1,&m_bbVao); glGenBuffers(1,&m_bbVbo); glGenBuffers(1,&m_bbIbo);
    buildBoundingBox();

    // Ruler VAO
    glGenVertexArrays(1,&m_rulerVao); glGenBuffers(1,&m_rulerVbo);
    glBindVertexArray(m_rulerVao);
    glBindBuffer(GL_ARRAY_BUFFER,m_rulerVbo);
    float dummy[6]={};
    glBufferData(GL_ARRAY_BUFFER,sizeof(dummy),dummy,GL_DYNAMIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0,3,GL_FLOAT,GL_FALSE,12,nullptr);
    glBindVertexArray(0);

    m_fpsTimerNs=nowNs();
    return !checkGLError("init");
}

void Renderer::resize(int w,int h){ m_width=w; m_height=h; glViewport(0,0,w,h); }

void Renderer::buildShaders(){
    if(m_mainProg) glDeleteProgram(m_mainProg);
    if(m_wireProg) glDeleteProgram(m_wireProg);
    m_mainProg=createProgram(kVertPhong,kFragPhong);
    m_wireProg=createProgram(kVertSimple,kFragSimple);
    cacheUniformLocs();
}

void Renderer::cacheUniformLocs(){
    // Cache all uniform locations once after shader compilation.
    // glGetUniformLocation is slow — calling it 9x per mesh per frame kills FPS.
    m_uloc.mvp      = glGetUniformLocation(m_mainProg, "uMVP");
    m_uloc.model    = glGetUniformLocation(m_mainProg, "uModel");
    m_uloc.norm     = glGetUniformLocation(m_mainProg, "uNorm");
    m_uloc.color    = glGetUniformLocation(m_mainProg, "uColor");
    m_uloc.lightDir = glGetUniformLocation(m_mainProg, "uLightDir");
    m_uloc.ambient  = glGetUniformLocation(m_mainProg, "uAmbient");
    m_uloc.diffuse  = glGetUniformLocation(m_mainProg, "uDiffuse");
    m_uloc.selected = glGetUniformLocation(m_mainProg, "uSelected");
    m_uloc.camPos   = glGetUniformLocation(m_mainProg, "uCamPos");
    m_uloc.wireMvp  = glGetUniformLocation(m_wireProg,  "uMVP");
    m_uloc.wireColor     = glGetUniformLocation(m_wireProg, "uColor");
    m_uloc.wirePointSize = glGetUniformLocation(m_wireProg, "uPointSize");
}

// ── Bounding box ─────────────────────────────────────────────────────────────
void Renderer::buildBoundingBox(){
    static const float v[24]={-1,-1,-1,1,-1,-1,1,1,-1,-1,1,-1,-1,-1,1,1,-1,1,1,1,1,-1,1,1};
    static const uint16_t idx[24]={0,1,1,2,2,3,3,0,4,5,5,6,6,7,7,4,0,4,1,5,2,6,3,7};
    glBindVertexArray(m_bbVao);
    glBindBuffer(GL_ARRAY_BUFFER,m_bbVbo);
    glBufferData(GL_ARRAY_BUFFER,sizeof(v),v,GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0,3,GL_FLOAT,GL_FALSE,12,nullptr);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,m_bbIbo);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER,sizeof(idx),idx,GL_STATIC_DRAW);
    glBindVertexArray(0);
    m_bbIndexCount=24;
}

// ── Upload single MeshObject to GPU ──────────────────────────────────────────
void Renderer::uploadMeshObject(MeshObject& mo){
    if(!mo.vao){ glGenVertexArrays(1,&mo.vao); glGenBuffers(1,&mo.vbo); glGenBuffers(1,&mo.ibo); }
    glBindVertexArray(mo.vao);
    glBindBuffer(GL_ARRAY_BUFFER,mo.vbo);
    glBufferData(GL_ARRAY_BUFFER,(GLsizeiptr)(mo.vertices.size()*sizeof(Vertex)),mo.vertices.data(),GL_STATIC_DRAW);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,mo.ibo);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER,(GLsizeiptr)(mo.indices.size()*sizeof(unsigned int)),mo.indices.data(),GL_STATIC_DRAW);
    constexpr GLsizei stride=sizeof(Vertex);
    glEnableVertexAttribArray(0); glVertexAttribPointer(0,3,GL_FLOAT,GL_FALSE,stride,(void*)offsetof(Vertex,px));
    glEnableVertexAttribArray(1); glVertexAttribPointer(1,3,GL_FLOAT,GL_FALSE,stride,(void*)offsetof(Vertex,nx));
    glEnableVertexAttribArray(2); glVertexAttribPointer(2,2,GL_FLOAT,GL_FALSE,stride,(void*)offsetof(Vertex,u));
    glBindVertexArray(0);
    mo.gpuReady=true;
}

// ── separateIntoMeshes — GL thread version (used by legacy loadModel) ─────────
// Uses production MeshSeparator then uploads to GPU.
void Renderer::separateIntoMeshes(const ModelData& md){
    for(auto& mo:m_meshes){
        if(mo.vao) glDeleteVertexArrays(1,&mo.vao);
        if(mo.vbo) glDeleteBuffers(1,&mo.vbo);
        if(mo.ibo) glDeleteBuffers(1,&mo.ibo);
    }
    m_meshes.clear();
    if(md.vertices.empty() || md.indices.empty()) return;

    std::vector<MeshComponent> components;
    m_separator.separate(
        md.vertices.data(), (uint32_t)md.vertices.size(),
        md.indices.data(),  (uint32_t)(md.indices.size()/3),
        components);

    for(auto& comp : components){
        if(comp.vertices.empty()) continue;
        MeshObject mo;
        mo.name    = "Mesh_" + std::to_string(m_meshes.size()+1);
        mo.colorR  = m_colorR; mo.colorG = m_colorG; mo.colorB = m_colorB;
        mo.vertices = std::move(comp.vertices);
        mo.indices.resize(comp.indices.size());
        std::copy(comp.indices.begin(), comp.indices.end(), mo.indices.begin());
        uploadMeshObject(mo);
        m_meshes.push_back(std::move(mo));
    }
    LOGI("separateIntoMeshes: %d islands", (int)m_meshes.size());
}

// ── CPU mesh separation — uses production MeshSeparator (NO GL calls) ─────────
// Replaces old vertex-based Union-Find. Now face-based with sort adjacency.
void Renderer::separateMeshesCPU(const ModelData& md, std::vector<MeshObject>& out){
    out.clear();
    if(md.vertices.empty() || md.indices.empty()) return;

    const uint32_t triCount  = (uint32_t)(md.indices.size() / 3);
    const uint32_t vertCount = (uint32_t)md.vertices.size();

    // Reusable separator — buffers persist for lifetime of this Renderer instance
    std::vector<MeshComponent> components;
    m_separator.separate(
        md.vertices.data(), vertCount,
        md.indices.data(),  triCount,
        components);

    // Convert MeshComponent → MeshObject (add name, color; VAO/VBO filled later on GL thread)
    out.resize(components.size());
    for(size_t i = 0; i < components.size(); ++i){
        MeshObject& mo = out[i];
        mo.name    = "Mesh_" + std::to_string(i + 1);
        mo.colorR  = m_colorR; mo.colorG = m_colorG; mo.colorB = m_colorB;
        mo.vertices = std::move(components[i].vertices);
        // MeshObject uses unsigned int, MeshComponent uses uint32_t — same on all ABIs
        mo.indices.resize(components[i].indices.size());
        std::copy(components[i].indices.begin(), components[i].indices.end(), mo.indices.begin());
    }
    LOGI("separateMeshesCPU: %d islands from %u tris", (int)out.size(), triCount);
}

// ── Matrix helpers ────────────────────────────────────────────────────────────
Mat4 Renderer::buildGlobalMatrix() const {
    return Mat4::translation(m_posX,m_posY,m_posZ)
         * Mat4::rotationZ(m_rotZ*DEG2RAD)
         * Mat4::rotationY(m_rotY*DEG2RAD)
         * Mat4::rotationX(m_rotX*DEG2RAD)
         * Mat4::scale(m_scaX,m_scaY,m_scaZ);
}
Mat4 Renderer::buildMeshMatrix(const MeshObject& mo) const {
    return Mat4::translation(mo.posX,mo.posY,mo.posZ)
         * Mat4::rotationZ(mo.rotZ*DEG2RAD)
         * Mat4::rotationY(mo.rotY*DEG2RAD)
         * Mat4::rotationX(mo.rotX*DEG2RAD)
         * Mat4::scale(mo.scaX,mo.scaY,mo.scaZ);
}
Vec3 Renderer::cameraEye() const {
    float cp=cosf(m_camPitch),sp=sinf(m_camPitch);
    float cy=cosf(m_camYaw),sy=sinf(m_camYaw);
    return {m_panX+m_camDist*cp*sy, m_panY+m_camDist*sp, m_camDist*cp*cy};
}

// ── Draw ─────────────────────────────────────────────────────────────────────
void Renderer::draw(){
    updateFPS();
    glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
    if(m_meshes.empty()||!m_mainProg||!m_wireProg) return;

    float aspect=(float)m_width/std::max(m_height,1);
    Mat4 proj=Mat4::perspective(60.0f*DEG2RAD,aspect,0.01f,100.0f);
    Vec3 eye=cameraEye();
    Mat4 view=Mat4::lookAt(eye,{m_panX,m_panY,0},{0,1,0});
    Mat4 global=buildGlobalMatrix();
    // lightDir still passed for legacy uLightDir uniform (not used in new shader)
    Vec3 lightDir=Vec3{-0.5f, 1.0f, 0.8f}.normalized();

    for(auto& mo:m_meshes){
        if(!mo.visible||!mo.gpuReady) continue;
        Mat4 meshMat = buildMeshMatrix(mo);
        Mat4 model   = global * meshMat;
        Mat4 mvp     = proj*view*model;
        float nm[9]; model.toNormalMatrix(nm);

        glUseProgram(m_mainProg);
        // Use cached locations — no more slow glGetUniformLocation in hot loop
        glUniformMatrix4fv(m_uloc.mvp,      1, GL_FALSE, mvp.m);
        glUniformMatrix4fv(m_uloc.model,    1, GL_FALSE, model.m);
        glUniformMatrix3fv(m_uloc.norm,     1, GL_FALSE, nm);
        glUniform3f(m_uloc.color,    mo.colorR, mo.colorG, mo.colorB);
        glUniform3f(m_uloc.lightDir, lightDir.x, lightDir.y, lightDir.z);
        glUniform1f(m_uloc.ambient,  m_ambient);
        glUniform1f(m_uloc.diffuse,  m_diffuse);
        glUniform1i(m_uloc.selected, mo.selected ? 1 : 0);
        glUniform3f(m_uloc.camPos,   eye.x, eye.y, eye.z);

        GLsizei ic=(GLsizei)mo.indices.size();
        if(m_wireframe){
            glEnable(GL_POLYGON_OFFSET_FILL); glPolygonOffset(1,1);
            glUniform3f(m_uloc.color,0.05f,0.05f,0.08f);
            glBindVertexArray(mo.vao);
            glDrawElements(GL_TRIANGLES,ic,GL_UNSIGNED_INT,nullptr);
            glDisable(GL_POLYGON_OFFSET_FILL);

            glUseProgram(m_wireProg);
            glUniformMatrix4fv(m_uloc.wireMvp, 1, GL_FALSE, mvp.m);
            glUniform4f(m_uloc.wireColor,0.2f,0.85f,1.0f,1.0f);
            glUniform1f(m_uloc.wirePointSize,1.0f);
            glLineWidth(1.2f);
            glDrawElements(GL_LINES,ic,GL_UNSIGNED_INT,nullptr);
            glBindVertexArray(0);
        } else {
            glBindVertexArray(mo.vao);
            glDrawElements(GL_TRIANGLES,ic,GL_UNSIGNED_INT,nullptr);
            glBindVertexArray(0);
        }

        // Selected mesh bounding box overlay
        if(mo.selected && m_bbIndexCount>0){
            glUseProgram(m_wireProg);
            glUniformMatrix4fv(m_uloc.wireMvp, 1, GL_FALSE, mvp.m);
            glUniform4f(m_uloc.wireColor,0.2f,0.9f,1.0f,1.0f);
            glUniform1f(m_uloc.wirePointSize,1.0f);
            glLineWidth(2.0f);
            glBindVertexArray(m_bbVao);
            glDrawElements(GL_LINES,m_bbIndexCount,GL_UNSIGNED_SHORT,nullptr);
            glBindVertexArray(0);
        }
    }

    // Global bounding box
    if(m_showBBox && m_bbIndexCount>0){
        Mat4 mvp=proj*view*buildGlobalMatrix();
        glUseProgram(m_wireProg);
        glUniformMatrix4fv(m_uloc.wireMvp,1,GL_FALSE,mvp.m);
        glUniform4f(m_uloc.wireColor,1.0f,0.6f,0.1f,0.9f);
        glUniform1f(m_uloc.wirePointSize,1.0f);
        glLineWidth(1.5f);
        glBindVertexArray(m_bbVao);
        glDrawElements(GL_LINES,m_bbIndexCount,GL_UNSIGNED_SHORT,nullptr);
        glBindVertexArray(0);
    }

    // Ruler
    if(m_rulerHasP1||m_rulerHasP2){
        Mat4 vp=proj*view;
        glUseProgram(m_wireProg);
        glUniformMatrix4fv(m_uloc.wireMvp,1,GL_FALSE,vp.m);
        glDisable(GL_DEPTH_TEST);

        if(m_rulerHasP1&&m_rulerHasP2){
            float pts[6]={m_rulerP1[0],m_rulerP1[1],m_rulerP1[2],
                          m_rulerP2[0],m_rulerP2[1],m_rulerP2[2]};
            glBindBuffer(GL_ARRAY_BUFFER,m_rulerVbo);
            glBufferSubData(GL_ARRAY_BUFFER,0,sizeof(pts),pts);
            glBindVertexArray(m_rulerVao);
            glUniform4f(m_uloc.wireColor,1.0f,1.0f,0.0f,1.0f);
            glUniform1f(m_uloc.wirePointSize,1.0f);
            glLineWidth(2.5f); glDrawArrays(GL_LINES,0,2);
            glBindVertexArray(0);
        }

        float dotPts[6]={}; int dotCount=0;
        if(m_rulerHasP1){memcpy(dotPts+dotCount*3,m_rulerP1,12);dotCount++;}
        if(m_rulerHasP2){memcpy(dotPts+dotCount*3,m_rulerP2,12);dotCount++;}
        glBindBuffer(GL_ARRAY_BUFFER,m_rulerVbo);
        glBufferSubData(GL_ARRAY_BUFFER,0,dotCount*12,dotPts);
        glBindVertexArray(m_rulerVao);
        glUniform4f(m_uloc.wireColor,1.0f,0.3f,0.3f,1.0f);
        glUniform1f(m_uloc.wirePointSize,14.0f);
        glDrawArrays(GL_POINTS,0,dotCount);
        glBindVertexArray(0);
        glEnable(GL_DEPTH_TEST);
    }
}

// ── FPS ──────────────────────────────────────────────────────────────────────
void Renderer::updateFPS(){
    int64_t now=nowNs();
    if(++m_frameCount>=60){
        float e=(float)(now-m_fpsTimerNs)/1e9f;
        m_fps=(e>0)?m_frameCount/e:0;
        m_frameCount=0; m_fpsTimerNs=now;
    }
}

// ── Camera ───────────────────────────────────────────────────────────────────
void Renderer::touchRotate(float dx,float dy){
    // Positive dx = finger moves right = model rotates right (yaw increases)
    m_camYaw  -= dx * 0.006f;   // negate: drag right → rotate right around Y
    // + dy: drag UP (dy<0) → pitch decreases → camera lower → sees top of model (natural turntable)
    m_camPitch = std::clamp(m_camPitch + dy * 0.006f, -PI*0.48f, PI*0.48f);
}
void Renderer::touchZoom(float f){
    // f > 1.0 = pinch open = zoom in (distance decreases)
    // Clamp scaleFactor to avoid jumpy single-frame zoom
    float sf = f < 0.85f ? 0.85f : (f > 1.18f ? 1.18f : f);
    m_camDist = std::clamp(m_camDist / sf, 0.05f, 80.0f);
}
void Renderer::touchPan(float dx,float dy){
    // Screen dx/dy → world pan, scaled by camera distance so pan speed is
    // proportional to how far the camera is (feels consistent at all zoom levels)
    float s = m_camDist / (float)std::max(m_height, 1);
    m_panX -= dx * s;   // negate: drag right → view moves right → model appears to go right
    m_panY -= dy * s;   // Android Y grows DOWN, OpenGL Y grows UP → invert for correct pan
}
void Renderer::resetCamera(){ m_camYaw=0.4f;m_camPitch=0.3f;m_camDist=3.5f;m_panX=0;m_panY=0; }

// ── Transform ────────────────────────────────────────────────────────────────
void Renderer::setRotation(float x,float y,float z)    {m_rotX=x;m_rotY=y;m_rotZ=z;}
void Renderer::setTranslation(float x,float y,float z) {m_posX=x;m_posY=y;m_posZ=z;}
void Renderer::setScaleMM(float w,float h,float d){
    if(m_origWmm>1e-9f) m_scaX=w/m_origWmm;
    if(m_origHmm>1e-9f) m_scaY=h/m_origHmm;
    if(m_origDmm>1e-9f) m_scaZ=d/m_origDmm;
}
void Renderer::mirrorX(){m_scaX=-m_scaX;}
void Renderer::mirrorY(){m_scaY=-m_scaY;}
void Renderer::mirrorZ(){m_scaZ=-m_scaZ;}
void Renderer::resetTransform(){m_rotX=m_rotY=m_rotZ=0;m_posX=m_posY=m_posZ=0;m_scaX=m_scaY=m_scaZ=1;}
void Renderer::setColor(float r,float g,float b){
    m_colorR=r;m_colorG=g;m_colorB=b;
    for(auto& mo:m_meshes){ mo.colorR=r;mo.colorG=g;mo.colorB=b; }
}
void Renderer::setAmbient(float a){m_ambient=std::clamp(a,0.0f,1.0f);}
void Renderer::setDiffuse(float d){m_diffuse=std::clamp(d,0.0f,1.0f);}
void Renderer::setWireframe(bool on){m_wireframe=on;}
void Renderer::setShowBoundingBox(bool on){m_showBBox=on;}

// ── TWO-STEP LOAD ─────────────────────────────────────────────────────────────

// Step 1 — IO thread: parse file (tinyobj/stl/glb). NO GL calls. FAST.
bool Renderer::parseModel(const std::string& path){
    delete m_pendingData;
    m_pendingData = nullptr;

    ModelData* md = new ModelData();
    if(!ModelLoader::load(path, *md)){
        delete md;
        LOGE("parseModel failed: %s", path.c_str());
        return false;
    }
    m_pendingData = md;
    LOGI("parseModel OK — %zu verts, %zu idx | %.1fx%.1fx%.1f mm",
         md->vertices.size(), md->indices.size(),
         md->widthMM(), md->heightMM(), md->depthMM());
    return true;
}

// Step 2 — GL thread: upload as ONE single mesh. Instant. NO separation.
bool Renderer::uploadParsed(){
    if(!m_pendingData){
        LOGE("uploadParsed: no pending data");
        return false;
    }
    for(auto& mo:m_meshes){
        if(mo.vao) glDeleteVertexArrays(1,&mo.vao);
        if(mo.vbo) glDeleteBuffers(1,&mo.vbo);
        if(mo.ibo) glDeleteBuffers(1,&mo.ibo);
    }
    m_meshes.clear();

    m_origWmm        = m_pendingData->widthMM();
    m_origHmm        = m_pendingData->heightMM();
    m_origDmm        = m_pendingData->depthMM();
    m_normalizeScale = m_pendingData->normalizeScale;

    // Keep a copy for later separation (performSeparationCPU reads these on IO thread)
    m_rawVertices = m_pendingData->vertices;   // copy before move
    m_rawIndices  = m_pendingData->indices;    // copy before move

    MeshObject mo;
    mo.name     = "Model";
    mo.colorR   = m_colorR; mo.colorG = m_colorG; mo.colorB = m_colorB;
    mo.vertices = std::move(m_pendingData->vertices);
    mo.indices  = std::move(m_pendingData->indices);
    uploadMeshObject(mo);
    m_meshes.push_back(std::move(mo));

    delete m_pendingData;
    m_pendingData  = nullptr;
    m_hasModel     = true;
    m_isSeparated  = false;
    m_selectedMesh = -1;
    resetTransform(); resetCamera(); clearRuler();
    LOGI("uploadParsed OK — %zu verts, %zu tris, %.1fx%.1fx%.1f mm",
         m_rawVertices.size(), m_rawIndices.size()/3,
         m_origWmm, m_origHmm, m_origDmm);
    return true;
}

// Called from JNI after uploadParsed to hand raw data to bridge for safe separation
void Renderer::getRawData(std::vector<Vertex>& verts, std::vector<uint32_t>& idx) const {
    verts = m_rawVertices;
    idx   = m_rawIndices;
    LOGI("getRawData: %zu verts, %zu tris", verts.size(), idx.size()/3);
}

// GL thread: load pre-separated components from JNI bridge onto GPU
bool Renderer::loadSeparatedComponents(std::vector<MeshComponent>& comps){
    if(comps.empty()) return false;
    // Free old GPU objects
    for(auto& mo:m_meshes){
        if(mo.vao) glDeleteVertexArrays(1,&mo.vao);
        if(mo.vbo) glDeleteBuffers(1,&mo.vbo);
        if(mo.ibo) glDeleteBuffers(1,&mo.ibo);
    }
    m_meshes.clear();

    static const float kColors[][3] = {
        {0.0f,0.83f,1.0f},{1.0f,0.44f,0.26f},{0.30f,0.69f,0.51f},
        {1.0f,0.84f,0.31f},{0.67f,0.48f,0.74f},{0.93f,0.25f,0.48f},
        {0.15f,0.78f,0.85f},{0.83f,0.88f,0.34f}
    };
    const int NC = (int)(sizeof(kColors)/sizeof(kColors[0]));

    for(int i=0;i<(int)comps.size();++i){
        auto& comp = comps[i];
        if(comp.vertices.empty()) continue;
        MeshObject mo;
        mo.name   = "Mesh_" + std::to_string(i+1);
        mo.colorR = kColors[i%NC][0];
        mo.colorG = kColors[i%NC][1];
        mo.colorB = kColors[i%NC][2];
        mo.vertices = std::move(comp.vertices);
        mo.indices.resize(comp.indices.size());
        for(size_t k=0;k<comp.indices.size();++k)
            mo.indices[k] = (unsigned int)comp.indices[k];
        uploadMeshObject(mo);
        m_meshes.push_back(std::move(mo));
    }
    m_isSeparated  = true;
    m_selectedMesh = -1;
    // Free raw data — separation complete
    m_rawVertices.clear(); m_rawVertices.shrink_to_fit();
    m_rawIndices.clear();  m_rawIndices.shrink_to_fit();
    LOGI("loadSeparatedComponents: %d meshes on GPU", (int)m_meshes.size());
    return !m_meshes.empty();
}

// ── Load model ───────────────────────────────────────────────────────────────
bool Renderer::loadModel(const std::string& path){
    ModelData md;
    if(!ModelLoader::load(path,md)) return false;
    m_origWmm=md.widthMM(); m_origHmm=md.heightMM(); m_origDmm=md.depthMM();
    m_normalizeScale=md.normalizeScale;
    separateIntoMeshes(md);
    m_hasModel=!m_meshes.empty();
    m_selectedMesh=-1;
    resetTransform(); resetCamera(); clearRuler();
    return m_hasModel;
}

// ── Mesh management ───────────────────────────────────────────────────────────
void Renderer::getMeshName(int idx,char* buf,int bufLen) const {
    if(idx<0||idx>=(int)m_meshes.size()){snprintf(buf,bufLen,"?");return;}
    snprintf(buf,bufLen,"%s",m_meshes[idx].name.c_str());
}
void Renderer::selectMesh(int idx){
    m_selectedMesh=idx;
    for(int i=0;i<(int)m_meshes.size();++i) m_meshes[i].selected=(i==idx);
}
void Renderer::deleteMesh(int idx){
    if(idx<0||idx>=(int)m_meshes.size()) return;
    auto& mo=m_meshes[idx];
    if(mo.vao) glDeleteVertexArrays(1,&mo.vao);
    if(mo.vbo) glDeleteBuffers(1,&mo.vbo);
    if(mo.ibo) glDeleteBuffers(1,&mo.ibo);
    m_meshes.erase(m_meshes.begin()+idx);
    m_selectedMesh=-1;
    if(m_meshes.empty()) m_hasModel=false;
}
void Renderer::setMeshVisible(int idx,bool v){
    if(idx>=0&&idx<(int)m_meshes.size()) m_meshes[idx].visible=v;
}
bool Renderer::getMeshVisible(int idx) const {
    if(idx<0||idx>=(int)m_meshes.size()) return false;
    return m_meshes[idx].visible;
}
void Renderer::setMeshColor(int idx,float r,float g,float b){
    if(idx>=0&&idx<(int)m_meshes.size()){ m_meshes[idx].colorR=r;m_meshes[idx].colorG=g;m_meshes[idx].colorB=b; }
}
void Renderer::setMeshScaleMM(int idx,float w,float h,float d){
    if(idx<0||idx>=(int)m_meshes.size()) return;
    auto& mo=m_meshes[idx];
    // Compute mesh original size from its vertices
    float minX=FLT_MAX,minY=FLT_MAX,minZ=FLT_MAX;
    float maxX=-FLT_MAX,maxY=-FLT_MAX,maxZ=-FLT_MAX;
    for(auto& v:mo.vertices){
        minX=std::min(minX,v.px);maxX=std::max(maxX,v.px);
        minY=std::min(minY,v.py);maxY=std::max(maxY,v.py);
        minZ=std::min(minZ,v.pz);maxZ=std::max(maxZ,v.pz);
    }
    float sx=maxX-minX, sy=maxY-minY, sz=maxZ-minZ;
    // sx is in normalized units; convert to mm via normalizeScale
    float uToMM = (m_normalizeScale>1e-9f)? (1.0f/m_normalizeScale) : 1.0f;
    // origMeshMm = sx/normalizeScale * unitToMM... simplified:
    // desired world size = scaX * sx → desired world * uToMM/m_origWmm… 
    // Simpler: scaX = desired_mm / (sx * mmPerUnit)
    float mmPerUnit = (m_origWmm>1e-9f)?m_origWmm/2.0f:1.0f;
    if(sx>1e-9f) mo.scaX=w/(sx*mmPerUnit);
    if(sy>1e-9f) mo.scaY=h/(sy*mmPerUnit);
    if(sz>1e-9f) mo.scaZ=d/(sz*mmPerUnit);
}
void Renderer::getMeshSizeMM(int idx,float& w,float& h,float& d) const {
    if(idx<0||idx>=(int)m_meshes.size()){w=h=d=0;return;}
    const auto& mo=m_meshes[idx];
    float minX=FLT_MAX,minY=FLT_MAX,minZ=FLT_MAX;
    float maxX=-FLT_MAX,maxY=-FLT_MAX,maxZ=-FLT_MAX;
    for(auto& v:mo.vertices){
        minX=std::min(minX,v.px);maxX=std::max(maxX,v.px);
        minY=std::min(minY,v.py);maxY=std::max(maxY,v.py);
        minZ=std::min(minZ,v.pz);maxZ=std::max(maxZ,v.pz);
    }
    float mmPerUnit=(m_origWmm>1e-9f)?m_origWmm/2.0f:1.0f;
    w=(maxX-minX)*fabsf(mo.scaX)*mmPerUnit;
    h=(maxY-minY)*fabsf(mo.scaY)*mmPerUnit;
    d=(maxZ-minZ)*fabsf(mo.scaZ)*mmPerUnit;
}

// ── Size ─────────────────────────────────────────────────────────────────────
void Renderer::getModelSizeMM(float& w,float& h,float& d) const {w=m_origWmm;h=m_origHmm;d=m_origDmm;}
void Renderer::getCurrentSizeMM(float& w,float& h,float& d) const {
    w=fabsf(m_scaX)*m_origWmm; h=fabsf(m_scaY)*m_origHmm; d=fabsf(m_scaZ)*m_origDmm;
}

// ── Export helpers ────────────────────────────────────────────────────────────
// Transform a 3D point by a column-major 4x4 matrix (with perspective divide)
static Vec3 applyMat4Point(const Mat4& mat, float x, float y, float z) {
    return {
        mat.m[0]*x + mat.m[4]*y + mat.m[8]*z  + mat.m[12],
        mat.m[1]*x + mat.m[5]*y + mat.m[9]*z  + mat.m[13],
        mat.m[2]*x + mat.m[6]*y + mat.m[10]*z + mat.m[14]
    };
}
// Transform a normal using the upper-left 3x3 of the matrix, then renormalize
static Vec3 applyMat4Normal(const Mat4& mat, float nx, float ny, float nz) {
    Vec3 n{
        mat.m[0]*nx + mat.m[4]*ny + mat.m[8]*nz,
        mat.m[1]*nx + mat.m[5]*ny + mat.m[9]*nz,
        mat.m[2]*nx + mat.m[6]*ny + mat.m[10]*nz
    };
    float len = sqrtf(n.x*n.x + n.y*n.y + n.z*n.z);
    if(len > 1e-9f){ n.x /= len; n.y /= len; n.z /= len; }
    return n;
}

// ── Export ───────────────────────────────────────────────────────────────────
bool Renderer::exportOBJ(const std::string& path) const {
    std::ofstream f(path);
    if(!f) return false;
    f << "# Exported by 3D Model Viewer\n";
    f << std::fixed; f.precision(6);

    Mat4 global = buildGlobalMatrix();
    int baseVertex = 1;

    for(const auto& mo : m_meshes){
        if(!mo.visible) continue;
        f << "o " << mo.name << "\n";

        // Combined transform: global(userScale/rot/trans) * meshTransform * mmConversion
        // mmConversion converts from normalized [-1,1] coords back to real-world mm units.
        // This ensures exported files are correctly sized (1 OBJ unit = 1 mm).
        float toMM = (m_normalizeScale > 1e-9f) ? (1.0f / m_normalizeScale) : 1.0f;
        Mat4 mmConv = Mat4::scale(toMM, toMM, toMM);
        Mat4 model  = global * buildMeshMatrix(mo) * mmConv;

        for(const auto& v : mo.vertices){
            Vec3 p = applyMat4Point(model, v.px, v.py, v.pz);
            f << "v " << p.x << " " << p.y << " " << p.z << "\n";
        }
        for(const auto& v : mo.vertices){
            Vec3 n = applyMat4Normal(model, v.nx, v.ny, v.nz);
            f << "vn " << n.x << " " << n.y << " " << n.z << "\n";
        }
        for(size_t i = 0; i+2 < mo.indices.size(); i+=3){
            int a = (int)mo.indices[i+0] + baseVertex;
            int b = (int)mo.indices[i+1] + baseVertex;
            int c = (int)mo.indices[i+2] + baseVertex;
            f << "f " << a << "//" << a << " " << b << "//" << b << " " << c << "//" << c << "\n";
        }
        baseVertex += (int)mo.vertices.size();
    }
    return true;
}

bool Renderer::exportSTL(const std::string& path) const {
    std::ofstream f(path, std::ios::binary);
    if(!f) return false;

    Mat4 global = buildGlobalMatrix();

    // Count total visible triangles
    uint32_t total = 0;
    for(const auto& mo : m_meshes) if(mo.visible) total += (uint32_t)(mo.indices.size()/3);

    char header[80] = "3D Model Viewer Export";
    f.write(header, 80);
    f.write(reinterpret_cast<const char*>(&total), 4);

    for(const auto& mo : m_meshes){
        if(!mo.visible) continue;
        float toMM = (m_normalizeScale > 1e-9f) ? (1.0f / m_normalizeScale) : 1.0f;
        Mat4 mmConv = Mat4::scale(toMM, toMM, toMM);
        Mat4 model  = global * buildMeshMatrix(mo) * mmConv;

        for(size_t i = 0; i+2 < mo.indices.size(); i+=3){
            const auto& v0 = mo.vertices[mo.indices[i+0]];
            const auto& v1 = mo.vertices[mo.indices[i+1]];
            const auto& v2 = mo.vertices[mo.indices[i+2]];

            // Transform positions by the full model matrix
            Vec3 p0 = applyMat4Point(model, v0.px, v0.py, v0.pz);
            Vec3 p1 = applyMat4Point(model, v1.px, v1.py, v1.pz);
            Vec3 p2 = applyMat4Point(model, v2.px, v2.py, v2.pz);

            // Recompute face normal from transformed positions
            Vec3 e1{p1.x-p0.x, p1.y-p0.y, p1.z-p0.z};
            Vec3 e2{p2.x-p0.x, p2.y-p0.y, p2.z-p0.z};
            Vec3 n = e1.cross(e2).normalized();

            float nf[3]  = {n.x,  n.y,  n.z};
            float pf0[3] = {p0.x, p0.y, p0.z};
            float pf1[3] = {p1.x, p1.y, p1.z};
            float pf2[3] = {p2.x, p2.y, p2.z};
            uint16_t att = 0;
            f.write(reinterpret_cast<const char*>(nf),  12);
            f.write(reinterpret_cast<const char*>(pf0), 12);
            f.write(reinterpret_cast<const char*>(pf1), 12);
            f.write(reinterpret_cast<const char*>(pf2), 12);
            f.write(reinterpret_cast<const char*>(&att), 2);
        }
    }
    return true;
}


// ══════════════════════════════════════════════════════════════════════════════
// RING DEFORMATION ENGINE
// ══════════════════════════════════════════════════════════════════════════════

// ── Jacobi 3×3 symmetric eigenvalue decomposition ────────────────────────────
// On exit: eigenvalues in A[0][0], A[1][1], A[2][2] (ascending)
//          eigenvectors in columns of V
static void jacobiEigen3(float A[3][3], float V[3][3]) {
    // Identity for V
    for(int i=0;i<3;i++) for(int j=0;j<3;j++) V[i][j]=(i==j)?1.f:0.f;

    for(int iter=0;iter<64;iter++){
        // Find largest off-diagonal |A[p][q]|
        int p=0,q=1;
        float maxOff=fabsf(A[0][1]);
        if(fabsf(A[0][2])>maxOff){maxOff=fabsf(A[0][2]);p=0;q=2;}
        if(fabsf(A[1][2])>maxOff){maxOff=fabsf(A[1][2]);p=1;q=2;}
        if(maxOff<1e-12f) break;

        // Compute Givens angle
        float denom = A[q][q]-A[p][p];
        float theta = (fabsf(denom)<1e-30f) ? (float)M_PI/4.f
                      : 0.5f*atanf(2.f*A[p][q]/denom);
        float c=cosf(theta), s=sinf(theta);

        // Update symmetric matrix A ← G^T A G
        float newApp = c*c*A[p][p] + 2.f*s*c*A[p][q] + s*s*A[q][q];
        float newAqq = s*s*A[p][p] - 2.f*s*c*A[p][q] + c*c*A[q][q];
        float newApq = 0.f;

        for(int k=0;k<3;k++){
            if(k==p||k==q) continue;
            float Akp =  c*A[k][p] + s*A[k][q];
            float Akq = -s*A[k][p] + c*A[k][q];
            A[k][p]=A[p][k]=Akp; A[k][q]=A[q][k]=Akq;
        }
        A[p][p]=newApp; A[q][q]=newAqq; A[p][q]=A[q][p]=newApq;

        // Update eigenvector matrix V ← V G
        for(int k=0;k<3;k++){
            float Vkp =  c*V[k][p] + s*V[k][q];
            float Vkq = -s*V[k][p] + c*V[k][q];
            V[k][p]=Vkp; V[k][q]=Vkq;
        }
    }

    // Sort eigenpairs ascending by eigenvalue (bubble-sort 3 elements)
    float ev[3]={A[0][0],A[1][1],A[2][2]};
    int   idx[3]={0,1,2};
    for(int i=0;i<2;i++) for(int j=i+1;j<3;j++)
        if(ev[idx[i]]>ev[idx[j]]) { int t=idx[i];idx[i]=idx[j];idx[j]=t; }

    // Reorder V columns
    float Vs[3][3];
    for(int i=0;i<3;i++) for(int j=0;j<3;j++) Vs[j][i]=V[j][idx[i]];
    for(int i=0;i<3;i++) for(int j=0;j<3;j++) V[i][j]=Vs[i][j];
}

// ══════════════════════════════════════════════════════════════════════════════
// RING DEFORMATION ENGINE  v2 — Correct additive displacement
// ══════════════════════════════════════════════════════════════════════════════
//
// ROOT CAUSE OF V1 BUG:
//   Old code: sc = rNew/r;  v.px = center + along*axis + rad.x * sc
//   This SCALES the full radial-plane vector by sc, which IS the right direction
//   but operates on already-modified vertices on every slider move → cumulative
//   distortion accumulates and destroys the geometry.
//
// V2 FIXES:
//   1. Always deform from origVerts (no cumulative errors ever)
//   2. Additive displacement: r_new = r + delta * smoothWeight(r)
//      Vertex moves along its own radial direction by delta*weight — never scaled
//   3. Percentile-based inner/outer radius (robust to carved/complex geometry)
//   4. Axis detection from inner-surface verts only (stone settings can't break it)
//   5. Smoothstep weight prevents hard edges at inner/outer boundaries
//   6. Area-weighted smooth normals after deformation
// ══════════════════════════════════════════════════════════════════════════════

// ── smoothstep ───────────────────────────────────────────────────────────────
static inline float smoothstep3(float edge0, float edge1, float x) {
    float t = std::clamp((x - edge0) / (edge1 - edge0 + 1e-12f), 0.f, 1.f);
    return t * t * (3.f - 2.f * t);   // 3t²−2t³
}

// ── Area-weighted smooth normals ─────────────────────────────────────────────
// Each face contributes its normal weighted by face area to its 3 vertices.
// Equivalent to accumulating unnormalised cross-products — already what
// the old code did — but we now also handle degenerate faces gracefully.
void Renderer::regenerateNormals(MeshObject& mo) {
    for (auto& v : mo.vertices) { v.nx = v.ny = v.nz = 0.f; }
    const size_t triN = mo.indices.size();
    for (size_t i = 0; i + 2 < triN; i += 3) {
        const auto& v0 = mo.vertices[mo.indices[i+0]];
        const auto& v1 = mo.vertices[mo.indices[i+1]];
        const auto& v2 = mo.vertices[mo.indices[i+2]];
        Vec3 a{v1.px-v0.px, v1.py-v0.py, v1.pz-v0.pz};
        Vec3 b{v2.px-v0.px, v2.py-v0.py, v2.pz-v0.pz};
        Vec3 n = a.cross(b);   // magnitude ∝ face area — no div, just accumulate
        for (int k = 0; k < 3; ++k) {
            auto& vk = mo.vertices[mo.indices[i+k]];
            vk.nx += n.x; vk.ny += n.y; vk.nz += n.z;
        }
    }
    for (auto& v : mo.vertices) {
        float L = sqrtf(v.nx*v.nx + v.ny*v.ny + v.nz*v.nz);
        if (L > 1e-12f) { v.nx /= L; v.ny /= L; v.nz /= L; }
        else             { v.nx = 0.f; v.ny = 1.f; v.nz = 0.f; }
    }
}

// ── Update GPU positions + normals (positions + normals only, uvs unchanged) ─
void Renderer::updateMeshVBO(MeshObject& mo) {
    if (!mo.vbo || mo.vertices.empty()) return;
    glBindBuffer(GL_ARRAY_BUFFER, mo.vbo);
    // Full vertex buffer update — struct is interleaved (pos+norm+uv)
    glBufferData(GL_ARRAY_BUFFER,
                 (GLsizeiptr)(mo.vertices.size() * sizeof(Vertex)),
                 mo.vertices.data(), GL_DYNAMIC_DRAW);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
}

// ── Core radial deformation ───────────────────────────────────────────────────
// Applies additive radial displacement from origVerts.
// weight_outer  = smoothstep(innerR→outerR) — moves outer surface
// weight_inner  = 1 - smoothstep(innerR→outerR) — moves inner surface
//
// Formulae:
//   Band width:      r_new = r_orig + smoothstep(innerR,outerR, r_orig) * deltaOuter
//   Inner diameter:  r_new = r_orig + (1-smoothstep(innerR,outerR, r_orig)) * deltaInner
//
// deltaOuter = newOuterR - origOuterR  (positive → expand outer wall)
// deltaInner = newInnerR - origInnerR  (positive → expand hole)
//
// Both can be combined in one pass:
//   r_new = r_orig
//         + smoothstep_t      * deltaOuter
//         + (1-smoothstep_t) * deltaInner
void Renderer::applyRingDeformation(float newInnerN, float newOuterN) {
    if (!m_ring.valid || m_ring.meshIdx < 0 ||
        m_ring.meshIdx >= (int)m_meshes.size()) return;
    if (m_ring.origVerts.empty()) return;

    auto& mo = m_meshes[m_ring.meshIdx];

    // Safety: never let inner >= outer
    newInnerN = std::min(newInnerN, newOuterN - 1e-5f);
    newOuterN = std::max(newOuterN, newInnerN + 1e-5f);

    const float origInner = m_ring.origInnerR;
    const float origOuter = m_ring.origOuterR;
    const float deltaOuter = newOuterN - origOuter;   // change in outer radius
    const float deltaInner = newInnerN - origInner;   // change in inner radius

    // Always work from the backup — zero cumulative error
    mo.vertices = m_ring.origVerts;

    const size_t N = mo.vertices.size();
    const Vec3&  cen  = m_ring.center;
    const Vec3&  axis = m_ring.axis;

    for (size_t i = 0; i < N; ++i) {
        auto& v = mo.vertices[i];

        // Decompose vertex position into axial + radial components
        Vec3 d { v.px - cen.x, v.py - cen.y, v.pz - cen.z };
        float along = d.x*axis.x + d.y*axis.y + d.z*axis.z;
        // radial vector (perpendicular to axis, toward vertex)
        float rx = d.x - along*axis.x;
        float ry = d.y - along*axis.y;
        float rz = d.z - along*axis.z;
        float r  = sqrtf(rx*rx + ry*ry + rz*rz);
        if (r < 1e-12f) continue;  // vertex exactly on axis — skip

        // Smooth weight: 0 at inner surface, 1 at outer surface
        float t = smoothstep3(origInner, origOuter, r);

        // Additive radial displacement: outer weight moves outer, inner weight moves inner
        float displacement = t * deltaOuter + (1.f - t) * deltaInner;

        // Move vertex along its own radial direction by displacement
        float rNew  = r + displacement;
        float scale = rNew / r;   // scale the radial vector only (not axial)

        v.px = cen.x + along*axis.x + rx*scale;
        v.py = cen.y + along*axis.y + ry*scale;
        v.pz = cen.z + along*axis.z + rz*scale;
    }

    // Update live tracking
    m_ring.currentInnerR = newInnerN;
    m_ring.currentOuterR = newOuterN;

    regenerateNormals(mo);
    updateMeshVBO(mo);
}

// ── Ring Analysis v2 ─────────────────────────────────────────────────────────
// Robust inner/outer detection using percentiles — immune to stone settings,
// carvings, and asymmetric geometry that confuses simple k-means.
bool Renderer::analyzeRing(int meshIdx) {
    if (meshIdx < 0 || meshIdx >= (int)m_meshes.size()) return false;
    const auto& mo = m_meshes[meshIdx];
    const size_t N  = mo.vertices.size();
    if (N < 64) return false;

    // ── Step 1: Coarse centroid and covariance (all vertices) ─────────────────
    Vec3 cen{0,0,0};
    for (const auto& v : mo.vertices) { cen.x+=v.px; cen.y+=v.py; cen.z+=v.pz; }
    float invN = 1.f / (float)N;
    cen.x*=invN; cen.y*=invN; cen.z*=invN;

    float C[3][3] = {};
    for (const auto& v : mo.vertices) {
        float d[3] = {v.px-cen.x, v.py-cen.y, v.pz-cen.z};
        for (int i=0;i<3;i++) for (int j=0;j<3;j++) C[i][j] += d[i]*d[j];
    }
    float V[3][3]; jacobiEigen3(C, V);
    // Smallest eigenvalue → ring axis (least variance direction = through hole)
    Vec3 axis{V[0][0], V[1][0], V[2][0]};
    float alen = sqrtf(axis.x*axis.x + axis.y*axis.y + axis.z*axis.z);
    if (alen < 1e-9f) return false;
    axis.x/=alen; axis.y/=alen; axis.z/=alen;

    // ── Step 2: Compute all radial distances and axial extent ─────────────────
    std::vector<float> radii(N);
    float axMin = FLT_MAX, axMax = -FLT_MAX;
    for (size_t i = 0; i < N; ++i) {
        const auto& v = mo.vertices[i];
        Vec3 d{v.px-cen.x, v.py-cen.y, v.pz-cen.z};
        float along = d.x*axis.x + d.y*axis.y + d.z*axis.z;
        axMin = std::min(axMin, along); axMax = std::max(axMax, along);
        float rx = d.x - along*axis.x;
        float ry = d.y - along*axis.y;
        float rz = d.z - along*axis.z;
        radii[i] = sqrtf(rx*rx + ry*ry + rz*rz);
    }

    // ── Step 3: Percentile-based robust inner/outer radius ────────────────────
    // Using percentiles avoids k-means failure on asymmetric/carved rings.
    // 5th percentile ≈ inner bore, 95th percentile ≈ outer surface peak.
    std::vector<float> sortedR = radii;
    std::sort(sortedR.begin(), sortedR.end());
    float innerR = sortedR[(size_t)(N * 0.05f)];
    float outerR = sortedR[(size_t)(N * 0.95f)];

    // Reject if not enough spread (probably not a ring)
    if (outerR - innerR < 1e-6f) return false;

    // ── Step 4: Refine axis using inner-surface vertices only ─────────────────
    // Stone settings and carvings only affect outer geometry.
    // The inner bore is a clean cylinder → gives the true ring axis.
    float innerBound = innerR * 1.3f;  // 30% above inner radius
    float refCovC[3][3] = {};
    Vec3  innerCen{0,0,0};
    int   nRefVerts = 0;
    for (size_t i = 0; i < N; ++i) {
        if (radii[i] <= innerBound) {
            const auto& v = mo.vertices[i];
            innerCen.x += v.px; innerCen.y += v.py; innerCen.z += v.pz;
            ++nRefVerts;
        }
    }
    if (nRefVerts > 32) {
        float invR = 1.f / (float)nRefVerts;
        innerCen.x*=invR; innerCen.y*=invR; innerCen.z*=invR;
        // Recompute covariance from inner verts only
        for (size_t i = 0; i < N; ++i) {
            if (radii[i] > innerBound) continue;
            const auto& v = mo.vertices[i];
            float d[3] = {v.px-innerCen.x, v.py-innerCen.y, v.pz-innerCen.z};
            for (int ii=0;ii<3;ii++) for (int jj=0;jj<3;jj++)
                refCovC[ii][jj] += d[ii]*d[jj];
        }
        float Vr[3][3]; jacobiEigen3(refCovC, Vr);
        Vec3 refAxis{Vr[0][0], Vr[1][0], Vr[2][0]};
        float ralen = sqrtf(refAxis.x*refAxis.x+refAxis.y*refAxis.y+refAxis.z*refAxis.z);
        if (ralen > 1e-9f) {
            refAxis.x/=ralen; refAxis.y/=ralen; refAxis.z/=ralen;
            // Only use refined axis if it's reasonably aligned with coarse one
            // (dot product > 0.7 → within 45°)
            float dot = fabsf(refAxis.x*axis.x + refAxis.y*axis.y + refAxis.z*axis.z);
            if (dot > 0.7f) {
                axis = refAxis;
                cen  = innerCen;   // use inner surface centroid — more stable
            }
        }
    }

    // Recompute radii with refined axis for final percentiles
    axMin = FLT_MAX; axMax = -FLT_MAX;
    for (size_t i = 0; i < N; ++i) {
        const auto& v = mo.vertices[i];
        Vec3 d{v.px-cen.x, v.py-cen.y, v.pz-cen.z};
        float along = d.x*axis.x + d.y*axis.y + d.z*axis.z;
        axMin = std::min(axMin, along); axMax = std::max(axMax, along);
        float rx = d.x - along*axis.x;
        float ry = d.y - along*axis.y;
        float rz = d.z - along*axis.z;
        radii[i] = sqrtf(rx*rx + ry*ry + rz*rz);
    }
    // Recalculate percentiles with refined axis
    std::copy(radii.begin(), radii.end(), sortedR.begin());
    std::sort(sortedR.begin(), sortedR.end());
    innerR = sortedR[(size_t)(N * 0.05f)];
    outerR = sortedR[(size_t)(N * 0.95f)];
    if (outerR - innerR < 1e-6f) return false;

    // ── Step 5: Store results ─────────────────────────────────────────────────
    m_ring.center      = cen;
    m_ring.axis        = axis;
    m_ring.innerR      = innerR;
    m_ring.outerR      = outerR;
    m_ring.origInnerR  = innerR;
    m_ring.origOuterR  = outerR;
    m_ring.currentInnerR = innerR;
    m_ring.currentOuterR = outerR;
    m_ring.heightAx    = axMax - axMin;
    m_ring.valid       = true;
    m_ring.meshIdx     = meshIdx;
    m_ring.origVerts   = mo.vertices;  // backup — deformation always starts here

    LOGI("Ring v2: innerR=%.4f outerR=%.4f h=%.4f axis=(%.3f,%.3f,%.3f) N=%zu",
         innerR, outerR, m_ring.heightAx,
         axis.x, axis.y, axis.z, N);
    return true;
}

// ── Get ring parameters in mm ─────────────────────────────────────────────────
bool Renderer::getRingParams(float out[6]) const {
    if (!m_ring.valid) return false;
    float toMM = (m_normalizeScale > 1e-9f) ? (1.f / m_normalizeScale) : 1.f;
    float innerMM = m_ring.currentInnerR * toMM;
    float outerMM = m_ring.currentOuterR * toMM;
    float bwMM    = (m_ring.currentOuterR - m_ring.currentInnerR) * toMM;
    out[0] = innerMM;     // inner radius
    out[1] = outerMM;     // outer radius
    out[2] = bwMM;        // band width
    out[3] = innerMM*2.f; // inner diameter
    out[4] = outerMM*2.f; // outer diameter
    out[5] = m_ring.heightAx * toMM; // height
    return true;
}

// ── Public API ────────────────────────────────────────────────────────────────
void Renderer::setRingBandWidth(float newWidthMM) {
    if (!m_ring.valid) return;
    float toNorm = (m_normalizeScale > 1e-9f) ? m_normalizeScale : 1.f;
    float newWidthN = newWidthMM * toNorm;
    if (newWidthN < 1e-6f) return;
    float newOuterN = m_ring.origInnerR + newWidthN;  // outer = inner + band
    applyRingDeformation(m_ring.origInnerR, newOuterN);
}

void Renderer::setRingInnerDiameter(float newDiamMM) {
    if (!m_ring.valid) return;
    float toNorm = (m_normalizeScale > 1e-9f) ? m_normalizeScale : 1.f;
    float newInnerN = (newDiamMM * 0.5f) * toNorm;
    if (newInnerN < 1e-6f) return;
    applyRingDeformation(newInnerN, m_ring.origOuterR);
}

void Renderer::resetRingDeformation() {
    if (!m_ring.valid || m_ring.meshIdx < 0 ||
        m_ring.meshIdx >= (int)m_meshes.size()) return;
    if (m_ring.origVerts.empty()) return;
    auto& mo = m_meshes[m_ring.meshIdx];
    mo.vertices = m_ring.origVerts;
    m_ring.currentInnerR = m_ring.origInnerR;
    m_ring.currentOuterR = m_ring.origOuterR;
    regenerateNormals(mo);
    updateMeshVBO(mo);
}


// ── Raycasting ───────────────────────────────────────────────────────────────
Renderer::Ray Renderer::screenToRay(float sx,float sy,float sw,float sh) const {
    float ndcX=(2.0f*sx/sw)-1.0f;
    float ndcY=1.0f-(2.0f*sy/sh);
    float aspect=sw/std::max(sh,1.0f);
    float tanH=tanf(60.0f*DEG2RAD*0.5f);
    Vec3 eye=cameraEye();
    Vec3 center{m_panX,m_panY,0};
    Vec3 fwd=(center-eye).normalized();
    Vec3 right=fwd.cross({0,1,0}).normalized();
    Vec3 up=right.cross(fwd).normalized();
    Vec3 dir=(fwd+right*(ndcX*aspect*tanH)+up*(ndcY*tanH)).normalized();
    return {eye,dir};
}
bool Renderer::rayTriangle(const Ray& ray,const Vec3& v0,const Vec3& v1,const Vec3& v2,float& t) const {
    const float EPS=1e-7f;
    Vec3 e1=v1-v0,e2=v2-v0,h=ray.dir.cross(e2);
    float a=e1.dot(h); if(fabsf(a)<EPS) return false;
    float f=1.0f/a; Vec3 s=ray.origin-v0;
    float u=f*s.dot(h); if(u<0||u>1) return false;
    Vec3 q=s.cross(e1); float v=f*ray.dir.dot(q);
    if(v<0||u+v>1) return false;
    t=f*e2.dot(q); return t>EPS;
}
bool Renderer::pickPoint(float sx,float sy,float sw,float sh,float out[3]){
    if(m_meshes.empty()) return false;
    Ray ray=screenToRay(sx,sy,sw,sh);
    Mat4 global=buildGlobalMatrix();
    float bestT=FLT_MAX; bool hit=false;
    for(const auto& mo:m_meshes){
        if(!mo.visible) continue;
        Mat4 model=global*buildMeshMatrix(mo);
        auto tfm=[&](const Vertex& v)->Vec3{
            return {model.m[0]*v.px+model.m[4]*v.py+model.m[8]*v.pz+model.m[12],
                    model.m[1]*v.px+model.m[5]*v.py+model.m[9]*v.pz+model.m[13],
                    model.m[2]*v.px+model.m[6]*v.py+model.m[10]*v.pz+model.m[14]};
        };
        for(size_t i=0;i+2<mo.indices.size();i+=3){
            float t;
            if(rayTriangle(ray,tfm(mo.vertices[mo.indices[i]]),
                              tfm(mo.vertices[mo.indices[i+1]]),
                              tfm(mo.vertices[mo.indices[i+2]]),t)){
                if(t<bestT){bestT=t;hit=true;}
            }
        }
    }
    if(hit){out[0]=ray.origin.x+ray.dir.x*bestT;out[1]=ray.origin.y+ray.dir.y*bestT;out[2]=ray.origin.z+ray.dir.z*bestT;}
    return hit;
}

// ── Ruler ─────────────────────────────────────────────────────────────────────
void Renderer::setRulerPoints(bool h1,float* p1,bool h2,float* p2){
    m_rulerHasP1=h1; m_rulerHasP2=h2;
    if(h1&&p1) memcpy(m_rulerP1,p1,12);
    if(h2&&p2) memcpy(m_rulerP2,p2,12);
}
void Renderer::clearRuler(){m_rulerHasP1=m_rulerHasP2=false;}

// ── Undo/Redo ────────────────────────────────────────────────────────────────
void Renderer::pushUndoState(){
    m_undoStack.push_back(getTransform());
    if((int)m_undoStack.size()>MAX_UNDO) m_undoStack.erase(m_undoStack.begin());
    m_redoStack.clear();
}
void Renderer::undo(){
    if(m_undoStack.empty()) return;
    m_redoStack.push_back(getTransform());
    auto s=m_undoStack.back(); m_undoStack.pop_back();
    m_rotX=s.rotX;m_rotY=s.rotY;m_rotZ=s.rotZ;
    m_posX=s.posX;m_posY=s.posY;m_posZ=s.posZ;
    m_scaX=s.scaX;m_scaY=s.scaY;m_scaZ=s.scaZ;
}
void Renderer::redo(){
    if(m_redoStack.empty()) return;
    m_undoStack.push_back(getTransform());
    auto s=m_redoStack.back(); m_redoStack.pop_back();
    m_rotX=s.rotX;m_rotY=s.rotY;m_rotZ=s.rotZ;
    m_posX=s.posX;m_posY=s.posY;m_posZ=s.posZ;
    m_scaX=s.scaX;m_scaY=s.scaY;m_scaZ=s.scaZ;
}
TransformState Renderer::getTransform() const{
    return{m_rotX,m_rotY,m_rotZ,m_posX,m_posY,m_posZ,m_scaX,m_scaY,m_scaZ};
}

// ── Screenshot ───────────────────────────────────────────────────────────────
std::vector<uint8_t> Renderer::takeScreenshot(){
    std::vector<uint8_t> p((size_t)m_width*m_height*4);
    glReadPixels(0,0,m_width,m_height,GL_RGBA,GL_UNSIGNED_BYTE,p.data());
    int rb=m_width*4; std::vector<uint8_t> row((size_t)rb);
    for(int y=0;y<m_height/2;++y){
        uint8_t* top=p.data()+y*rb,*bot=p.data()+(m_height-1-y)*rb;
        memcpy(row.data(),top,rb); memcpy(top,bot,rb); memcpy(bot,row.data(),rb);
    }
    return p;
}

int Renderer::getMeshVertexCount(int idx) const {
    if (idx < 0 || idx >= (int)m_meshes.size()) return 0;
    return (int)m_meshes[idx].vertices.size();
}
