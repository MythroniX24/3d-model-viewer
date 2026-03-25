#include "renderer.h"
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
#include <unordered_map>

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
precision mediump float;
in vec3 vFragPos, vNormal;
out vec4 fragColor;
uniform vec3  uColor, uLightDir;
uniform float uAmbient, uDiffuse;
uniform int   uSelected;
void main(){
    vec3 n   = normalize(vNormal);
    float d  = max(dot(n,-uLightDir),0.0);
    vec3 vd  = normalize(vec3(0.0,0.0,3.5)-vFragPos);
    vec3 rd  = reflect(uLightDir,n);
    float sp = pow(max(dot(vd,rd),0.0),32.0)*0.25;
    vec3 c   = (uAmbient + uDiffuse*d)*uColor + sp;
    if(uSelected==1) c = mix(c, vec3(0.2,0.8,1.0), 0.35);
    fragColor = vec4(clamp(c,0.0,1.0),1.0);
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

// ── Init ─────────────────────────────────────────────────────────────────────
bool Renderer::init(int w,int h){
    m_width=w; m_height=h;
    glViewport(0,0,w,h);
    glClearColor(0.09f,0.09f,0.12f,1.0f);
    glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LEQUAL);
    glEnable(GL_CULL_FACE);  glCullFace(GL_BACK);

    buildShaders();

    // Safely recreate bounding-box GPU objects (delete stale handles first)
    if(m_bbVao)    { glDeleteVertexArrays(1,&m_bbVao);   m_bbVao=0; }
    if(m_bbVbo)    { glDeleteBuffers(1,&m_bbVbo);         m_bbVbo=0; }
    if(m_bbIbo)    { glDeleteBuffers(1,&m_bbIbo);         m_bbIbo=0; }
    if(m_rulerVao) { glDeleteVertexArrays(1,&m_rulerVao); m_rulerVao=0; }
    if(m_rulerVbo) { glDeleteBuffers(1,&m_rulerVbo);      m_rulerVbo=0; }

    glGenVertexArrays(1,&m_bbVao); glGenBuffers(1,&m_bbVbo); glGenBuffers(1,&m_bbIbo);
    buildBoundingBox();

    glGenVertexArrays(1,&m_rulerVao); glGenBuffers(1,&m_rulerVbo);
    glBindVertexArray(m_rulerVao);
    glBindBuffer(GL_ARRAY_BUFFER,m_rulerVbo);
    float dummy[6]={};
    glBufferData(GL_ARRAY_BUFFER,sizeof(dummy),dummy,GL_DYNAMIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0,3,GL_FLOAT,GL_FALSE,12,nullptr);
    glBindVertexArray(0);

    // Re-upload any existing meshes (GL context was recreated — old handles invalid)
    // Vertex data is still in RAM inside each MeshObject, so we can re-upload cheaply.
    reuploadAllMeshes();

    m_fpsTimerNs=nowNs();
    return !checkGLError("init");
}

void Renderer::resize(int w,int h){ m_width=w; m_height=h; glViewport(0,0,w,h); }

