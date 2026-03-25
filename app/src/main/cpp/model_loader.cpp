#include "model_loader.h"
#include <fstream>
#include <sstream>
#include <unordered_map>
#include <cstring>
#include <cfloat>
#include <climits>
#include <android/log.h>
#include <algorithm>
#include <limits>
#include <cctype>

#define TINYOBJLOADER_IMPLEMENTATION
#include "tiny_obj_loader.h"

#define TINYGLTF_IMPLEMENTATION
#define STB_IMAGE_IMPLEMENTATION
#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "tiny_gltf.h"

#define TAG "ModelLoader"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ── Dispatch ─────────────────────────────────────────────────────────────────
bool ModelLoader::load(const std::string& path, ModelData& data) {
    data.clear();
    std::string ext = path;
    std::transform(ext.begin(), ext.end(), ext.begin(), ::tolower);

    bool ok = false;
    if      (ext.size()>=4 && ext.rfind(".obj")==ext.size()-4) ok=loadOBJ(path,data);
    else if (ext.size()>=4 && ext.rfind(".stl")==ext.size()-4) ok=loadSTL(path,data);
    else if (ext.size()>=4 && ext.rfind(".glb")==ext.size()-4) ok=loadGLB(path,data);
    else { LOGE("Unsupported: %s", path.c_str()); return false; }

    if (!ok) { LOGE("Failed: %s", path.c_str()); return false; }
    if (!data.hasNormals) generateFlatNormals(data);
    normalizeModel(data);
    LOGI("Loaded %zu verts, %zu idx | size %.1fx%.1fx%.1f mm",
         data.vertices.size(), data.indices.size(),
         data.widthMM(), data.heightMM(), data.depthMM());
    return true;
}

// ── OBJ ──────────────────────────────────────────────────────────────────────
bool ModelLoader::loadOBJ(const std::string& path, ModelData& data) {
    tinyobj::ObjReaderConfig cfg; cfg.triangulate=true; cfg.vertex_color=false;
    tinyobj::ObjReader reader;
    if (!reader.ParseFromFile(path, cfg)) {
        LOGE("OBJ: %s", reader.Error().c_str()); return false;
    }
    const auto& attrib = reader.GetAttrib();
    const auto& shapes = reader.GetShapes();
    data.hasNormals = !attrib.normals.empty();
    data.hasTex     = !attrib.texcoords.empty();
    data.unitToMM   = 1.0f;  // OBJ assumed mm

    std::unordered_map<std::string,unsigned int> idxMap;
    for (const auto& shape : shapes) {
        for (const auto& idx : shape.mesh.indices) {
            char key[64]; snprintf(key,64,"%d/%d/%d",idx.vertex_index,idx.normal_index,idx.texcoord_index);
            auto it = idxMap.find(key);
            if (it != idxMap.end()) { data.indices.push_back(it->second); continue; }
            Vertex v{};
            int vi=idx.vertex_index;
            v.px=attrib.vertices[3*vi+0]; v.py=attrib.vertices[3*vi+1]; v.pz=attrib.vertices[3*vi+2];
            if (data.hasNormals && idx.normal_index>=0) {
                int ni=idx.normal_index;
                v.nx=attrib.normals[3*ni+0]; v.ny=attrib.normals[3*ni+1]; v.nz=attrib.normals[3*ni+2];
            }
            if (data.hasTex && idx.texcoord_index>=0) {
                int ti=idx.texcoord_index;
                v.u=attrib.texcoords[2*ti+0]; v.v=attrib.texcoords[2*ti+1];
            }
            unsigned int newIdx=(unsigned int)data.vertices.size();
            data.vertices.push_back(v); data.indices.push_back(newIdx); idxMap[key]=newIdx;
        }
    }
    return !data.vertices.empty();
}

