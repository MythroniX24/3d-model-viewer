#include "renderer.h"
#include "shader_utils.h"
#include <android/log.h>
#include <ctime>
#include <cstring>
#include <cfloat>
#include <algorithm>
#include <cmath>

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
out vec2 vUV;
void main(){
    vec4 wp=uModel*vec4(aPos,1.0);
    vFragPos=wp.xyz; vNormal=normalize(uNorm*aNorm); vUV=aUV;
    gl_Position=uMVP*vec4(aPos,1.0);
})";

static const char* kFragPhong = R"(#version 300 es
precision mediump float;
in vec3 vFragPos, vNormal; in vec2 vUV;
out vec4 fragColor;
uniform vec3 uColor, uLightDir;
uniform float uAmbient, uDiffuse;
void main(){
    vec3 n=normalize(vNormal);
    float diff=max(dot(n,-uLightDir),0.0);
    vec3 vd=normalize(vec3(0.0,0.0,3.5)-vFragPos);
    vec3 rd=reflect(uLightDir,n);
    float spec=pow(max(dot(vd,rd),0.0),32.0)*0.25;
    vec3 c=(uAmbient+uDiffuse*diff)*uColor+spec;
    fragColor=vec4(clamp(c,0.0,1.0),1.0);
})";

static const char* kVertSimple = R"(#version 300 es
layout(location=0) in vec3 aPos;
uniform mat4 uMVP;
void main(){ gl_Position=uMVP*vec4(aPos,1.0); })";

static const char* kFragSimple = R"(#version 300 es
precision mediump float;
uniform vec4 uColor;
out vec4 fragColor;
void main(){ fragColor=uColor; })";

// ── Time ─────────────────────────────────────────────────────────────────────
static int64_t nowNs(){
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC,&ts);
    return (int64_t)ts.tv_sec*1000000000LL+ts.tv_nsec;
}

// ── Ctor/Dtor ────────────────────────────────────────────────────────────────
Renderer::Renderer()=default;
Renderer::~Renderer(){
    if(m_vao)      glDeleteVertexArrays(1,&m_vao);
    if(m_vbo)      glDeleteBuffers(1,&m_vbo);
    if(m_ibo)      glDeleteBuffers(1,&m_ibo);
    if(m_bbVao)    glDeleteVertexArrays(1,&m_bbVao);
    if(m_bbVbo)    glDeleteBuffers(1,&m_bbVbo);
    if(m_bbIbo)    glDeleteBuffers(1,&m_bbIbo);
    if(m_rulerVao) glDeleteVertexArrays(1,&m_rulerVao);
    if(m_rulerVbo) glDeleteBuffers(1,&m_rulerVbo);
    if(m_mainProg) glDeleteProgram(m_mainProg);
    if(m_wireProg) glDeleteProgram(m_wireProg);
}

// ── Init ────────────────────────────────────────────────────────────────────
bool Renderer::init(int w, int h){
    m_width=w; m_height=h;
    glViewport(0,0,w,h);
    glClearColor(0.10f,0.10f,0.13f,1.0f);
    glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LEQUAL);
    glEnable(GL_CULL_FACE);  glCullFace(GL_BACK);

    buildShaders();
    glGenVertexArrays(1,&m_vao);   glGenBuffers(1,&m_vbo);   glGenBuffers(1,&m_ibo);
    glGenVertexArrays(1,&m_bbVao); glGenBuffers(1,&m_bbVbo); glGenBuffers(1,&m_bbIbo);
    glGenVertexArrays(1,&m_rulerVao); glGenBuffers(1,&m_rulerVbo);

    // Build ruler VAO (2 points, updated dynamically)
    glBindVertexArray(m_rulerVao);
    glBindBuffer(GL_ARRAY_BUFFER,m_rulerVbo);
    float dummy[6]={0,0,0,0,0,0};
    glBufferData(GL_ARRAY_BUFFER,sizeof(dummy),dummy,GL_DYNAMIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0,3,GL_FLOAT,GL_FALSE,12,nullptr);
    glBindVertexArray(0);

    m_fpsTimerNs=nowNs();
    LOGI("Renderer init %dx%d",w,h);
    return !checkGLError("init");
}
void Renderer::resize(int w,int h){ m_width=w; m_height=h; glViewport(0,0,w,h); }
void Renderer::buildShaders(){
    if(m_mainProg) glDeleteProgram(m_mainProg);
    if(m_wireProg) glDeleteProgram(m_wireProg);
    m_mainProg=createProgram(kVertPhong,kFragPhong);
    m_wireProg=createProgram(kVertSimple,kFragSimple);
}

