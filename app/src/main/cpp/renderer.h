#pragma once
#include <GLES3/gl3.h>
#include <vector>
#include <cstdint>
#include "math_utils.h"
#include "model_loader.h"

struct TransformState {
    float rotX = 0, rotY = 0, rotZ = 0;
    float posX = 0, posY = 0, posZ = 0;
    float scaX = 1, scaY = 1, scaZ = 1;
};

class Renderer {
public:
    Renderer();
    ~Renderer();

    bool init(int width, int height);
    void draw();
    void resize(int width, int height);

    bool loadModel(const std::string& path);

    void touchRotate(float dx, float dy);
    void touchZoom(float factor);
    void touchPan(float dx, float dy);
    void resetCamera();

    void setRotation(float x, float y, float z);
    void setTranslation(float x, float y, float z);
    void setScale(float x, float y, float z);
    void mirrorX(); void mirrorY(); void mirrorZ();
    void resetTransform();

    void setColor(float r, float g, float b);
    void setAmbient(float a);
    void setDiffuse(float d);
    void setWireframe(bool on);
    void setShowBoundingBox(bool on);

    void pushUndoState();
    void undo();
    void redo();

    std::vector<uint8_t> takeScreenshot();
    float getFPS() const { return m_fps; }
    TransformState getTransform() const;

private:
    GLuint m_vao = 0, m_vbo = 0, m_ibo = 0;
    GLuint m_mainProg = 0, m_wireProg = 0;
    GLuint m_bbVao = 0, m_bbVbo = 0, m_bbIbo = 0;

    GLsizei m_indexCount   = 0;
    GLsizei m_bbIndexCount = 0;
    bool    m_hasModel     = false;

    int m_width = 1, m_height = 1;

    float m_camYaw = 0.4f, m_camPitch = 0.3f, m_camDist = 3.5f;
    float m_panX = 0.0f, m_panY = 0.0f;

    float m_rotX=0,m_rotY=0,m_rotZ=0;
    float m_posX=0,m_posY=0,m_posZ=0;
    float m_scaX=1,m_scaY=1,m_scaZ=1;

    float m_colorR=0.7f,m_colorG=0.7f,m_colorB=0.9f;
    float m_ambient=0.3f, m_diffuse=0.8f;

    bool m_wireframe = false;
    bool m_showBBox  = false;

    float   m_fps        = 0;
    int     m_frameCount = 0;
    int64_t m_fpsTimerNs = 0;

    static constexpr int MAX_UNDO = 50;
    std::vector<TransformState> m_undoStack;
    std::vector<TransformState> m_redoStack;

    void buildShaders();
    void buildBoundingBox();
    void uploadMesh(const ModelData& md);
    Mat4 buildModelMatrix() const;
    void updateFPS();
};
