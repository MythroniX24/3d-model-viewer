# 3D Model Viewer + Editor

A mobile-friendly 3D model viewer and editor for Android built with **Kotlin** (UI) and **C++ / OpenGL ES 3.0** (rendering engine via NDK).

---

## ✨ Features

| Category | Details |
|---|---|
| **Formats** | OBJ · STL (ASCII + Binary) · GLB |
| **Touch** | Orbit (1-finger) · Zoom (pinch) · Pan (2-finger) · Double-tap reset |
| **Transform** | Rotate / Translate / Scale on X Y Z axes |
| **Geometry** | Mirror X/Y/Z · Uniform scale lock · Reset |
| **Visual** | RGB color picker · Ambient & Diffuse lighting sliders |
| **Display** | Wireframe mode · Bounding box overlay |
| **History** | Undo / Redo (50 steps) |
| **Export** | Screenshot → PNG saved to Pictures/3DViewer |
| **Performance** | OpenGL ES 3.0 · Phong shading · 30–60 FPS on low-end devices |

---

## 🏗️ Project Structure

```
3d-model-viewer/
├── app/src/main/
│   ├── cpp/                  ← C++ rendering engine (NDK)
│   │   ├── CMakeLists.txt
│   │   ├── math_utils.h      ← Vec3 / Mat4 math
│   │   ├── shader_utils.*    ← GLSL compile/link helpers
│   │   ├── model_loader.*    ← OBJ / STL / GLB parsers
│   │   ├── renderer.*        ← OpenGL ES 3.0 renderer
│   │   └── jni_bridge.cpp    ← Kotlin ↔ C++ JNI bridge
│   ├── java/com/modelviewer3d/
│   │   ├── NativeLib.kt      ← JNI declarations
│   │   ├── ModelGLSurfaceView.kt
│   │   ├── ModelRenderer.kt
│   │   ├── EditorPanelFragment.kt
│   │   └── MainActivity.kt
│   └── res/                  ← Layouts, icons, themes
└── .github/workflows/
    └── android.yml           ← Auto-build APK on push
```

---

## 🚀 Build APK via GitHub Actions (No Android Studio needed)

### Step 1 — Push to GitHub

```bash
git init
git add .
git commit -m "Initial commit: 3D Model Viewer"
git branch -M main
git remote add origin https://github.com/YOUR_USERNAME/3d-model-viewer.git
git push -u origin main
```

### Step 2 — GitHub Actions builds automatically

After push, go to your repo → **Actions** tab → **Build Android APK** workflow → wait ~8–12 min.

### Step 3 — Download APK

1. Click the completed workflow run
2. Scroll to **Artifacts** section at the bottom
3. Download `3DModelViewer-debug` → extract → install `.apk` on your phone

> **Install tip:** On your Android phone go to *Settings → Install unknown apps* and enable for your file manager.

### Tag-based Release

Push a version tag to create a GitHub Release with APKs attached automatically:

```bash
git tag v1.0.0
git push origin v1.0.0
```

---

## 🔧 Local Build (Optional — requires Android Studio)

1. Open project in Android Studio Hedgehog or newer
2. SDK 34, NDK 27.x, CMake 3.22.1 must be installed
3. Run → device or emulator with OpenGL ES 3.0 support

---

## 📱 Minimum Requirements

| Item | Requirement |
|---|---|
| Android | 7.0 (API 24) |
| GPU | OpenGL ES 3.0 |
| RAM | 512 MB free |
| Storage | ~15 MB for app |

---

## 🧩 Third-party Libraries (fetched automatically by CMake)

| Library | Version | Use |
|---|---|---|
| [tinyobjloader](https://github.com/tinyobjloader/tinyobjloader) | v2.0.0rc13 | OBJ parsing |
| [tinygltf](https://github.com/syoyo/tinygltf) | v2.8.21 | GLB/GLTF parsing |
| stb_image | bundled with tinygltf | Texture loading |