// ── STL ──────────────────────────────────────────────────────────────────────
bool ModelLoader::loadSTL(const std::string& path, ModelData& data) {
    // Detect binary vs ASCII robustly. A binary STL starts with an 80-byte header
    // followed by a 4-byte triangle count and 50 bytes per triangle.
    std::ifstream f(path, std::ios::binary);
    if (!f) return false;

    f.seekg(0, std::ios::end);
    const std::streamoff fileSize = f.tellg();
    if (fileSize < 84) return false;
    f.seekg(0, std::ios::beg);

    char header[80];
    f.read(header, 80);
    if (f.fail()) return false;

    uint32_t triCount = 0;
    f.read(reinterpret_cast<char*>(&triCount), 4);
    if (f.fail()) return false;

    const std::streamoff expectedBinarySize = 84 + static_cast<std::streamoff>(triCount) * 50;
    const bool looksBinary = (triCount > 0 && fileSize == expectedBinarySize);
    data.unitToMM = 1.0f;  // STL standard = mm

    if (looksBinary) {
        data.vertices.reserve(triCount * 3);
        data.indices.reserve(triCount * 3);
        for (uint32_t i = 0; i < triCount; ++i) {
            float n[3], p[3][3];
            f.read(reinterpret_cast<char*>(n), 12);
            for (int j = 0; j < 3; ++j) f.read(reinterpret_cast<char*>(p[j]), 12);
            uint16_t att; f.read(reinterpret_cast<char*>(&att), 2);
            if (f.fail()) break;
            for (int j = 0; j < 3; ++j) {
                Vertex v{};
                v.px = p[j][0]; v.py = p[j][1]; v.pz = p[j][2];
                v.nx = n[0];    v.ny = n[1];    v.nz = n[2];
                data.indices.push_back((unsigned int)data.vertices.size());
                data.vertices.push_back(v);
            }
        }
    } else {
        // ASCII fallback: re-open as text and parse facet/vertex blocks.
        f.close();
        std::ifstream tf(path);
        if (!tf) return false;
        std::string line;
        Vec3 normal{};
        while (std::getline(tf, line)) {
            std::istringstream ss(line);
            std::string tok;
            ss >> tok;
            if (tok == "facet") {
                std::string n;
                ss >> n >> normal.x >> normal.y >> normal.z;
            } else if (tok == "vertex") {
                Vertex v{};
                ss >> v.px >> v.py >> v.pz;
                v.nx = normal.x; v.ny = normal.y; v.nz = normal.z;
                data.indices.push_back((unsigned int)data.vertices.size());
                data.vertices.push_back(v);
            }
        }
    }

    data.hasNormals = true;
    return !data.vertices.empty();
}

// ── GLB ──────────────────────────────────────────────────────────────────────
bool ModelLoader::loadGLB(const std::string& path, ModelData& data) {
    tinygltf::Model model; tinygltf::TinyGLTF loader;
    std::string err,warn;
    if (!loader.LoadBinaryFromFile(&model,&err,&warn,path)) { LOGE("GLB: %s",err.c_str()); return false; }
    data.unitToMM = 1000.0f;  // GLB uses meters → convert to mm

    for (const auto& mesh : model.meshes) {
        for (const auto& prim : mesh.primitives) {
            if (prim.mode!=TINYGLTF_MODE_TRIANGLES && prim.mode!=TINYGLTF_MODE_TRIANGLE_STRIP) continue;
            unsigned int baseVertex=(unsigned int)data.vertices.size();
            auto posIt=prim.attributes.find("POSITION");
            if (posIt==prim.attributes.end()) continue;
            const auto& posAcc=model.accessors[posIt->second];
            const auto& posView=model.bufferViews[posAcc.bufferView];
            const float* positions=reinterpret_cast<const float*>(
                model.buffers[posView.buffer].data.data()+posView.byteOffset+posAcc.byteOffset);
            const float* normals=nullptr;
            auto normIt=prim.attributes.find("NORMAL");
            if (normIt!=prim.attributes.end()) {
                const auto& acc=model.accessors[normIt->second];
                const auto& view=model.bufferViews[acc.bufferView];
                normals=reinterpret_cast<const float*>(model.buffers[view.buffer].data.data()+view.byteOffset+acc.byteOffset);
                data.hasNormals=true;
            }
            const float* uvs=nullptr;
            auto uvIt=prim.attributes.find("TEXCOORD_0");
            if (uvIt!=prim.attributes.end()) {
                const auto& acc=model.accessors[uvIt->second];
                const auto& view=model.bufferViews[acc.bufferView];
                uvs=reinterpret_cast<const float*>(model.buffers[view.buffer].data.data()+view.byteOffset+acc.byteOffset);
                data.hasTex=true;
            }
            for (size_t vi=0;vi<posAcc.count;++vi) {
                Vertex v{};
                v.px=positions[vi*3+0]; v.py=positions[vi*3+1]; v.pz=positions[vi*3+2];
                if (normals){v.nx=normals[vi*3+0];v.ny=normals[vi*3+1];v.nz=normals[vi*3+2];}
                if (uvs)    {v.u=uvs[vi*2+0];     v.v=uvs[vi*2+1];}
                data.vertices.push_back(v);
            }
            if (prim.indices>=0) {
                const auto& idxAcc=model.accessors[prim.indices];
                const auto& idxView=model.bufferViews[idxAcc.bufferView];
                const uint8_t* raw=model.buffers[idxView.buffer].data.data()+idxView.byteOffset+idxAcc.byteOffset;
                for (size_t ii=0;ii<idxAcc.count;++ii) {
                    unsigned int idx;
                    if      (idxAcc.componentType==TINYGLTF_COMPONENT_TYPE_UNSIGNED_SHORT) idx=reinterpret_cast<const uint16_t*>(raw)[ii];
                    else if (idxAcc.componentType==TINYGLTF_COMPONENT_TYPE_UNSIGNED_INT)   idx=reinterpret_cast<const uint32_t*>(raw)[ii];
                    else                                                                    idx=reinterpret_cast<const uint8_t*> (raw)[ii];
                    data.indices.push_back(baseVertex+idx);
                }
            } else {
                for (unsigned int ii=baseVertex;ii<(unsigned int)data.vertices.size();++ii) data.indices.push_back(ii);
            }
        }
    }
    return !data.vertices.empty();
}