// ── Upload mesh ──────────────────────────────────────────────────────────────
void Renderer::uploadMesh(const ModelData& md){
    // Keep CPU copy for raycasting
    m_cpuVerts   = md.vertices;
    m_cpuIndices = md.indices;

    glBindVertexArray(m_vao);
    glBindBuffer(GL_ARRAY_BUFFER,m_vbo);
    glBufferData(GL_ARRAY_BUFFER,(GLsizeiptr)(md.vertices.size()*sizeof(Vertex)),md.vertices.data(),GL_STATIC_DRAW);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,m_ibo);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER,(GLsizeiptr)(md.indices.size()*sizeof(unsigned int)),md.indices.data(),GL_STATIC_DRAW);
    constexpr GLsizei stride=sizeof(Vertex);
    glEnableVertexAttribArray(0); glVertexAttribPointer(0,3,GL_FLOAT,GL_FALSE,stride,(void*)offsetof(Vertex,px));
    glEnableVertexAttribArray(1); glVertexAttribPointer(1,3,GL_FLOAT,GL_FALSE,stride,(void*)offsetof(Vertex,nx));
    glEnableVertexAttribArray(2); glVertexAttribPointer(2,2,GL_FLOAT,GL_FALSE,stride,(void*)offsetof(Vertex,u));
    glBindVertexArray(0);
    m_indexCount=(GLsizei)md.indices.size();
    m_hasModel=true;
    buildBoundingBox();
}

// ── Bounding box ─────────────────────────────────────────────────────────────
void Renderer::buildBoundingBox(){
    static const float v[24]={-1,-1,-1,1,-1,-1,1,1,-1,-1,1,-1,-1,-1,1,1,-1,1,1,1,1,-1,1,1};
    static const uint16_t idx[24]={0,1,1,2,2,3,3,0,4,5,5,6,6,7,7,4,0,4,1,5,2,6,3,7};
    glBindVertexArray(m_bbVao);
    glBindBuffer(GL_ARRAY_BUFFER,m_bbVbo);
    glBufferData(GL_ARRAY_BUFFER,sizeof(v),v,GL_STATIC_DRAW);
    glEnableVertexAttribArray(0); glVertexAttribPointer(0,3,GL_FLOAT,GL_FALSE,12,nullptr);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,m_bbIbo);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER,sizeof(idx),idx,GL_STATIC_DRAW);
    glBindVertexArray(0);
    m_bbIndexCount=24;
}

// ── Model matrix ─────────────────────────────────────────────────────────────
Mat4 Renderer::buildModelMatrix() const {
    return Mat4::translation(m_posX,m_posY,m_posZ)
         * Mat4::rotationZ(m_rotZ*DEG2RAD)
         * Mat4::rotationY(m_rotY*DEG2RAD)
         * Mat4::rotationX(m_rotX*DEG2RAD)
         * Mat4::scale(m_scaX,m_scaY,m_scaZ);
}

Vec3 Renderer::cameraEye() const {
    float cp=cosf(m_camPitch),sp=sinf(m_camPitch);
    float cy=cosf(m_camYaw),  sy=sinf(m_camYaw);
    return {m_panX+m_camDist*cp*sy, m_panY+m_camDist*sp, m_camDist*cp*cy};
}