void Renderer::buildShaders(){
    if(m_mainProg) glDeleteProgram(m_mainProg);
    if(m_wireProg) glDeleteProgram(m_wireProg);
    m_mainProg=createProgram(kVertPhong,kFragPhong);
    m_wireProg=createProgram(kVertSimple,kFragSimple);
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
    // Always generate fresh handles (stale handles must be cleared before calling)
    glGenVertexArrays(1,&mo.vao); glGenBuffers(1,&mo.vbo); glGenBuffers(1,&mo.ibo);
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

// Re-upload all m_meshes (called after GL context recreation)
void Renderer::reuploadAllMeshes(){
    for(auto& mo:m_meshes){
        if(mo.vertices.empty()) continue;
        // Clear stale handles before re-uploading
        mo.vao=0; mo.vbo=0; mo.ibo=0; mo.gpuReady=false;
        uploadMeshObject(mo);
    }
    if(!m_meshes.empty())
        LOGI("reuploadAllMeshes: re-uploaded %d mesh(es) after context recreation", (int)m_meshes.size());
}

// ── separateMeshesCPU ─────────────────────────────────────────────────────────
// Pure CPU work — NO OpenGL calls — safe to call from IO thread
void Renderer::separateMeshesCPU(const ModelData& md,
                                   std::vector<MeshObject>& out,
                                   float cr, float cg, float cb){
    out.clear();
    size_t n = md.vertices.size();
    size_t triCount = md.indices.size()/3;
    if(n==0||triCount==0) return;

    // Union-Find on vertices
    std::vector<size_t> parent(n);
    std::iota(parent.begin(),parent.end(),0);

    // Iterative path-compressed find (avoids std::function overhead)
    auto find=[&](size_t x) -> size_t {
        while(parent[x]!=x){ parent[x]=parent[parent[x]]; x=parent[x]; }
        return x;
    };
    auto unite=[&](size_t a,size_t b){
        a=find(a); b=find(b); if(a!=b) parent[a]=b;
    };

    for(size_t t=0;t<triCount;++t){
        size_t i0=md.indices[t*3+0], i1=md.indices[t*3+1], i2=md.indices[t*3+2];
        unite(i0,i1); unite(i1,i2);
    }

    // Group triangles by root component
    std::unordered_map<size_t,std::vector<size_t>> groups;
    groups.reserve(64);
    for(size_t t=0;t<triCount;++t){
        groups[find(md.indices[t*3])].push_back(t);
    }

    // Build one MeshObject per component (NO GPU calls)
    int gi=0;
    out.reserve(groups.size());
    for(auto& [root,tris]:groups){
        MeshObject mo;
        mo.name   = "Mesh_" + std::to_string(++gi);
        mo.colorR = cr; mo.colorG = cg; mo.colorB = cb;
        // vao/vbo/ibo stay 0 — uploadMeshObject called later on GL thread

        std::unordered_map<unsigned int,unsigned int> remap;
        remap.reserve(tris.size()*3);
        for(size_t t:tris){
            for(int k=0;k<3;++k){
                unsigned int vi=md.indices[t*3+k];
                auto [it,inserted]=remap.emplace(vi,(unsigned int)mo.vertices.size());
                if(inserted) mo.vertices.push_back(md.vertices[vi]);
                mo.indices.push_back(it->second);
            }
        }
        out.push_back(std::move(mo));
    }
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
    Vec3 lightDir=Vec3{-0.4f,-1.0f,-0.3f}.normalized();

    for(auto& mo:m_meshes){
        if(!mo.visible||!mo.gpuReady) continue;
        Mat4 meshMat = buildMeshMatrix(mo);
        Mat4 model   = global * meshMat;
        Mat4 mvp     = proj*view*model;
        float nm[9]; model.toNormalMatrix(nm);

        glUseProgram(m_mainProg);
        glUniformMatrix4fv(glGetUniformLocation(m_mainProg,"uMVP"),  1,GL_FALSE,mvp.m);
        glUniformMatrix4fv(glGetUniformLocation(m_mainProg,"uModel"),1,GL_FALSE,model.m);
        glUniformMatrix3fv(glGetUniformLocation(m_mainProg,"uNorm"), 1,GL_FALSE,nm);
        glUniform3f(glGetUniformLocation(m_mainProg,"uColor"),    mo.colorR,mo.colorG,mo.colorB);
        glUniform3f(glGetUniformLocation(m_mainProg,"uLightDir"), lightDir.x,lightDir.y,lightDir.z);
        glUniform1f(glGetUniformLocation(m_mainProg,"uAmbient"),  m_ambient);
        glUniform1f(glGetUniformLocation(m_mainProg,"uDiffuse"),  m_diffuse);
        glUniform1i(glGetUniformLocation(m_mainProg,"uSelected"), mo.selected?1:0);

        GLsizei ic=(GLsizei)mo.indices.size();
        if(m_wireframe){
            glEnable(GL_POLYGON_OFFSET_FILL); glPolygonOffset(1,1);
            glUniform3f(glGetUniformLocation(m_mainProg,"uColor"),0.05f,0.05f,0.08f);
            glBindVertexArray(mo.vao);
            glDrawElements(GL_TRIANGLES,ic,GL_UNSIGNED_INT,nullptr);
            glDisable(GL_POLYGON_OFFSET_FILL);

            glUseProgram(m_wireProg);
            glUniformMatrix4fv(glGetUniformLocation(m_wireProg,"uMVP"),1,GL_FALSE,mvp.m);
            glUniform4f(glGetUniformLocation(m_wireProg,"uColor"),0.2f,0.85f,1.0f,1.0f);
            glUniform1f(glGetUniformLocation(m_wireProg,"uPointSize"),1.0f);
            glLineWidth(1.2f);
            glDrawElements(GL_LINES,ic,GL_UNSIGNED_INT,nullptr);
            glBindVertexArray(0);
        } else {
            glBindVertexArray(mo.vao);
            glDrawElements(GL_TRIANGLES,ic,GL_UNSIGNED_INT,nullptr);
            glBindVertexArray(0);
        }

        if(mo.selected && m_bbIndexCount>0){
            glUseProgram(m_wireProg);
            glUniformMatrix4fv(glGetUniformLocation(m_wireProg,"uMVP"),1,GL_FALSE,mvp.m);
            glUniform4f(glGetUniformLocation(m_wireProg,"uColor"),0.2f,0.9f,1.0f,1.0f);
            glUniform1f(glGetUniformLocation(m_wireProg,"uPointSize"),1.0f);
            glLineWidth(2.0f);
            glBindVertexArray(m_bbVao);
            glDrawElements(GL_LINES,m_bbIndexCount,GL_UNSIGNED_SHORT,nullptr);
            glBindVertexArray(0);
        }
    }

    if(m_showBBox && m_bbIndexCount>0){
        Mat4 mvp=proj*view*buildGlobalMatrix();
        glUseProgram(m_wireProg);
        glUniformMatrix4fv(glGetUniformLocation(m_wireProg,"uMVP"),1,GL_FALSE,mvp.m);
        glUniform4f(glGetUniformLocation(m_wireProg,"uColor"),1.0f,0.6f,0.1f,0.9f);
        glUniform1f(glGetUniformLocation(m_wireProg,"uPointSize"),1.0f);
        glLineWidth(1.5f);
        glBindVertexArray(m_bbVao);
        glDrawElements(GL_LINES,m_bbIndexCount,GL_UNSIGNED_SHORT,nullptr);
        glBindVertexArray(0);
    }

    if(m_rulerHasP1||m_rulerHasP2){
        Mat4 vp=proj*view;
        glUseProgram(m_wireProg);
        glUniformMatrix4fv(glGetUniformLocation(m_wireProg,"uMVP"),1,GL_FALSE,vp.m);
        glDisable(GL_DEPTH_TEST);

        if(m_rulerHasP1&&m_rulerHasP2){
            float pts[6]={m_rulerP1[0],m_rulerP1[1],m_rulerP1[2],
                          m_rulerP2[0],m_rulerP2[1],m_rulerP2[2]};
            glBindBuffer(GL_ARRAY_BUFFER,m_rulerVbo);
            glBufferSubData(GL_ARRAY_BUFFER,0,sizeof(pts),pts);
            glBindVertexArray(m_rulerVao);
            glUniform4f(glGetUniformLocation(m_wireProg,"uColor"),1.0f,1.0f,0.0f,1.0f);
            glUniform1f(glGetUniformLocation(m_wireProg,"uPointSize"),1.0f);
            glLineWidth(2.5f); glDrawArrays(GL_LINES,0,2);
            glBindVertexArray(0);
        }

        float dotPts[6]={}; int dotCount=0;
        if(m_rulerHasP1){memcpy(dotPts+dotCount*3,m_rulerP1,12);dotCount++;}
        if(m_rulerHasP2){memcpy(dotPts+dotCount*3,m_rulerP2,12);dotCount++;}
        glBindBuffer(GL_ARRAY_BUFFER,m_rulerVbo);
        glBufferSubData(GL_ARRAY_BUFFER,0,dotCount*12,dotPts);
        glBindVertexArray(m_rulerVao);
        glUniform4f(glGetUniformLocation(m_wireProg,"uColor"),1.0f,0.3f,0.3f,1.0f);
        glUniform1f(glGetUniformLocation(m_wireProg,"uPointSize"),14.0f);
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
    m_camYaw+=dx*0.005f;
    m_camPitch=std::clamp(m_camPitch+dy*0.005f,-PI*0.48f,PI*0.48f);
}
void Renderer::touchZoom(float f){ m_camDist=std::clamp(m_camDist/f,0.1f,50.0f); }
void Renderer::touchPan(float dx,float dy){
    float s=m_camDist/(float)std::max(m_height,1);
    m_panX+=dx*s; m_panY-=dy*s;
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

// Step 1: IO thread — parse file AND do mesh separation (heavy CPU, ZERO GL calls)
bool Renderer::parseModel(const std::string& path){
    // Clear any previously pending data
    m_pendingMeshes.clear();
    m_hasPending=false;

    ModelData md;
    if(!ModelLoader::load(path, md)){
        LOGE("parseModel: load failed — %s", path.c_str());
        return false;
    }

    // Capture colors (read-only, safe from IO thread)
    float cr=m_colorR, cg=m_colorG, cb=m_colorB;

    // All heavy CPU work (Union-Find + vertex remapping) done here on IO thread
    separateMeshesCPU(md, m_pendingMeshes, cr, cg, cb);

    if(m_pendingMeshes.empty()){
        LOGE("parseModel: no meshes after separation — %s", path.c_str());
        return false;
    }

    m_pendingOrigWmm        = md.widthMM();
    m_pendingOrigHmm        = md.heightMM();
    m_pendingOrigDmm        = md.depthMM();
    m_pendingNormalizeScale = md.normalizeScale;
    m_hasPending            = true;

    LOGI("parseModel OK — %zu verts, %zu idx → %zu mesh(es) | %.1fx%.1fx%.1f mm",
         md.vertices.size(), md.indices.size(), m_pendingMeshes.size(),
         md.widthMM(), md.heightMM(), md.depthMM());
    return true;
}

// Step 2: GL thread — ONLY GPU buffer uploads (fast, no heavy CPU work)
bool Renderer::uploadParsed(){
    if(!m_hasPending || m_pendingMeshes.empty()){
        LOGE("uploadParsed: no pending meshes");
        return false;
    }

    // Delete old mesh GPU objects
    for(auto& mo:m_meshes){
        if(mo.vao) glDeleteVertexArrays(1,&mo.vao);
        if(mo.vbo) glDeleteBuffers(1,&mo.vbo);
        if(mo.ibo) glDeleteBuffers(1,&mo.ibo);
    }

    m_origWmm        = m_pendingOrigWmm;
    m_origHmm        = m_pendingOrigHmm;
    m_origDmm        = m_pendingOrigDmm;
    m_normalizeScale = m_pendingNormalizeScale;

    // Move pending meshes into active list (zero-copy)
    m_meshes = std::move(m_pendingMeshes);
    m_hasPending = false;

    // Fast GPU upload — this is all that runs on the GL thread
    for(auto& mo:m_meshes) uploadMeshObject(mo);

    m_hasModel     = !m_meshes.empty();
    m_selectedMesh = -1;
    resetTransform(); resetCamera(); clearRuler();

    LOGI("uploadParsed OK — %d mesh(es) uploaded to GPU", (int)m_meshes.size());
    return m_hasModel;
}

// ── Legacy single-step load (GL thread) ──────────────────────────────────────
bool Renderer::loadModel(const std::string& path){
    ModelData md;
    if(!ModelLoader::load(path,md)) return false;
    m_origWmm=md.widthMM(); m_origHmm=md.heightMM(); m_origDmm=md.depthMM();
    m_normalizeScale=md.normalizeScale;

    // Delete old GPU objects
    for(auto& mo:m_meshes){
        if(mo.vao) glDeleteVertexArrays(1,&mo.vao);
        if(mo.vbo) glDeleteBuffers(1,&mo.vbo);
        if(mo.ibo) glDeleteBuffers(1,&mo.ibo);
    }

    separateMeshesCPU(md, m_meshes, m_colorR, m_colorG, m_colorB);
    for(auto& mo:m_meshes) uploadMeshObject(mo);

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
void Renderer::setMeshColor(int idx,float r,float g,float b){
    if(idx>=0&&idx<(int)m_meshes.size()){ m_meshes[idx].colorR=r;m_meshes[idx].colorG=g;m_meshes[idx].colorB=b; }
}
void Renderer::setMeshScaleMM(int idx,float w,float h,float d){
    if(idx<0||idx>=(int)m_meshes.size()) return;
    auto& mo=m_meshes[idx];
    float minX=FLT_MAX,minY=FLT_MAX,minZ=FLT_MAX;
    float maxX=-FLT_MAX,maxY=-FLT_MAX,maxZ=-FLT_MAX;
    for(auto& v:mo.vertices){
        minX=std::min(minX,v.px);maxX=std::max(maxX,v.px);
        minY=std::min(minY,v.py);maxY=std::max(maxY,v.py);
        minZ=std::min(minZ,v.pz);maxZ=std::max(maxZ,v.pz);
    }
    float sx=maxX-minX, sy=maxY-minY, sz=maxZ-minZ;
    float mmPerUnit=(m_origWmm>1e-9f)?m_origWmm/2.0f:1.0f;
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

// ── Export ───────────────────────────────────────────────────────────────────
bool Renderer::exportOBJ(const std::string& path) const {
    std::ofstream f(path);
    if(!f) return false;
    f<<"# Exported by 3D Model Viewer\n";
    int baseVertex=1;
    for(const auto& mo:m_meshes){
        if(!mo.visible) continue;
        f<<"o "<<mo.name<<"\n";
        for(const auto& v:mo.vertices)
            f<<"v "<<v.px<<" "<<v.py<<" "<<v.pz<<"\n";
        for(const auto& v:mo.vertices)
            f<<"vn "<<v.nx<<" "<<v.ny<<" "<<v.nz<<"\n";
        for(size_t i=0;i+2<mo.indices.size();i+=3){
            int a=mo.indices[i+0]+baseVertex;
            int b=mo.indices[i+1]+baseVertex;
            int c=mo.indices[i+2]+baseVertex;
            f<<"f "<<a<<"//"<<a<<" "<<b<<"//"<<b<<" "<<c<<"//"<<c<<"\n";
        }
        baseVertex+=(int)mo.vertices.size();
    }
    return true;
}

bool Renderer::exportSTL(const std::string& path) const {
    std::ofstream f(path,std::ios::binary);
    if(!f) return false;
    uint32_t total=0;
    for(const auto& mo:m_meshes) if(mo.visible) total+=(uint32_t)(mo.indices.size()/3);
    char header[80]="3D Model Viewer Export"; f.write(header,80);
    f.write(reinterpret_cast<const char*>(&total),4);
    for(const auto& mo:m_meshes){
        if(!mo.visible) continue;
        for(size_t i=0;i+2<mo.indices.size();i+=3){
            const auto& v0=mo.vertices[mo.indices[i+0]];
            const auto& v1=mo.vertices[mo.indices[i+1]];
            const auto& v2=mo.vertices[mo.indices[i+2]];
            Vec3 e1{v1.px-v0.px,v1.py-v0.py,v1.pz-v0.pz};
            Vec3 e2{v2.px-v0.px,v2.py-v0.py,v2.pz-v0.pz};
            Vec3 n=e1.cross(e2).normalized();
            float nf[3]={n.x,n.y,n.z};
            float p0[3]={v0.px,v0.py,v0.pz};
            float p1[3]={v1.px,v1.py,v1.pz};
            float p2[3]={v2.px,v2.py,v2.pz};
            uint16_t att=0;
            f.write(reinterpret_cast<const char*>(nf),12);
            f.write(reinterpret_cast<const char*>(p0),12);
            f.write(reinterpret_cast<const char*>(p1),12);
            f.write(reinterpret_cast<const char*>(p2),12);
            f.write(reinterpret_cast<const char*>(&att),2);
        }
    }
    return true;
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
