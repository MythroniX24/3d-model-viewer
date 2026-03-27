#pragma once
// =============================================================================
// mesh_separator.h  —  Production-grade mesh island extraction
// =============================================================================
// Algorithm: Sort-based edge adjacency + face-level Union-Find
//
// Data flow:
//   1. Generate one EdgeEntry per triangle edge (3 per tri) into flat array
//   2. Sort edge array by packed 64-bit key  O(E log E), E = 3T
//   3. Linear scan of sorted edges: consecutive equal keys → shared edge → union
//   4. Path-compress UF roots → compact component IDs via prefix-sum
//   5. Distribute face indices into flat per-component arrays (prefix-sum trick)
//   6. Reconstruct each component mesh via stamping remap (no hash map)
//
// Complexity: O(T log T) time, O(T) extra memory
// For 500k tris: ~15M edge sort ops, <200ms on mobile ARMv8
// =============================================================================

#include <vector>
#include <cstdint>
#include <algorithm>
#include <cassert>
#include "model_loader.h"

// One output component — raw vertex+index buffers, no GPU objects yet
struct MeshComponent {
    std::vector<Vertex>       vertices;
    std::vector<uint32_t>     indices;
    uint32_t                  faceCount = 0;
};

// =============================================================================
// MeshSeparator — reusable, preallocates buffers on first call
// =============================================================================
class MeshSeparator {
public:
    // Separate `indices` (flat triangle list) + `vertices` into disconnected
    // components. Results placed in `out` (cleared first).
    // Thread-safe as long as each thread has its own MeshSeparator instance.
    //
    // vertices   — full vertex buffer
    // indices    — flat triangle index list (size = triCount * 3)
    // triCount   — number of triangles
    // out        — output: one MeshComponent per island
    void separate(
        const Vertex*       vertices,
        uint32_t            vertCount,
        const uint32_t*     indices,
        uint32_t            triCount,
        std::vector<MeshComponent>& out);

private:
    // ── Persistent work buffers (reused across calls, never shrink) ──────────
    struct EdgeEntry {
        uint64_t key;      // packed (lo<<32)|hi — sort key
        uint32_t face;     // triangle index this edge belongs to
    };

    std::vector<EdgeEntry> m_edges;       // 3 * triCount entries
    std::vector<uint32_t>  m_ufParent;    // UF parent[face]
    std::vector<uint32_t>  m_ufSize;      // UF size[face]  (union-by-size)
    std::vector<uint32_t>  m_compId;      // compId[face] after labeling
    std::vector<uint32_t>  m_compCount;   // count of faces per component
    std::vector<uint32_t>  m_compOffset;  // prefix-sum offsets into m_faceOrder
    std::vector<uint32_t>  m_faceOrder;   // faces grouped by component
    std::vector<uint32_t>  m_remap;       // vertex remap[globalVi] = localVi
    std::vector<uint32_t>  m_stamp;       // generation stamp per vertex slot

    uint32_t m_generation = 0;            // bumped each component — avoids memset

    // ── Union-Find helpers (inline for performance) ──────────────────────────
    uint32_t uf_find(uint32_t x) {
        // Two-step path halving — fastest on mobile branch predictors
        while (m_ufParent[x] != x) {
            m_ufParent[x] = m_ufParent[m_ufParent[x]];
            x = m_ufParent[x];
        }
        return x;
    }

    void uf_union(uint32_t a, uint32_t b) {
        a = uf_find(a); b = uf_find(b);
        if (a == b) return;
        // Union by size: attach smaller tree under larger
        if (m_ufSize[a] < m_ufSize[b]) { uint32_t t=a; a=b; b=t; }
        m_ufParent[b] = a;
        m_ufSize[a]  += m_ufSize[b];
    }
};
