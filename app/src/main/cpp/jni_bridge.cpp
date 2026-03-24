#include <jni.h>
#include <android/log.h>
#include <memory>
#include <cstring>
#include <cmath>
#include "renderer.h"

#define TAG "JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG,__VA_ARGS__)

static std::unique_ptr<Renderer> g_renderer;

static std::string jstr(JNIEnv* env, jstring js){
    if(!js) return "";
    const char* c=env->GetStringUTFChars(js,nullptr);
    std::string s(c?c:""); env->ReleaseStringUTFChars(js,c); return s;
}

extern "C" {

// Lifecycle
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeInit(JNIEnv*,jclass,jint w,jint h){
    if(!g_renderer) g_renderer=std::make_unique<Renderer>();
    g_renderer->init((int)w,(int)h);
}
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeResize(JNIEnv*,jclass,jint w,jint h){
    if(g_renderer) g_renderer->resize((int)w,(int)h);
}
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeDraw(JNIEnv*,jclass){
    if(g_renderer) g_renderer->draw();
}
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeDestroy(JNIEnv*,jclass){
    g_renderer.reset();
}

// Model
JNIEXPORT jboolean JNICALL Java_com_modelviewer3d_NativeLib_nativeLoadModel(JNIEnv* env,jclass,jstring path){
    if(!g_renderer) return JNI_FALSE;
    return g_renderer->loadModel(jstr(env,path)) ? JNI_TRUE : JNI_FALSE;
}

// Camera
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeTouchRotate(JNIEnv*,jclass,jfloat dx,jfloat dy) { if(g_renderer)g_renderer->touchRotate(dx,dy); }
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeTouchZoom  (JNIEnv*,jclass,jfloat f)            { if(g_renderer)g_renderer->touchZoom(f); }
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeTouchPan   (JNIEnv*,jclass,jfloat dx,jfloat dy) { if(g_renderer)g_renderer->touchPan(dx,dy); }
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeResetCamera(JNIEnv*,jclass)                      { if(g_renderer)g_renderer->resetCamera(); }

// Transform
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeSetRotation   (JNIEnv*,jclass,jfloat x,jfloat y,jfloat z){ if(g_renderer){g_renderer->pushUndoState();g_renderer->setRotation(x,y,z);} }
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeSetTranslation(JNIEnv*,jclass,jfloat x,jfloat y,jfloat z){ if(g_renderer){g_renderer->pushUndoState();g_renderer->setTranslation(x,y,z);} }
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeSetScaleMM    (JNIEnv*,jclass,jfloat w,jfloat h,jfloat d){ if(g_renderer){g_renderer->pushUndoState();g_renderer->setScaleMM(w,h,d);} }
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeMirrorX(JNIEnv*,jclass){ if(g_renderer){g_renderer->pushUndoState();g_renderer->mirrorX();} }
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeMirrorY(JNIEnv*,jclass){ if(g_renderer){g_renderer->pushUndoState();g_renderer->mirrorY();} }
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeMirrorZ(JNIEnv*,jclass){ if(g_renderer){g_renderer->pushUndoState();g_renderer->mirrorZ();} }
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeResetTransform(JNIEnv*,jclass){ if(g_renderer){g_renderer->pushUndoState();g_renderer->resetTransform();} }

// Visual
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeSetColor      (JNIEnv*,jclass,jfloat r,jfloat g,jfloat b){ if(g_renderer)g_renderer->setColor(r,g,b); }
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeSetAmbient    (JNIEnv*,jclass,jfloat v){ if(g_renderer)g_renderer->setAmbient(v); }
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeSetDiffuse    (JNIEnv*,jclass,jfloat v){ if(g_renderer)g_renderer->setDiffuse(v); }
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeSetWireframe  (JNIEnv*,jclass,jboolean on){ if(g_renderer)g_renderer->setWireframe(on==JNI_TRUE); }
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeSetBoundingBox(JNIEnv*,jclass,jboolean on){ if(g_renderer)g_renderer->setShowBoundingBox(on==JNI_TRUE); }

// Undo/Redo
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeUndo(JNIEnv*,jclass){ if(g_renderer)g_renderer->undo(); }
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeRedo(JNIEnv*,jclass){ if(g_renderer)g_renderer->redo(); }

// Stats
JNIEXPORT jfloat JNICALL Java_com_modelviewer3d_NativeLib_nativeGetFPS(JNIEnv*,jclass){
    return g_renderer?(jfloat)g_renderer->getFPS():0.0f;
}

// Model size in mm: returns float[6] = {origW,origH,origD, curW,curH,curD}
JNIEXPORT jfloatArray JNICALL Java_com_modelviewer3d_NativeLib_nativeGetModelSizeMM(JNIEnv* env,jclass){
    float data[6]={1,1,1,1,1,1};
    if(g_renderer){
        g_renderer->getModelSizeMM(data[0],data[1],data[2]);
        g_renderer->getCurrentSizeMM(data[3],data[4],data[5]);
    }
    jfloatArray arr=env->NewFloatArray(6);
    env->SetFloatArrayRegion(arr,0,6,data);
    return arr;
}

// Ruler: pick a point by screen tap
// Returns float[3] world position, or null if no hit
JNIEXPORT jfloatArray JNICALL Java_com_modelviewer3d_NativeLib_nativePickPoint(
        JNIEnv* env, jclass, jfloat sx, jfloat sy, jfloat sw, jfloat sh){
    if (!g_renderer) return nullptr;
    float pt[3];
    if (!g_renderer->pickPoint(sx,sy,sw,sh,pt)) return nullptr;
    jfloatArray arr=env->NewFloatArray(3);
    env->SetFloatArrayRegion(arr,0,3,pt);
    return arr;
}

// Tell renderer which ruler points to draw
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeSetRulerPoints(
        JNIEnv* env, jclass,
        jboolean hasP1, jfloatArray p1,
        jboolean hasP2, jfloatArray p2){
    if(!g_renderer) return;
    float arr1[3]={}, arr2[3]={};
    if(hasP1==JNI_TRUE && p1) env->GetFloatArrayRegion(p1,0,3,arr1);
    if(hasP2==JNI_TRUE && p2) env->GetFloatArrayRegion(p2,0,3,arr2);
    g_renderer->setRulerPoints(hasP1==JNI_TRUE,arr1,hasP2==JNI_TRUE,arr2);
}

JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeClearRuler(JNIEnv*,jclass){
    if(g_renderer) g_renderer->clearRuler();
}

// Screenshot
JNIEXPORT jbyteArray JNICALL Java_com_modelviewer3d_NativeLib_nativeTakeScreenshot(JNIEnv* env,jclass){
    if(!g_renderer) return nullptr;
    auto pixels=g_renderer->takeScreenshot();
    if(pixels.empty()) return nullptr;
    jbyteArray arr=env->NewByteArray((jsize)pixels.size());
    env->SetByteArrayRegion(arr,0,(jsize)pixels.size(),reinterpret_cast<const jbyte*>(pixels.data()));
    return arr;
}

} // extern "C"
