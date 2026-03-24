#pragma once
#include <GLES3/gl3.h>
#include <vector>
#include <array>
#include <cstdint>
#include "math_utils.h"
#include "model_loader.h"

struct TransformState {
    float rotX=0,rotY=0,rotZ=0;
    float posX=0,posY=0,posZ=0;
    float scaX=1,scaY=1,scaZ=1;
};

// Ruler point: 3D position in NORMALIZED model space
struct RulerPoint { float x,y,z; bool valid=false; };

class Renderer {
public:
    Renderer(); ~Renderer();

    bool init(int width, int height);
    void draw();
    void resize(int width, int height);

    // Model
    bool loadModel(const std::string& path);

    // Camera
    void touchRotate(float dx, float dy);
    void touchZoom(float factor);
    void touchPan(float dx, float dy);
    void resetCamera();

    // Transform (in mm — scale only; rot/pos still in degrees/units)
    void setRotation(float x, float y, float z);
    void setTranslation(float x, float y, float z);
    // Set scale via mm dimensions directly
    void setScaleMM(float wMM, float hMM, float dMM);
    void mirrorX(); void mirrorY(); void mirrorZ();
    void resetTransform();

    // Visual
    void setColor(float r, float g, float b);
    void setAmbient(float a);
    void setDiffuse(float d);
    void setWireframe(bool on);
    void setShowBoundingBox(bool on);

    // Undo / Redo
    void pushUndoState(); void undo(); void redo();

    // ── Ruler measurement ──────────────────────────────────────────────────
    // Returns true if ray hit the mesh; fills outPoint[3] with WORLD position
    bool pickPoint(float screenX, float screenY,
                   float screenW, float screenH,
                   float outPoint[3]);
    // Set ruler endpoints (world space) for rendering
    void setRulerPoints(bool hasP1, float p1[3], bool hasP2, float p2[3]);
    void clearRuler();

    // ── Size info (for mm UI) ──────────────────────────────────────────────
    // Returns original model size in mm
    void getModelSizeMM(float& wMM, float& hMM, float& dMM) const;
    // Returns current rendered size in mm
    void getCurrentSizeMM(float& wMM, float& hMM, float& dMM) const;

    // Screenshot
    std::vector<uint8_t> takeScreenshot();

    float getFPS() const { return m_fps; }
    TransformState getTransform() const;

private:
    // GPU
    GLuint m_vao=0,m_vbo=0,m_ibo=0;
    GLuint m_mainProg=0,m_wireProg=0;
    GLuint m_bbVao=0,m_bbVbo=0,m_bbIbo=0;
    GLuint m_rulerVao=0,m_rulerVbo=0;

    GLsizei m_indexCount=0,m_bbIndexCount=0;
    bool m_hasModel=false;

    // Viewport
    int m_width=1,m_height=1;

    // Camera
    float m_camYaw=0.4f,m_camPitch=0.3f,m_camDist=3.5f;
    float m_panX=0,m_panY=0;

    // Transform (internal — scale is a multiplier)
    float m_rotX=0,m_rotY=0,m_rotZ=0;
    float m_posX=0,m_posY=0,m_posZ=0;
    float m_scaX=1,m_scaY=1,m_scaZ=1;

    // Original model info (mm)
    float m_origWmm=1,m_origHmm=1,m_origDmm=1;
    // normalizeScale stored so we can compute mm↔scale conversions
    float m_normalizeScale=1.0f;

    // Visual
    float m_colorR=0.72f,m_colorG=0.72f,m_colorB=0.92f;
    float m_ambient=0.3f,m_diffuse=0.8f;
    bool m_wireframe=false,m_showBBox=false;

    // FPS
    float m_fps=0; int m_frameCount=0; int64_t m_fpsTimerNs=0;

    // CPU mesh copy for raycasting
    std::vector<Vertex>       m_cpuVerts;
    std::vector<unsigned int> m_cpuIndices;

    // Ruler
    bool  m_rulerHasP1=false, m_rulerHasP2=false;
    float m_rulerP1[3]={}, m_rulerP2[3]={};

    // Undo/redo
    static constexpr int MAX_UNDO=50;
    std::vector<TransformState> m_undoStack,m_redoStack;

    // Internal helpers
    void buildShaders();
    void buildBoundingBox();
    void buildRulerVAO();
    void uploadMesh(const ModelData& md);
    Mat4 buildModelMatrix() const;
    void updateFPS();
    Vec3 cameraEye() const;

    // Ray helpers
    struct Ray { Vec3 origin, dir; };
    Ray   screenToRay(float sx, float sy, float sw, float sh) const;
    bool  rayTriangle(const Ray& ray, const Vec3& v0, const Vec3& v1, const Vec3& v2, float& t) const;
};
