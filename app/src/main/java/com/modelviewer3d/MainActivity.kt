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
import android.content.BroadcastReceiver
import android.content.IntentFilter
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
import java.nio.channels.Channels
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    lateinit var glView: ModelGLSurfaceView
    private lateinit var renderer: ModelRenderer

    private var tvFps: TextView? = null
    private var tvHint: View? = null
    private var loadingOverlay: View? = null
    private var tvLoading: TextView? = null
    private var tvLoadingDetail: TextView? = null
    private var rulerOverlay: View? = null
    private var tvRulerInfo: TextView? = null
    private var btnRuler: View? = null
    private var statusBar: View? = null
    private var tvStatusMeshes: TextView? = null
    private var tvStatusVerts: TextView? = null
    private var tvStatusFile: TextView? = null

    private var rulerPoint1: FloatArray? = null
    private var rulerPoint2: FloatArray? = null
    private var rulerActive = false
    private var currentFileName = ""

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument())
    { uri -> uri?.let { openModelFromUri(it) } }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions())
    { r -> if (r.values.all { it }) launchFilePicker() else toast("Storage permission required") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = 0x00000000

        try {
            setContentView(R.layout.activity_main)

            glView           = findViewById(R.id.glSurface)
            tvFps            = findViewById(R.id.tvFps)
            tvHint           = findViewById(R.id.tvHint)
            loadingOverlay   = findViewById(R.id.loadingOverlay)
            tvLoading        = findViewById(R.id.tvLoading)
            tvLoadingDetail  = findViewById(R.id.tvLoadingDetail)
            rulerOverlay     = findViewById(R.id.rulerOverlay)
            tvRulerInfo      = findViewById(R.id.tvRulerInfo)
            btnRuler         = findViewById(R.id.btnRuler)
            statusBar        = findViewById(R.id.statusBar)
            tvStatusMeshes   = findViewById(R.id.tvStatusMeshes)
            tvStatusVerts    = findViewById(R.id.tvStatusVerts)
            tvStatusFile     = findViewById(R.id.tvStatusFile)

            renderer = ModelRenderer()
            renderer.onFpsUpdate = { fps ->
                runOnUiThread { tvFps?.text = "%.0f".format(fps) }
            }
            glView.attachRenderer(renderer)
            glView.onRulerPick = { pt -> onRulerPointPicked(pt) }

            // Toolbar wiring
            findViewById<View>(R.id.btnOpen).setOnClickListener       { requestOpenFile() }
            findViewById<View>(R.id.btnEdit).setOnClickListener       { openEditor() }
            findViewById<View>(R.id.btnMeshList).setOnClickListener   { openMeshList() }
            btnRuler?.setOnClickListener                               { toggleRulerMode() }
            findViewById<View>(R.id.btnExport).setOnClickListener     { showExportSheet() }
            findViewById<View>(R.id.btnUndo).setOnClickListener       { glView.queueEvent { NativeLib.nativeUndo() } }
            findViewById<View>(R.id.btnRedo).setOnClickListener       { glView.queueEvent { NativeLib.nativeRedo() } }
            findViewById<View>(R.id.btnScreenshot).setOnClickListener { takeScreenshot() }
            findViewById<View>(R.id.btnReset).setOnClickListener      { glView.queueEvent { NativeLib.nativeResetCamera() } }
            findViewById<View?>(R.id.btnClearRuler)?.setOnClickListener { clearRuler() }

            // Also wire the "Open Model" button inside the hint card
            findViewById<android.view.View?>(R.id.btnOpenHint)?.setOnClickListener { requestOpenFile() }

            // Register receiver for separation CPU-done signal (GPU upload needed on GL thread)
            val sepFilter = IntentFilter(SeparationService.ACTION_SEPARATION_CPU_DONE)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(separationCpuDoneReceiver, sepFilter, android.content.Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(separationCpuDoneReceiver, sepFilter)
            }

            intent?.data?.let { openModelFromUri(it) }
        } catch (e: Exception) {
            toast("Init error: ${e.message}")
        }
    }

    override fun onResume()  { super.onResume();  glView.onResume()  }
    override fun onPause()   { super.onPause();   glView.onPause()   }
    override fun onDestroy() {
        try { unregisterReceiver(separationCpuDoneReceiver) } catch (_: Exception) {}
        glView.queueEvent { NativeLib.nativeDestroy() }
        super.onDestroy()
    }

    // ── Separation GPU upload (triggered by SeparationService broadcast) ───────
    private val separationCpuDoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: android.content.Context, intent: android.content.Intent) {
            if (intent.action != SeparationService.ACTION_SEPARATION_CPU_DONE) return
            // GPU upload must happen on GL thread
            glView.queueEvent {
                val ok = NativeLib.nativePerformSeparationGPU()
                val mc = NativeLib.nativeGetMeshCount()
                runOnUiThread {
                    if (ok) {
                        updateStatusBar()
                        // Notify service that GPU upload is done
                        sendBroadcast(android.content.Intent(SeparationService.ACTION_SEPARATION_COMPLETE))
                    } else {
                        sendBroadcast(android.content.Intent(SeparationService.ACTION_SEPARATION_FAILED))
                    }
                }
            }
        }
    }

    // ── Status Bar ────────────────────────────────────────────────────────────
    fun updateStatusBar() {
        glView.queueEvent {
            val meshCount = NativeLib.nativeGetMeshCount()
            var totalVerts = 0
            for (i in 0 until meshCount) totalVerts += NativeLib.nativeGetMeshVertexCount(i)
            val mc = meshCount; val tv = totalVerts
            runOnUiThread {
                statusBar?.visibility = View.VISIBLE
                tvStatusMeshes?.text = "● $mc mesh${if (mc != 1) "es" else ""}"
                tvStatusVerts?.text  = "${formatNum(tv)} verts"
                tvStatusFile?.text   = currentFileName
            }
        }
    }

    private fun formatNum(n: Int): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000f)
        n >= 1_000     -> "%.1fK".format(n / 1_000f)
        else           -> n.toString()
    }

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
                val p1 = rulerPoint1!!.copyOf(); val p2 = pt.copyOf()
                glView.queueEvent { NativeLib.nativeSetRulerPoints(true, p1, true, p2) }
                lifecycleScope.launch(Dispatchers.Default) {
                    val dx = p2[0]-p1[0]; val dy = p2[1]-p1[1]; val dz = p2[2]-p1[2]
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

    // ── Export Sheet ──────────────────────────────────────────────────────────
    private fun showExportSheet() {
        if (supportFragmentManager.findFragmentByTag(ExportFragment.TAG) != null) return
        ExportFragment.newInstance().show(supportFragmentManager, ExportFragment.TAG)
    }

    fun exportModel(format: String, share: Boolean, shareApp: String? = null) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val baseName = if (currentFileName.isNotEmpty())
                    currentFileName.substringBeforeLast('.') else "model"
                val fileName = "${baseName}_$ts.$format"
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
                            putExtra(Intent.EXTRA_SUBJECT, "3D Model: $fileName")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            if (shareApp != null) setPackage(shareApp)
                        }
                        try {
                            startActivity(Intent.createChooser(intent, "Share 3D Model via"))
                        } catch (e: Exception) {
                            toast("App not installed")
                        }
                    } else {
                        MediaScannerConnection.scanFile(this@MainActivity, arrayOf(outFile.absolutePath), null, null)
                        toast("✅ Saved: ${outFile.name}")
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
                currentFileName = name
                val dest = File(cacheDir, name)
                // NIO channel transfer — ~5x faster than stream copyTo()
                contentResolver.openInputStream(uri)?.use { inp ->
                    val src = Channels.newChannel(inp)
                    FileOutputStream(dest).channel.use { dst ->
                        var pos = 0L
                        while(true) { val n = dst.transferFrom(src, pos, 4L*1024*1024); if(n<=0) break; pos+=n }
                    }
                }
                withContext(Dispatchers.Main) {
                    tvHint?.visibility = View.GONE
                    showLoading("Loading $name…", "Parsing & separating meshes…")
                }
                // parseModel now does: file parse + mesh separation on IO thread
                // uploadParsed only does fast GPU buffer upload on GL thread
                val parseOk = try { NativeLib.nativeParseModel(dest.absolutePath) } catch (_: Exception) { false }
                if (!parseOk) {
                    withContext(Dispatchers.Main) { hideLoading(); toast("Failed to parse $name") }
                    return@launch
                }
                withContext(Dispatchers.Main) { showLoading("Sending to GPU…", "Almost ready…") }
                var uploadOk = false
                val latch = CountDownLatch(1)
                glView.queueEvent {
                    try { uploadOk = NativeLib.nativeUploadParsed() } catch (_: Exception) {}
                    latch.countDown()
                }
                latch.await()
                withContext(Dispatchers.Main) {
                    hideLoading()
                    if (uploadOk) {
                        toast("✓ $name loaded")
                        updateStatusBar()
                    } else toast("GPU upload failed")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { hideLoading(); toast("Error: ${e.message}") }
            }
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
    private fun showLoading(msg: String, detail: String = "") {
        tvLoading?.text = msg
        tvLoadingDetail?.text = detail
        loadingOverlay?.visibility = View.VISIBLE
    }
    private fun hideLoading() { loadingOverlay?.visibility = View.GONE }

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
            toast(if(saved!=null)"📸 Saved: ${saved.name}" else "Could not save screenshot")
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

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
