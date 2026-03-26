#pragma once
#include <cmath>
#include <cstring>
#include <algorithm>

static constexpr float PI = 3.14159265358979323846f;
static constexpr float DEG2RAD = PI / 180.0f;
static constexpr float RAD2DEG = 180.0f / PI;

// ── Vec3 ─────────────────────────────────────────────────────────────────────
struct Vec3 {
    float x = 0, y = 0, z = 0;
    Vec3() = default;
    Vec3(float x, float y, float z) : x(x), y(y), z(z) {}

    Vec3 operator+(const Vec3& o) const { return {x+o.x, y+o.y, z+o.z}; }
    Vec3 operator-(const Vec3& o) const { return {x-o.x, y-o.y, z-o.z}; }
    Vec3 operator*(float s)        const { return {x*s, y*s, z*s}; }
    Vec3 operator-()               const { return {-x, -y, -z}; }
    Vec3& operator+=(const Vec3& o){ x+=o.x; y+=o.y; z+=o.z; return *this; }
    Vec3& operator/=(float s)      { x/=s; y/=s; z/=s; return *this; }

    float dot(const Vec3& o)  const { return x*o.x + y*o.y + z*o.z; }
    Vec3  cross(const Vec3& o) const {
        return { y*o.z - z*o.y, z*o.x - x*o.z, x*o.y - y*o.x };
    }
    float length() const { return std::sqrt(x*x + y*y + z*z); }
    Vec3 normalized() const {
        float l = length();
        if (l < 1e-9f) return {0,0,0};
        return {x/l, y/l, z/l};
    }
};

// ── Mat4 (column-major, matching OpenGL) ─────────────────────────────────────
struct Mat4 {
    float m[16];   // col0, col1, col2, col3

    Mat4() { identity(); }

    void identity() {
        memset(m, 0, sizeof(m));
        m[0] = m[5] = m[10] = m[15] = 1.0f;
    }

    // Mat4 * Mat4
    Mat4 operator*(const Mat4& r) const {
        Mat4 out;
        for (int col = 0; col < 4; ++col)
            for (int row = 0; row < 4; ++row) {
                float sum = 0;
                for (int k = 0; k < 4; ++k)
                    sum += m[k*4 + row] * r.m[col*4 + k];
                out.m[col*4 + row] = sum;
            }
        return out;
    }

    // Static constructors ────────────────────────────────────────────────────

    static Mat4 translation(float tx, float ty, float tz) {
        Mat4 t; // identity
        t.m[12] = tx; t.m[13] = ty; t.m[14] = tz;
        return t;
    }

    static Mat4 scale(float sx, float sy, float sz) {
        Mat4 s;
        s.m[0] = sx; s.m[5] = sy; s.m[10] = sz;
        return s;
    }

    static Mat4 rotationX(float rad) {
        Mat4 r;
        float c = cosf(rad), s = sinf(rad);
        r.m[5] =  c; r.m[9]  = -s;
        r.m[6] =  s; r.m[10] =  c;
        return r;
    }
    static Mat4 rotationY(float rad) {
        Mat4 r;
        float c = cosf(rad), s = sinf(rad);
        r.m[0] =  c; r.m[8]  =  s;
        r.m[2] = -s; r.m[10] =  c;
        return r;
    }
    static Mat4 rotationZ(float rad) {
        Mat4 r;
        float c = cosf(rad), s = sinf(rad);
        r.m[0] =  c; r.m[4] = -s;
        r.m[1] =  s; r.m[5] =  c;
        return r;
    }

    // Perspective projection
    static Mat4 perspective(float fovY_rad, float aspect, float near, float far) {
        Mat4 p; memset(p.m, 0, sizeof(p.m));
        float f = 1.0f / tanf(fovY_rad * 0.5f);
        p.m[0]  = f / aspect;
        p.m[5]  = f;
        p.m[10] = (far + near) / (near - far);
        p.m[11] = -1.0f;
        p.m[14] = (2.0f * far * near) / (near - far);
        return p;
    }

    // LookAt view matrix
    static Mat4 lookAt(Vec3 eye, Vec3 center, Vec3 up) {
        Vec3 f = (center - eye).normalized();
        Vec3 s = f.cross(up).normalized();
        Vec3 u = s.cross(f);
        Mat4 v;
        v.m[0]  =  s.x; v.m[4]  =  s.y; v.m[8]  =  s.z;
        v.m[1]  =  u.x; v.m[5]  =  u.y; v.m[9]  =  u.z;
        v.m[2]  = -f.x; v.m[6]  = -f.y; v.m[10] = -f.z;
        v.m[12] = -s.dot(eye);
        v.m[13] = -u.dot(eye);
        v.m[14] =  f.dot(eye);
        v.m[15] =  1.0f;
        return v;
    }

    // Normal matrix (upper-left 3x3 of transpose(inverse(model)))
    // Returns a float[9] for upload as mat3 uniform
    void toNormalMatrix(float out[9]) const {
        // Simplified: extract rotation part (assumes uniform scale)
        out[0] = m[0]; out[1] = m[1]; out[2] = m[2];
        out[3] = m[4]; out[4] = m[5]; out[5] = m[6];
        out[6] = m[8]; out[7] = m[9]; out[8] = m[10];
    }
};
