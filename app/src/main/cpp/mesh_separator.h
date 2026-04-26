#pragma once
// =============================================================================
// mesh_separator.h  —  Ultra-fast parallel mesh island extraction
// =============================================================================
// Algorithm (optimized for 300MB-500MB files, mobile ARMv8):
//
// STEP 1: Parallel edge generation    — nThreads × O(T/n), write-independent
// STEP 2: 4-pass 16-bit radix sort   — O(4N) true linear, 5-10x vs std::sort
// STEP 3: Single-pass UF on faces    — O(T·α(T)) ≈ O(T), path-halving + union-by-size
// STEP 4: Label components            — O(T), flat array, no hash map
// STEP 5: Prefix-sum grouping        — O(T), zero nested vectors
// STEP 6: Stamping-remap reconstruct — O(T), generation trick, no memset
//
// Performance targets (ARMv8, 4 cores):
//   100k tris  →  <50ms
//   1M   tris  →  <400ms
//   5M   tris  →  <2s
//   15M  tris  →  <6s   (500MB OBJ ≈ 10-15M triangles)
// =============================================================================

#include <vector>
#include <cstdint>
#include <atomic>
#include <thread>
#include <functional>
#include "model_loader.h"

// One output component — raw vertex+index buffers, no GPU objects yet
struct MeshComponent {
    std::vector<Vertex>   vertices;
    std::vector<uint32_t> indices;
    uint32_t              faceCount = 0;
};

// Progress callback: called periodically with 0-100
using ProgressFn = std::function<void(int)>;

// =============================================================================
class MeshSeparator {
public:
    // Separate a flat-indexed triangle mesh into disconnected components.
    // safe to call from any thread. Progress calls back on same thread.
    void separate(
        const Vertex*          vertices,
        uint32_t               vertCount,
        const uint32_t*        indices,
        uint32_t               triCount,
        std::vector<MeshComponent>& out,
        const ProgressFn&      progress = nullptr);

    // Global atomic progress 0-100, polled from Java via JNI
    static std::atomic<int> g_progress;

private:
    // ── EdgeEntry: 12 bytes, cache-line friendly ──────────────────────────────
    struct EdgeEntry {
        uint64_t key;   // (min<<32)|max — sort key
        uint32_t face;  // owning triangle
    };

    // ── Persistent buffers (grow-only, survive across calls) ─────────────────
    std::vector<EdgeEntry> m_edges;      // 3 × triCount
    std::vector<EdgeEntry> m_edgeTmp;    // radix sort temp
    std::vector<uint32_t>  m_ufParent;   // union-find parent[face]
    std::vector<uint32_t>  m_ufSize;     // union-find size[face]
    std::vector<uint32_t>  m_compId;     // component id per face
    std::vector<uint32_t>  m_compCount;  // faces per component
    std::vector<uint32_t>  m_compOffset; // prefix-sum start per component
    std::vector<uint32_t>  m_faceOrder;  // faces sorted by component
    std::vector<uint32_t>  m_remap;      // vertex global→local remap
    std::vector<uint32_t>  m_stamp;      // generation stamp per vertex
    uint32_t               m_gen = 0;

    // ── Pre-weld pass (Phase 2) ──────────────────────────────────────────────
    // STL stores 3 unique vertices per triangle, so a naive Union-Find on
    // shared-vertex edges produces 1 component per triangle.  We coalesce
    // bit-equal positions onto a single canonical index BEFORE edge generation.
    std::vector<uint32_t>  m_weldedIdx;  // triCount*3 — indices after spatial weld
    std::vector<uint32_t>  m_weldRemap;  // vertCount  — original→canonical
    void weldVerticesForSeparation(const Vertex* verts,
                                   uint32_t       vertCount,
                                   const uint32_t* idx,
                                   uint32_t       triCount);

    // ── Core steps ────────────────────────────────────────────────────────────
    void genEdgesParallel(const uint32_t* idx, uint32_t triCount, int nthreads);
    void radixSort64(uint32_t n);
    void buildUF(uint32_t triCount);
    uint32_t labelComponents(uint32_t triCount);
    void groupByComponent(uint32_t triCount, uint32_t numComp);
    void reconstructComponents(const Vertex* verts, uint32_t vertCount,
                               const uint32_t* idx, uint32_t numComp,
                               std::vector<MeshComponent>& out, int nthreads);

    // ── Union-Find helpers (inline) ───────────────────────────────────────────
    inline uint32_t uf_find(uint32_t x) noexcept {
        while (m_ufParent[x] != x) {
            m_ufParent[x] = m_ufParent[m_ufParent[x]]; // path halving
            x = m_ufParent[x];
        }
        return x;
    }
    inline void uf_union(uint32_t a, uint32_t b) noexcept {
        a = uf_find(a); b = uf_find(b);
        if (a == b) return;
        if (m_ufSize[a] < m_ufSize[b]) { uint32_t t=a; a=b; b=t; }
        m_ufParent[b]  = a;
        m_ufSize[a]   += m_ufSize[b];
    }
};