// ── Draw ─────────────────────────────────────────────────────────────────────
void Renderer::draw(){
    updateFPS();
    glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
    if (!m_hasModel||!m_mainProg||!m_wireProg) return;

    float aspect=(float)m_width/(float)(m_height>0?m_height:1);
    Mat4 proj=Mat4::perspective(60.0f*DEG2RAD,aspect,0.01f,100.0f);
    Vec3 eye=cameraEye();
    Mat4 view=Mat4::lookAt(eye,{m_panX,m_panY,0},{0,1,0});
    Mat4 model=buildModelMatrix();
    Mat4 mvp=proj*view*model;
    Vec3 lightDir=Vec3{-0.4f,-1.0f,-0.3f}.normalized();

    auto uploadPhong=[&](float r,float g,float b){
        glUseProgram(m_mainProg);
        glUniformMatrix4fv(glGetUniformLocation(m_mainProg,"uMVP"),  1,GL_FALSE,mvp.m);
        glUniformMatrix4fv(glGetUniformLocation(m_mainProg,"uModel"),1,GL_FALSE,model.m);
        float nm[9]; model.toNormalMatrix(nm);
        glUniformMatrix3fv(glGetUniformLocation(m_mainProg,"uNorm"),1,GL_FALSE,nm);
        glUniform3f(glGetUniformLocation(m_mainProg,"uColor"),    r,g,b);
        glUniform3f(glGetUniformLocation(m_mainProg,"uLightDir"), lightDir.x,lightDir.y,lightDir.z);
        glUniform1f(glGetUniformLocation(m_mainProg,"uAmbient"),  m_ambient);
        glUniform1f(glGetUniformLocation(m_mainProg,"uDiffuse"),  m_diffuse);
    };

    if (m_wireframe){
        glEnable(GL_POLYGON_OFFSET_FILL); glPolygonOffset(1.0f,1.0f);
        uploadPhong(0.05f,0.05f,0.08f);
        glBindVertexArray(m_vao); glDrawElements(GL_TRIANGLES,m_indexCount,GL_UNSIGNED_INT,nullptr);
        glDisable(GL_POLYGON_OFFSET_FILL);
        glUseProgram(m_wireProg);
        glUniformMatrix4fv(glGetUniformLocation(m_wireProg,"uMVP"),1,GL_FALSE,mvp.m);
        glUniform4f(glGetUniformLocation(m_wireProg,"uColor"),0.2f,0.8f,1.0f,1.0f);
        glLineWidth(1.2f); glDrawElements(GL_LINES,m_indexCount,GL_UNSIGNED_INT,nullptr);
        glBindVertexArray(0);
    } else {
        uploadPhong(m_colorR,m_colorG,m_colorB);
        glBindVertexArray(m_vao); glDrawElements(GL_TRIANGLES,m_indexCount,GL_UNSIGNED_INT,nullptr);
        glBindVertexArray(0);
    }

    // Bounding box
    if (m_showBBox && m_bbIndexCount>0){
        glUseProgram(m_wireProg);
        glUniformMatrix4fv(glGetUniformLocation(m_wireProg,"uMVP"),1,GL_FALSE,mvp.m);
        glUniform4f(glGetUniformLocation(m_wireProg,"uColor"),1.0f,0.6f,0.1f,0.9f);
        glLineWidth(1.5f);
        glBindVertexArray(m_bbVao);
        glDrawElements(GL_LINES,m_bbIndexCount,GL_UNSIGNED_SHORT,nullptr);
        glBindVertexArray(0);
    }

    // Ruler line + points
    if (m_rulerHasP1 || m_rulerHasP2){
        // Ruler draws in WORLD space — need view*proj only (ruler points already world-space)
        Mat4 vp = proj * view;
        glUseProgram(m_wireProg);
        glUniformMatrix4fv(glGetUniformLocation(m_wireProg,"uMVP"),1,GL_FALSE,vp.m);
        glDisable(GL_DEPTH_TEST);

        if (m_rulerHasP1 && m_rulerHasP2){
            float pts[6]={m_rulerP1[0],m_rulerP1[1],m_rulerP1[2],
                          m_rulerP2[0],m_rulerP2[1],m_rulerP2[2]};
            glBindBuffer(GL_ARRAY_BUFFER,m_rulerVbo);
            glBufferSubData(GL_ARRAY_BUFFER,0,sizeof(pts),pts);
            glBindVertexArray(m_rulerVao);
            glUniform4f(glGetUniformLocation(m_wireProg,"uColor"),1.0f,1.0f,0.0f,1.0f);
            glLineWidth(2.5f); glDrawArrays(GL_LINES,0,2);
            glBindVertexArray(0);
        }

        // Draw point dots as GL_POINTS
        float dotPts[6]={};
        int dotCount=0;
        if (m_rulerHasP1){memcpy(dotPts+dotCount*3,m_rulerP1,12);dotCount++;}
        if (m_rulerHasP2){memcpy(dotPts+dotCount*3,m_rulerP2,12);dotCount++;}
        glBindBuffer(GL_ARRAY_BUFFER,m_rulerVbo);
        glBufferSubData(GL_ARRAY_BUFFER,0,dotCount*12,dotPts);
        glBindVertexArray(m_rulerVao);
        glUniform4f(glGetUniformLocation(m_wireProg,"uColor"),1.0f,0.3f,0.3f,1.0f);
        glPointSize(10.0f); glDrawArrays(GL_POINTS,0,dotCount);
        glBindVertexArray(0);
        glEnable(GL_DEPTH_TEST);
    }
}

