# 3D Studio — Enhanced 3D Model Viewer & Editor

A professional Android 3D model viewer and editor built with OpenGL ES 3.0 and Kotlin.

## New Features (v2.0)

### 🔷 Mesh Separation
- Auto-separates disconnected geometry into named mesh islands via Union-Find algorithm (C++)
- Tap any mesh row to select and highlight it in the viewport (cyan tint)
- **Eye icon** to toggle per-mesh visibility on/off
- **Resize individual meshes** independently — W/H/D in mm with optional lock-ratio
- **Delete individual meshes** with confirmation dialog
- Vertex count shown per mesh island

### 📤 Export & Share
- **OBJ** and **STL** format export
- **Save to device** — writes to Documents/3DViewer/
- **Share via any app** — system share sheet
- **Direct share** to WhatsApp, Telegram, Email, Google Drive

### 📊 Status Bar
- Live mesh count, total vertex count, and filename shown at the bottom

### 🎨 Enhanced UI / UX
- Deep dark theme — `#090910` base, `#00D4FF` cyan accent
- Rounded card-based bottom sheets with glassmorphism hints
- Colored slider tracks (Red/Green/Blue channels, lighting sliders)
- Per-mesh color coding in the mesh list
- Smooth loading states with descriptive sub-text
- Pill badges for format chips

## Supported Formats
| Format | Load | Export |
|--------|------|--------|
| OBJ    | ✅   | ✅     |
| STL    | ✅   | ✅     |
| GLB    | ✅   | —      |

## Build
```bash
./gradlew assembleDebug
```
Requires NDK r25+ and CMake 3.22+.
