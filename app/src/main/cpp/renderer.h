#pragma once
#include <GLES3/gl3.h>
#include <vector>
#include <string>
#include <cstdint>
#include "math_utils.h"
#include "model_loader.h"
#include "mesh_separator.h"

struct TransformState {
    float rotX=0,rotY=0,rotZ=0;
    float posX=0,posY=0,posZ=0;
    float scaX=1,scaY=1,scaZ=1;
};

struct MeshObject {
    std::string name;
    std::vector<Vertex>       vertices;
    std::vector<unsigned int> indices;

    GLuint vao=0,vbo=0,ibo=0;
    bool   gpuReady=false;

    float rotX=0,rotY=0,rotZ=0;
    float posX=0,posY=0,posZ=0;
    float scaX=1,scaY=1,scaZ=1;

    float colorR=0.72f,colorG=0.72f,colorB=0.92f;
    bool  visible=true;
    bool  selected=false;
};

class Renderer {
public:
    Renderer(); ~Renderer();

    bool init(int width, int height);
    void draw();
    void resize(int width, int height);

    // TWO-STEP LOAD
    bool parseModel(const std::string& path);  // IO thread — parse only
    bool uploadParsed();                        // GL thread — GPU upload as single mesh (instant)
    bool loadModel(const std::string& path);   // Legacy

    // MANUAL SEPARATION (called by user via button)
    bool performSeparationCPU();   // IO thread — Union-Find, NO GL calls
    bool performSeparationGPU();   // GL thread — re-upload separated meshes
    bool isSeparated() const { return m_isSeparated; }

    // Camera
    void touchRotate(float dx, float dy);
    void touchZoom(float factor);
    void touchPan(float dx, float dy);
    void resetCamera();

    // Global transform
    void setRotation(float x,float y,float z);
    void setTranslation(float x,float y,float z);
    void setScaleMM(float w,float h,float d);
    void mirrorX(); void mirrorY(); void mirrorZ();
    void resetTransform();

    // Visual
    void setColor(float r,float g,float b);
    void setAmbient(float a); void setDiffuse(float d);
    void setWireframe(bool on); void setShowBoundingBox(bool on);

    // Mesh
    int  getMeshCount() const { return (int)m_meshes.size(); }
    void getMeshName(int idx, char* buf, int bufLen) const;
    void selectMesh(int idx);
    int  getSelectedMesh() const { return m_selectedMesh; }
    void deleteMesh(int idx);
    void setMeshVisible(int idx, bool v);
    void setMeshColor(int idx, float r, float g, float b);
    void setMeshScaleMM(int idx, float w, float h, float d);
    void getMeshSizeMM(int idx, float& w, float& h, float& d) const;
    int  getMeshVertexCount(int idx) const;

    // Export
    bool exportOBJ(const std::string& path) const;
    bool exportSTL(const std::string& path) const;

    // Ruler
    bool pickPoint(float sx,float sy,float sw,float sh,float out[3]);
    void setRulerPoints(bool h1,float* p1,bool h2,float* p2);
    void clearRuler();

    // Size info
    void getModelSizeMM(float& w,float& h,float& d) const;
    void getCurrentSizeMM(float& w,float& h,float& d) const;

    // Undo/redo, screenshot, fps
    void pushUndoState(); void undo(); void redo();
    std::vector<uint8_t> takeScreenshot();
    float getFPS() const { return m_fps; }
    TransformState getTransform() const;

private:
    GLuint m_mainProg=0, m_wireProg=0;
    GLuint m_bbVao=0, m_bbVbo=0, m_bbIbo=0;
    GLuint m_rulerVao=0, m_rulerVbo=0;
    GLsizei m_bbIndexCount=0;

    // Cached uniform locations — avoids glGetUniformLocation every frame
    struct UniformLocs {
        GLint mvp=-1, model=-1, norm=-1, color=-1, lightDir=-1;
        GLint ambient=-1, diffuse=-1, selected=-1, camPos=-1;
        GLint wireMvp=-1, wireColor=-1, wirePointSize=-1;
    } m_uloc;
    void cacheUniformLocs();

    std::vector<MeshObject> m_meshes;
    int  m_selectedMesh = -1;
    bool m_hasModel     = false;
    bool m_isSeparated  = false;

    int m_width=1, m_height=1;

    float m_camYaw=0.4f, m_camPitch=0.3f, m_camDist=3.5f;
    float m_panX=0, m_panY=0;

    float m_rotX=0,m_rotY=0,m_rotZ=0;
    float m_posX=0,m_posY=0,m_posZ=0;
    float m_scaX=1,m_scaY=1,m_scaZ=1;

    // Pending data between parse and upload
    ModelData*              m_pendingData  = nullptr;
    std::vector<MeshObject> m_pendingMeshes;  // used by performSeparation

    float m_origWmm=1,m_origHmm=1,m_origDmm=1;
    float m_normalizeScale=1.0f;

    float m_colorR=0.72f,m_colorG=0.72f,m_colorB=0.92f;
    float m_ambient=0.3f, m_diffuse=0.8f;
    bool  m_wireframe=false, m_showBBox=false;

    float   m_fps=0; int m_frameCount=0; int64_t m_fpsTimerNs=0;

    bool  m_rulerHasP1=false, m_rulerHasP2=false;
    float m_rulerP1[3]={}, m_rulerP2[3]={};

    static constexpr int MAX_UNDO=50;
    std::vector<TransformState> m_undoStack, m_redoStack;

    void buildShaders();
    void buildBoundingBox();
    void uploadMeshObject(MeshObject& mo);
    void separateIntoMeshes(const ModelData& md);
    void separateMeshesCPU(const ModelData& md, std::vector<MeshObject>& out);
    Mat4 buildGlobalMatrix() const;
    Mat4 buildMeshMatrix(const MeshObject& mo) const;
    void updateFPS();
    Vec3 cameraEye() const;

    struct Ray { Vec3 origin, dir; };
    Ray  screenToRay(float sx,float sy,float sw,float sh) const;
    bool rayTriangle(const Ray& r,const Vec3& v0,const Vec3& v1,const Vec3& v2,float& t) const;

    // Production-grade mesh separator (reusable, preallocates buffers)
    MeshSeparator m_separator;
};