// ── FPS ──────────────────────────────────────────────────────────────────────
void Renderer::updateFPS(){
    int64_t now=nowNs();
    if (++m_frameCount>=60){
        float e=(float)(now-m_fpsTimerNs)/1e9f;
        m_fps=(e>0)?(m_frameCount/e):0;
        m_frameCount=0; m_fpsTimerNs=now;
    }
}

// ── Camera ───────────────────────────────────────────────────────────────────
void Renderer::touchRotate(float dx,float dy){
    m_camYaw+=dx*0.005f;
    m_camPitch=std::clamp(m_camPitch+dy*0.005f,-PI*0.48f,PI*0.48f);
}
void Renderer::touchZoom(float f){ m_camDist=std::clamp(m_camDist/f,0.2f,30.0f); }
void Renderer::touchPan(float dx,float dy){
    float s=m_camDist/(float)(m_height>0?m_height:1);
    m_panX-=dx*s; m_panY+=dy*s;
}
void Renderer::resetCamera(){ m_camYaw=0.4f;m_camPitch=0.3f;m_camDist=3.5f;m_panX=0;m_panY=0; }

// ── Transforms ───────────────────────────────────────────────────────────────
void Renderer::setRotation(float x,float y,float z)    {m_rotX=x;m_rotY=y;m_rotZ=z;}
void Renderer::setTranslation(float x,float y,float z) {m_posX=x;m_posY=y;m_posZ=z;}

// MM → scale conversion:
// currentWidthMM = scaX * origWmm  →  scaX = desiredWmm / origWmm
void Renderer::setScaleMM(float wMM, float hMM, float dMM){
    if (m_origWmm>1e-9f) m_scaX = wMM / m_origWmm;
    if (m_origHmm>1e-9f) m_scaY = hMM / m_origHmm;
    if (m_origDmm>1e-9f) m_scaZ = dMM / m_origDmm;
}

void Renderer::mirrorX(){m_scaX=-m_scaX;}
void Renderer::mirrorY(){m_scaY=-m_scaY;}
void Renderer::mirrorZ(){m_scaZ=-m_scaZ;}
void Renderer::resetTransform(){
    m_rotX=m_rotY=m_rotZ=0; m_posX=m_posY=m_posZ=0; m_scaX=m_scaY=m_scaZ=1;
}

// ── Visual ───────────────────────────────────────────────────────────────────
void Renderer::setColor(float r,float g,float b){m_colorR=r;m_colorG=g;m_colorB=b;}
void Renderer::setAmbient(float a){m_ambient=std::clamp(a,0.0f,1.0f);}
void Renderer::setDiffuse(float d){m_diffuse=std::clamp(d,0.0f,1.0f);}
void Renderer::setWireframe(bool on){m_wireframe=on;}
void Renderer::setShowBoundingBox(bool on){m_showBBox=on;}

// ── Load model ───────────────────────────────────────────────────────────────
bool Renderer::loadModel(const std::string& path){
    ModelData md;
    if (!ModelLoader::load(path,md)) return false;
    // Store original mm dimensions
    m_origWmm = md.widthMM();
    m_origHmm = md.heightMM();
    m_origDmm = md.depthMM();
    m_normalizeScale = md.normalizeScale;
    uploadMesh(md);
    resetTransform(); resetCamera(); clearRuler();
    LOGI("Model mm: %.1f x %.1f x %.1f",m_origWmm,m_origHmm,m_origDmm);
    return true;
}

// ── Size queries ─────────────────────────────────────────────────────────────
void Renderer::getModelSizeMM(float& w,float& h,float& d) const {
    w=m_origWmm; h=m_origHmm; d=m_origDmm;
}
void Renderer::getCurrentSizeMM(float& w,float& h,float& d) const {
    w=fabsf(m_scaX)*m_origWmm;
    h=fabsf(m_scaY)*m_origHmm;
    d=fabsf(m_scaZ)*m_origDmm;
}

