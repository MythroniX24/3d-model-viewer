package com.modelviewer3d

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    lateinit var glView: ModelGLSurfaceView
    private lateinit var renderer: ModelRenderer
    private lateinit var tvFps: TextView
    private lateinit var tvHint: TextView
    private lateinit var loadingOverlay: View
    private lateinit var tvLoading: TextView

    // ── Ruler state ───────────────────────────────────────────────────────────
    private var rulerPoint1: FloatArray? = null
    private var rulerPoint2: FloatArray? = null
    private lateinit var rulerOverlay: LinearLayout
    private lateinit var tvRulerInfo: TextView
    private lateinit var btnRuler: ImageButton
    private var rulerActive = false

    // ── Pickers ───────────────────────────────────────────────────────────────
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument())
    { uri -> uri?.let { openModelFromUri(it) } }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions())
    { results -> if (results.values.all{it}) launchFilePicker() else toast("Storage permission required") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        glView         = findViewById(R.id.glSurface)
        tvFps          = findViewById(R.id.tvFps)
        tvHint         = findViewById(R.id.tvHint)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        tvLoading      = findViewById(R.id.tvLoading)
        rulerOverlay   = findViewById(R.id.rulerOverlay)
        tvRulerInfo    = findViewById(R.id.tvRulerInfo)
        btnRuler       = findViewById(R.id.btnRuler)

        renderer = ModelRenderer()
        renderer.onFpsUpdate = { fps -> runOnUiThread { tvFps.text = "%.0f FPS".format(fps) } }
        glView.attachRenderer(renderer)

        // Ruler pick callback
        glView.onRulerPick = { pt -> onRulerPointPicked(pt) }

        // Toolbar buttons
        findViewById<View>(R.id.btnOpen).setOnClickListener       { requestOpenFile() }
        findViewById<View>(R.id.btnEdit).setOnClickListener       { openEditor() }
        findViewById<View>(R.id.btnUndo).setOnClickListener       { glView.queueEvent { NativeLib.nativeUndo() } }
        findViewById<View>(R.id.btnRedo).setOnClickListener       { glView.queueEvent { NativeLib.nativeRedo() } }
        findViewById<View>(R.id.btnScreenshot).setOnClickListener { takeScreenshot() }
        findViewById<View>(R.id.btnReset).setOnClickListener      { glView.queueEvent { NativeLib.nativeResetCamera() } }
        btnRuler.setOnClickListener { toggleRulerMode() }

        // Clear ruler button in ruler overlay
        findViewById<View>(R.id.btnClearRuler).setOnClickListener { clearRuler() }

        intent?.data?.let { openModelFromUri(it) }
    }

    override fun onResume()  { super.onResume();  glView.onResume()  }
    override fun onPause()   { super.onPause();   glView.onPause()   }
    override fun onDestroy() { glView.queueEvent { NativeLib.nativeDestroy() }; super.onDestroy() }

    // ── Ruler ─────────────────────────────────────────────────────────────────
    private fun toggleRulerMode() {
        rulerActive = !rulerActive
        glView.mode = if (rulerActive) ModelGLSurfaceView.Mode.RULER else ModelGLSurfaceView.Mode.CAMERA
        btnRuler.alpha = if (rulerActive) 1.0f else 0.5f
        rulerOverlay.visibility = if (rulerActive) View.VISIBLE else View.GONE
        if (!rulerActive) clearRuler()
        else tvRulerInfo.text = "📐 Tap mesh for Point 1"
    }

    private fun onRulerPointPicked(pt: FloatArray) {
        when {
            rulerPoint1 == null -> {
                rulerPoint1 = pt
                tvRulerInfo.text = "✅ P1 set — tap for Point 2"
                glView.queueEvent {
                    NativeLib.nativeSetRulerPoints(true, pt, false, null)
                }
            }
            rulerPoint2 == null -> {
                rulerPoint2 = pt
                val p1 = rulerPoint1!!
                // Euclidean distance in world space
                // Convert to mm using model size info
                val dx = pt[0]-p1[0]; val dy = pt[1]-p1[1]; val dz = pt[2]-p1[2]
                val distWorld = sqrt((dx*dx+dy*dy+dz*dz).toDouble()).toFloat()

                // World units → mm: original model fits in 2 units; origMaxMM = max(origW,H,D)
                // We need to fetch mm/unit ratio: but simpler — use current size
                var wmm=1f; var hmm=1f; var dmm=1f
                val latch = CountDownLatch(1)
                glView.queueEvent {
                    NativeLib.nativeSetRulerPoints(true, p1, true, pt)
                    val s = NativeLib.nativeGetModelSizeMM()
                    wmm=s[3]; hmm=s[4]; dmm=s[5]
                    latch.countDown()
                }
                latch.await()
                // mm per unit: current rendered model fits scaX*2 world units across origWmm mm
                // So 1 world unit = origWmm/(scaX*2) mm — we stored curWmm = scaX*origWmm
                // mmPerUnit = curWmm / 2  (since model spans ~2 units)
                val maxMM = maxOf(wmm,hmm,dmm)
                val mmPerUnit = maxMM / 2.0f
                val distMM = distWorld * mmPerUnit

                tvRulerInfo.text = "📏 Distance: %.2f mm  (%.2f cm)".format(distMM, distMM/10f)
            }
            else -> {
                // Third tap resets
                clearRuler()
                tvRulerInfo.text = "🔄 Reset — tap mesh for Point 1"
            }
        }
    }

    private fun clearRuler() {
        rulerPoint1 = null; rulerPoint2 = null
        glView.queueEvent { NativeLib.nativeClearRuler() }
        if (rulerActive) tvRulerInfo.text = "📐 Tap mesh for Point 1"
    }

    // ── File open ─────────────────────────────────────────────────────────────
    private fun requestOpenFile() {
        if (hasStoragePermission()) launchFilePicker() else requestStoragePermission()
    }
    private fun hasStoragePermission() = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> true
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q        -> true
        else -> ContextCompat.checkSelfPermission(this,Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED
    }
    private fun requestStoragePermission(){
        permLauncher.launch(
            if (Build.VERSION.SDK_INT>=Build.VERSION_CODES.TIRAMISU)
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
    }
    private fun launchFilePicker() { filePicker.launch(arrayOf("*/*")) }

    private fun openModelFromUri(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val name = resolveFileName(uri) ?: "model.obj"
                val dest = File(cacheDir, name)
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(dest).use { input.copyTo(it) }
                }
                withContext(Dispatchers.Main) {
                    tvHint.visibility = View.GONE
                    showLoading("Loading $name…")
                }
                var loadOk = false
                val latch = CountDownLatch(1)
                glView.queueEvent {
                    loadOk = NativeLib.nativeLoadModel(dest.absolutePath)
                    latch.countDown()
                }
                latch.await()
                withContext(Dispatchers.Main) {
                    hideLoading()
                    if (loadOk) toast("✓ $name loaded")
                    else        toast("Failed to load $name")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { hideLoading(); toast("Error: ${e.message}") }
            }
        }
    }

    private fun resolveFileName(uri: Uri): String? {
        if (uri.scheme=="file") return uri.lastPathSegment
        contentResolver.query(uri,null,null,null,null)?.use{c->
            if(c.moveToFirst()){ val i=c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if(i>=0) return c.getString(i) }
        }
        return uri.lastPathSegment
    }

    // ── Loading overlay ───────────────────────────────────────────────────────
    private fun showLoading(msg: String) {
        tvLoading.text = msg
        loadingOverlay.visibility = View.VISIBLE
    }
    private fun hideLoading() {
        loadingOverlay.visibility = View.GONE
    }

    // ── Editor ────────────────────────────────────────────────────────────────
    private fun openEditor() {
        val tag = EditorPanelFragment.TAG
        if (supportFragmentManager.findFragmentByTag(tag)!=null) return
        EditorPanelFragment.newInstance().show(supportFragmentManager, tag)
    }

    // ── Screenshot ────────────────────────────────────────────────────────────
    private fun takeScreenshot() {
        val capW = glView.width; val capH = glView.height
        if (capW==0||capH==0) { toast("No model loaded"); return }
        lifecycleScope.launch {
            var rgba: ByteArray? = null
            val latch = CountDownLatch(1)
            glView.queueEvent { rgba=NativeLib.nativeTakeScreenshot(); latch.countDown() }
            withContext(Dispatchers.IO){ latch.await() }
            val bytes = rgba ?: run { toast("Screenshot failed"); return@launch }
            val bmp = withContext(Dispatchers.Default){
                val argb = IntArray(capW*capH)
                for(i in argb.indices){
                    val b=i*4
                    val r=bytes[b].toInt()and 0xFF; val g=bytes[b+1].toInt()and 0xFF
                    val bl=bytes[b+2].toInt()and 0xFF; val a=bytes[b+3].toInt()and 0xFF
                    argb[i]=(a shl 24)or(r shl 16)or(g shl 8)or bl
                }
                Bitmap.createBitmap(argb,capW,capH,Bitmap.Config.ARGB_8888)
            }
            val saved = withContext(Dispatchers.IO){ saveBitmap(bmp) }
            toast(if(saved!=null)"Saved: ${saved.name}" else "Could not save")
        }
    }
    private fun saveBitmap(bmp: Bitmap): File? = try {
        val dir = if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q)
            File(getExternalFilesDir(Environment.DIRECTORY_PICTURES),"3DViewer")
        else File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),"3DViewer")
        dir.mkdirs()
        val ts=SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(Date())
        val f=File(dir,"3DViewer_$ts.png")
        FileOutputStream(f).use{bmp.compress(Bitmap.CompressFormat.PNG,100,it)}
        MediaScannerConnection.scanFile(this,arrayOf(f.absolutePath),null,null); f
    } catch(e:Exception){null}

    private fun toast(msg: String) = Toast.makeText(this,msg,Toast.LENGTH_SHORT).show()
}
