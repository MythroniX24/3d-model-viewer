package com.modelviewer3d

import android.Manifest
import android.content.Intent
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
import androidx.core.content.FileProvider
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

    private var tvFps:          TextView?    = null
    private var tvHint:         View?        = null
    private var loadingOverlay: View?        = null
    private var tvLoading:      TextView?    = null
    private var rulerOverlay:   View?        = null
    private var tvRulerInfo:    TextView?    = null
    private var btnRuler:       ImageButton? = null

    // Track last loaded file so we can re-upload after GL context loss
    private var lastLoadedFilePath: String? = null

    private var rulerPoint1: FloatArray? = null
    private var rulerPoint2: FloatArray? = null
    private var rulerActive = false

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument())
    { uri -> uri?.let { openModelFromUri(it) } }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions())
    { r -> if (r.values.all { it }) launchFilePicker() else toast("Storage permission required") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        try {
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
            renderer.onFpsUpdate = { fps ->
                runOnUiThread { tvFps?.text = "%.0f".format(fps) }
            }
            renderer.onContextLost = {
                // GL context was recreated (e.g. app came back from background)
                // Re-upload the last model if we have one
                val path = lastLoadedFilePath
                if (path != null) {
                    reloadModelFromPath(path)
                }
            }
            glView.attachRenderer(renderer)
            glView.onRulerPick = { pt -> onRulerPointPicked(pt) }

            // Toolbar wiring
            findViewById<View>(R.id.btnOpen).setOnClickListener       { requestOpenFile() }
            findViewById<View>(R.id.btnEdit).setOnClickListener       { openEditor() }
            findViewById<View>(R.id.btnMeshList).setOnClickListener   { openMeshList() }
            btnRuler?.setOnClickListener                               { toggleRulerMode() }
            findViewById<View>(R.id.btnExport).setOnClickListener     { showExportMenu() }
            findViewById<View>(R.id.btnUndo).setOnClickListener       { glView.queueEvent { NativeLib.nativeUndo() } }
            findViewById<View>(R.id.btnRedo).setOnClickListener       { glView.queueEvent { NativeLib.nativeRedo() } }
            findViewById<View>(R.id.btnScreenshot).setOnClickListener { takeScreenshot() }
            findViewById<View>(R.id.btnReset).setOnClickListener      { glView.queueEvent { NativeLib.nativeResetCamera() } }
            findViewById<View?>(R.id.btnClearRuler)?.setOnClickListener { clearRuler() }

            intent?.data?.let { openModelFromUri(it) }
        } catch (e: Exception) {
            toast("Init error: ${e.message}")
        }
    }

    override fun onResume()  { super.onResume();  glView.onResume()  }
    override fun onPause()   { super.onPause();   glView.onPause()   }
    override fun onDestroy() { glView.queueEvent { NativeLib.nativeDestroy() }; super.onDestroy() }

    // ── Ruler ─────────────────────────────────────────────────────────────────
    private fun toggleRulerMode() {
        rulerActive = !rulerActive
        glView.mode = if (rulerActive) ModelGLSurfaceView.Mode.RULER else ModelGLSurfaceView.Mode.CAMERA
        btnRuler?.alpha = if (rulerActive) 1.0f else 0.45f
        rulerOverlay?.visibility = if (rulerActive) View.VISIBLE else View.GONE
        if (!rulerActive) clearRuler()
        else tvRulerInfo?.text = "Tap mesh surface — Point 1"
    }

    private fun onRulerPointPicked(pt: FloatArray) {
        when {
            rulerPoint1 == null -> {
                rulerPoint1 = pt.copyOf()
                tvRulerInfo?.text = "✅ P1 set — tap for Point 2"
                val p1c = pt.copyOf()
                glView.queueEvent { NativeLib.nativeSetRulerPoints(true, p1c, false, null) }
            }
            rulerPoint2 == null -> {
                rulerPoint2 = pt.copyOf()
                val p1 = rulerPoint1!!.copyOf()
                val p2 = pt.copyOf()
                glView.queueEvent { NativeLib.nativeSetRulerPoints(true, p1, true, p2) }

                lifecycleScope.launch(Dispatchers.Default) {
                    val dx=p2[0]-p1[0]; val dy=p2[1]-p1[1]; val dz=p2[2]-p1[2]
                    val distW = sqrt((dx*dx+dy*dy+dz*dz).toDouble()).toFloat()
                    var maxMM = 100f
                    val latch = CountDownLatch(1)
                    glView.queueEvent {
                        try { val s=NativeLib.nativeGetModelSizeMM(); maxMM=maxOf(s[3],s[4],s[5]) }
                        catch(_:Exception){}
                        latch.countDown()
                    }
                    withContext(Dispatchers.IO) { latch.await() }
                    val distMM = distW * (if (maxMM>0.001f) maxMM/2f else 1f)
                    withContext(Dispatchers.Main) {
                        tvRulerInfo?.text = "📏 %.2f mm  (%.2f cm)".format(distMM, distMM/10f)
                    }
                }
            }
            else -> clearRuler()
        }
    }

    private fun clearRuler() {
        rulerPoint1=null; rulerPoint2=null
        glView.queueEvent { NativeLib.nativeClearRuler() }
        if (rulerActive) tvRulerInfo?.text = "Tap mesh surface — Point 1"
    }

    // ── Export ────────────────────────────────────────────────────────────────
    private fun showExportMenu() {
        val items = arrayOf("Export as OBJ", "Export as STL", "Share OBJ", "Share STL")
        android.app.AlertDialog.Builder(this)
            .setTitle("Export / Share")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> exportModel("obj", share = false)
                    1 -> exportModel("stl", share = false)
                    2 -> exportModel("obj", share = true)
                    3 -> exportModel("stl", share = true)
                }
            }
            .show()
    }

    private fun exportModel(format: String, share: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val fileName = "model_$ts.$format"
                val outDir = if (share) cacheDir
                    else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                        File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "3DViewer")
                    else File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "3DViewer")
                outDir.mkdirs()
                val outFile = File(outDir, fileName)

                var ok = false
                val latch = CountDownLatch(1)
                glView.queueEvent {
                    try {
                        ok = when (format) {
                            "obj" -> NativeLib.nativeExportOBJ(outFile.absolutePath)
                            "stl" -> NativeLib.nativeExportSTL(outFile.absolutePath)
                            else  -> false
                        }
                    } catch (_: Exception) {}
                    latch.countDown()
                }
                latch.await()

                withContext(Dispatchers.Main) {
                    if (!ok) { toast("Export failed"); return@withContext }
                    if (share) {
                        val uri = FileProvider.getUriForFile(
                            this@MainActivity, "$packageName.fileprovider", outFile)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = if (format=="obj") "text/plain" else "application/octet-stream"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(intent, "Share 3D Model"))
                    } else {
                        MediaScannerConnection.scanFile(this@MainActivity, arrayOf(outFile.absolutePath), null, null)
                        toast("Saved: ${outFile.name}")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("Error: ${e.message}") }
            }
        }
    }

    // ── File open ─────────────────────────────────────────────────────────────
    private fun requestOpenFile() {
        if (hasStoragePermission()) launchFilePicker() else requestStoragePermission()
    }
    private fun hasStoragePermission() = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> true
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q        -> true
        else -> ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
    private fun requestStoragePermission() {
        permLauncher.launch(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
    }
    private fun launchFilePicker() { filePicker.launch(arrayOf("*/*")) }

    private fun openModelFromUri(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val name = resolveFileName(uri) ?: "model.obj"
                val dest = File(cacheDir, name)

                // Copy URI → local cache file
                val inputStream = contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    withContext(Dispatchers.Main) {
                        toast("Cannot open file — permission denied?")
                    }
                    return@launch
                }
                inputStream.use { inp ->
                    FileOutputStream(dest).use { out -> inp.copyTo(out) }
                }

                withContext(Dispatchers.Main) {
                    tvHint?.visibility = View.GONE
                    showLoading("Parsing $name…")
                }

                // ── Step 1: Parse on IO thread (heavy CPU work, no GL needed) ──
                val parseOk = try {
                    NativeLib.nativeParseModel(dest.absolutePath)
                } catch (e: Exception) {
                    false
                }

                if (!parseOk) {
                    withContext(Dispatchers.Main) {
                        hideLoading()
                        toast("Failed to parse $name — unsupported format?")
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) { showLoading("Uploading to GPU…") }

                // ── Step 2: Upload on GL thread (fast — just buffer uploads) ──
                // Use timeout to prevent infinite hang if GL thread is paused/unavailable
                var uploadOk = false
                val latch = java.util.concurrent.CountDownLatch(1)
                glView.queueEvent {
                    try { uploadOk = NativeLib.nativeUploadParsed() }
                    catch (e: Exception) { uploadOk = false }
                    finally { latch.countDown() }  // always release, even on exception
                }
                // Wait max 10 seconds — avoids infinite loading spinner
                val completed = latch.await(10, java.util.concurrent.TimeUnit.SECONDS)

                withContext(Dispatchers.Main) {
                    hideLoading()
                    when {
                        !completed -> toast("GPU upload timed out — please try again")
                        uploadOk   -> {
                            lastLoadedFilePath = dest.absolutePath
                            toast("✓ $name loaded")
                        }
                        else       -> toast("GPU upload failed")
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    hideLoading()
                    toast("Error: ${e.message}")
                }
            }
        }
    }
    // Re-upload a previously cached model file after GL context loss
    private fun reloadModelFromPath(path: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val parseOk = try { NativeLib.nativeParseModel(path) } catch (e: Exception) { false }
            if (!parseOk) return@launch
            val latch = java.util.concurrent.CountDownLatch(1)
            var uploadOk = false
            glView.queueEvent {
                try { uploadOk = NativeLib.nativeUploadParsed() }
                catch (e: Exception) { uploadOk = false }
                finally { latch.countDown() }
            }
            latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    private fun resolveFileName(uri: Uri): String? {
        if (uri.scheme=="file") return uri.lastPathSegment
        contentResolver.query(uri,null,null,null,null)?.use { c ->
            if (c.moveToFirst()) { val i=c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if(i>=0) return c.getString(i) }
        }
        return uri.lastPathSegment
    }

    // ── Panels ────────────────────────────────────────────────────────────────
    private fun openEditor() {
        if (supportFragmentManager.findFragmentByTag(EditorPanelFragment.TAG)!=null) return
        EditorPanelFragment.newInstance().show(supportFragmentManager, EditorPanelFragment.TAG)
    }
    private fun openMeshList() {
        if (supportFragmentManager.findFragmentByTag(MeshListFragment.TAG)!=null) return
        MeshListFragment.newInstance().show(supportFragmentManager, MeshListFragment.TAG)
    }

    // ── Loading ───────────────────────────────────────────────────────────────
    private fun showLoading(msg: String) { tvLoading?.text=msg; loadingOverlay?.visibility=View.VISIBLE }
    private fun hideLoading()            { loadingOverlay?.visibility=View.GONE }

    // ── Screenshot ────────────────────────────────────────────────────────────
    private fun takeScreenshot() {
        val capW=glView.width; val capH=glView.height
        if (capW==0||capH==0) { toast("No model loaded"); return }
        lifecycleScope.launch {
            var rgba: ByteArray?=null; val latch=CountDownLatch(1)
            glView.queueEvent { try{rgba=NativeLib.nativeTakeScreenshot()}catch(_:Exception){}; latch.countDown() }
            withContext(Dispatchers.IO) { latch.await() }
            val bytes=rgba ?: run { toast("Screenshot failed"); return@launch }
            val bmp=withContext(Dispatchers.Default){
                val argb=IntArray(capW*capH)
                for(i in argb.indices){ val b=i*4
                    argb[i]=(bytes[b+3].toInt()and 0xFF shl 24)or(bytes[b].toInt()and 0xFF shl 16)or
                             (bytes[b+1].toInt()and 0xFF shl 8)or(bytes[b+2].toInt()and 0xFF)
                }
                Bitmap.createBitmap(argb,capW,capH,Bitmap.Config.ARGB_8888)
            }
            val saved=withContext(Dispatchers.IO){ saveBitmap(bmp) }
            toast(if(saved!=null)"Saved: ${saved.name}" else "Could not save")
        }
    }
    private fun saveBitmap(bmp: Bitmap): File? = try {
        val dir=if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q)
            File(getExternalFilesDir(Environment.DIRECTORY_PICTURES),"3DViewer")
        else File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),"3DViewer")
        dir.mkdirs()
        val ts=SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(Date())
        val f=File(dir,"3DViewer_$ts.png")
        FileOutputStream(f).use{bmp.compress(Bitmap.CompressFormat.PNG,100,it)}
        MediaScannerConnection.scanFile(this,arrayOf(f.absolutePath),null,null); f
    } catch(_:Exception){null}

    private fun toast(msg: String)=Toast.makeText(this,msg,Toast.LENGTH_SHORT).show()
}