// ── Raycasting ───────────────────────────────────────────────────────────────
Renderer::Ray Renderer::screenToRay(float sx,float sy,float sw,float sh) const {
    // NDC coords (y flipped — screen Y grows down)
    float ndcX = (2.0f*sx/sw) - 1.0f;
    float ndcY = 1.0f - (2.0f*sy/sh);

    float aspect = sw / (sh > 0 ? sh : 1);
    float tanHalfFov = tanf(60.0f*DEG2RAD*0.5f);

    Vec3 eye = cameraEye();
    // Reconstruct view basis
    Vec3 center{m_panX,m_panY,0};
    Vec3 fwd = (center - eye).normalized();
    Vec3 right = fwd.cross({0,1,0}).normalized();
    Vec3 up    = right.cross(fwd).normalized();

    // Ray direction in view space → world space
    Vec3 dir = (fwd
              + right * (ndcX * aspect * tanHalfFov)
              + up    * (ndcY * tanHalfFov)).normalized();

    return {eye, dir};
}

// Möller–Trumbore ray-triangle intersection
bool Renderer::rayTriangle(const Ray& ray,
                            const Vec3& v0,const Vec3& v1,const Vec3& v2,
                            float& t) const {
    const float EPS = 1e-7f;
    Vec3 e1 = v1-v0, e2 = v2-v0;
    Vec3 h  = ray.dir.cross(e2);
    float a = e1.dot(h);
    if (fabsf(a) < EPS) return false;
    float f = 1.0f/a;
    Vec3 s = ray.origin-v0;
    float u = f*s.dot(h);
    if (u<0||u>1) return false;
    Vec3 q = s.cross(e1);
    float v = f*ray.dir.dot(q);
    if (v<0||u+v>1) return false;
    t = f*e2.dot(q);
    return t > EPS;
}

bool Renderer::pickPoint(float sx,float sy,float sw,float sh,float out[3]){
    if (!m_hasModel || m_cpuVerts.empty()) return false;

    Ray ray = screenToRay(sx,sy,sw,sh);
    Mat4 model = buildModelMatrix();

    float bestT = FLT_MAX;
    bool  hit   = false;

    // Test every triangle (CPU-side) — model matrix applied to vertices
    for (size_t i=0; i+2<m_cpuIndices.size(); i+=3){
        auto& va=m_cpuVerts[m_cpuIndices[i+0]];
        auto& vb=m_cpuVerts[m_cpuIndices[i+1]];
        auto& vc=m_cpuVerts[m_cpuIndices[i+2]];

        // Transform to world space
        auto transform=[&](const Vertex& v)->Vec3{
            float x=model.m[0]*v.px+model.m[4]*v.py+model.m[8] *v.pz+model.m[12];
            float y=model.m[1]*v.px+model.m[5]*v.py+model.m[9] *v.pz+model.m[13];
            float z=model.m[2]*v.px+model.m[6]*v.py+model.m[10]*v.pz+model.m[14];
            return {x,y,z};
        };

        float t;
        if (rayTriangle(ray,transform(va),transform(vb),transform(vc),t)){
            if (t < bestT){ bestT=t; hit=true; }
        }
    }
    if (hit){
        out[0]=ray.origin.x+ray.dir.x*bestT;
        out[1]=ray.origin.y+ray.dir.y*bestT;
        out[2]=ray.origin.z+ray.dir.z*bestT;
    }
    return hit;
}

// ── Ruler ────────────────────────────────────────────────────────────────────
void Renderer::setRulerPoints(bool hasP1,float p1[3],bool hasP2,float p2[3]){
    m_rulerHasP1=hasP1; m_rulerHasP2=hasP2;
    if(hasP1) memcpy(m_rulerP1,p1,12);
    if(hasP2) memcpy(m_rulerP2,p2,12);
}
void Renderer::clearRuler(){ m_rulerHasP1=m_rulerHasP2=false; }

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
    return {m_rotX,m_rotY,m_rotZ,m_posX,m_posY,m_posZ,m_scaX,m_scaY,m_scaZ};
}

// ── Screenshot ───────────────────────────────────────────────────────────────
std::vector<uint8_t> Renderer::takeScreenshot(){
    std::vector<uint8_t> p((size_t)m_width*m_height*4);
    glReadPixels(0,0,m_width,m_height,GL_RGBA,GL_UNSIGNED_BYTE,p.data());
    int rb=m_width*4; std::vector<uint8_t> row((size_t)rb);
    for(int y=0;y<m_height/2;++y){
        uint8_t* top=p.data()+y*rb, *bot=p.data()+(m_height-1-y)*rb;
        memcpy(row.data(),top,rb); memcpy(top,bot,rb); memcpy(bot,row.data(),rb);
    }
    return p;
}