// ── Flat normals ─────────────────────────────────────────────────────────────
void ModelLoader::generateFlatNormals(ModelData& data) {
    for (size_t i=0;i+2<data.indices.size();i+=3) {
        auto& v0=data.vertices[data.indices[i+0]];
        auto& v1=data.vertices[data.indices[i+1]];
        auto& v2=data.vertices[data.indices[i+2]];
        Vec3 a{v1.px-v0.px,v1.py-v0.py,v1.pz-v0.pz};
        Vec3 b{v2.px-v0.px,v2.py-v0.py,v2.pz-v0.pz};
        Vec3 n=a.cross(b).normalized();
        for (int k=0;k<3;++k) {
            auto& vk=data.vertices[data.indices[i+k]];
            vk.nx=n.x; vk.ny=n.y; vk.nz=n.z;
        }
    }
    data.hasNormals=true;
}

// ── Normalize to [-1,1] sphere + store original bounds ──────────────────────
void ModelLoader::normalizeModel(ModelData& data) {
    if (data.vertices.empty()) return;
    float minX=FLT_MAX,minY=FLT_MAX,minZ=FLT_MAX;
    float maxX=-FLT_MAX,maxY=-FLT_MAX,maxZ=-FLT_MAX;
    for (const auto& v : data.vertices) {
        minX=std::min(minX,v.px); maxX=std::max(maxX,v.px);
        minY=std::min(minY,v.py); maxY=std::max(maxY,v.py);
        minZ=std::min(minZ,v.pz); maxZ=std::max(maxZ,v.pz);
    }
    // Store ORIGINAL sizes in model-file units (before any scaling)
    data.origSizeX = maxX - minX;
    data.origSizeY = maxY - minY;
    data.origSizeZ = maxZ - minZ;

    Vec3 center{(minX+maxX)*0.5f,(minY+maxY)*0.5f,(minZ+maxZ)*0.5f};
    float maxSize=std::max({data.origSizeX,data.origSizeY,data.origSizeZ});
    float invScale=(maxSize>1e-9f)?(2.0f/maxSize):1.0f;

    for (auto& v : data.vertices) {
        v.px=(v.px-center.x)*invScale;
        v.py=(v.py-center.y)*invScale;
        v.pz=(v.pz-center.z)*invScale;
    }
    data.centerOffset   = center;
    data.normalizeScale = invScale;
}
