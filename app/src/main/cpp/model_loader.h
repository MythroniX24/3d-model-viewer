#pragma once
#include <vector>
#include <string>
#include "math_utils.h"

// ── Vertex: interleaved position + normal + UV ────────────────────────────────
struct Vertex {
    float px, py, pz;   // position
    float nx, ny, nz;   // normal
    float u,  v;        // texcoord
};

// ── Loaded model data sent to GPU ─────────────────────────────────────────────
struct ModelData {
    std::vector<Vertex>       vertices;
    std::vector<unsigned int> indices;

    // Computed at load time
    Vec3  centerOffset;   // subtract to center model at origin
    float normalizeScale; // multiply to fit in unit sphere

    bool  hasNormals = false;
    bool  hasTex     = false;

    void clear() {
        vertices.clear();
        indices.clear();
        centerOffset   = {0,0,0};
        normalizeScale = 1.0f;
        hasNormals = hasTex = false;
    }
};

class ModelLoader {
public:
    // Returns true on success; fills data
    static bool load(const std::string& path, ModelData& data);

private:
    static bool loadOBJ(const std::string& path, ModelData& data);
    static bool loadSTL(const std::string& path, ModelData& data);
    static bool loadGLB(const std::string& path, ModelData& data);

    // Compute flat normals for models that lack them
    static void generateFlatNormals(ModelData& data);

    // Translate to center + scale to unit sphere
    static void normalizeModel(ModelData& data);
};
