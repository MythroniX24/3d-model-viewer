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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch

class MainActivity : AppCompatActivity() {

    // Public so EditorPanelFragment.glRun() can access it
    lateinit var glView: ModelGLSurfaceView
    private lateinit var renderer: ModelRenderer
    private lateinit var tvFps: TextView
    private lateinit var tvHint: TextView

    // ── File picker ───────────────────────────────────────────────────────────
    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { openModelFromUri(it) } }

    // ── Permission request ────────────────────────────────────────────────────
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) launchFilePicker()
        else toast("Storage permission required to open files")
    }

    // ── onCreate ─────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        glView   = findViewById(R.id.glSurface)
        tvFps    = findViewById(R.id.tvFps)
        tvHint   = findViewById(R.id.tvHint)

        renderer = ModelRenderer()
        renderer.onFpsUpdate = { fps ->
            runOnUiThread { tvFps.text = "%.0f FPS".format(fps) }
        }
        glView.attachRenderer(renderer)

        findViewById<View>(R.id.btnOpen).setOnClickListener       { requestOpenFile() }
        findViewById<View>(R.id.btnEdit).setOnClickListener       { openEditor() }
        findViewById<View>(R.id.btnUndo).setOnClickListener       { glView.queueEvent { NativeLib.nativeUndo() } }
        findViewById<View>(R.id.btnRedo).setOnClickListener       { glView.queueEvent { NativeLib.nativeRedo() } }
        findViewById<View>(R.id.btnScreenshot).setOnClickListener { takeScreenshot() }
        findViewById<View>(R.id.btnReset).setOnClickListener     { glView.queueEvent { NativeLib.nativeResetCamera() } }

        // Handle "Open with" from file manager
        intent?.data?.let { openModelFromUri(it) }
    }

    override fun onResume()  { super.onResume();  glView.onResume()  }
    override fun onPause()   { super.onPause();   glView.onPause()   }
    override fun onDestroy() {
        glView.queueEvent { NativeLib.nativeDestroy() }
        super.onDestroy()
    }

    // ── Permission helpers ────────────────────────────────────────────────────
    private fun requestOpenFile() {
        if (hasStoragePermission()) launchFilePicker() else requestStoragePermission()
    }

    private fun hasStoragePermission() = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> true
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q        -> true
        else -> ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestStoragePermission() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        permLauncher.launch(perms)
    }

    private fun launchFilePicker() {
        // "*/*" as primary type makes most file managers show all files
        filePicker.launch(arrayOf("*/*"))
    }

    // ── Model loading ─────────────────────────────────────────────────────────
    private fun openModelFromUri(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val name = resolveFileName(uri) ?: "model.obj"
                val dest = File(cacheDir, name)

                // Copy URI content → local cache file (C++ needs a real path)
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(dest).use { input.copyTo(it) }
                }

                withContext(Dispatchers.Main) {
                    tvHint.visibility = View.GONE
                    toast("Loading $name…")
                }

                // Queue GPU upload onto the GL thread; wait for result
                var loadOk = false
                val latch = CountDownLatch(1)
                glView.queueEvent {
                    loadOk = NativeLib.nativeLoadModel(dest.absolutePath)
                    latch.countDown()
                }
                latch.await()   // blocks IO thread, not UI thread

                withContext(Dispatchers.Main) {
                    if (loadOk) toast("✓ $name loaded")
                    else        toast("Failed to load $name — unsupported format?")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("Error: ${e.message}") }
            }
        }
    }

    private fun resolveFileName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (col >= 0) return cursor.getString(col)
            }
        }
        return uri.lastPathSegment
    }

    // ── Editor bottom sheet ───────────────────────────────────────────────────
    private fun openEditor() {
        val tag = EditorPanelFragment.TAG
        if (supportFragmentManager.findFragmentByTag(tag) != null) return
        EditorPanelFragment.newInstance().show(supportFragmentManager, tag)
    }

    // ── Screenshot ────────────────────────────────────────────────────────────
    private fun takeScreenshot() {
        // Capture GL surface dimensions BEFORE going to GL thread (UI-thread safe)
        val capW = glView.width
        val capH = glView.height
        if (capW == 0 || capH == 0) { toast("No model to screenshot"); return }

        lifecycleScope.launch {
            // Read pixels on GL thread
            var rgba: ByteArray? = null
            val latch = CountDownLatch(1)
            glView.queueEvent {
                rgba = NativeLib.nativeTakeScreenshot()
                latch.countDown()
            }
            withContext(Dispatchers.IO) { latch.await() }

            val bytes = rgba ?: run { toast("Screenshot failed"); return@launch }

            // Convert RGBA bytes → ARGB_8888 Bitmap (Android uses ARGB internally)
            val bmp = withContext(Dispatchers.Default) {
                val argb = IntArray(capW * capH)
                for (i in argb.indices) {
                    val base = i * 4
                    val r = bytes[base    ].toInt() and 0xFF
                    val g = bytes[base + 1].toInt() and 0xFF
                    val b = bytes[base + 2].toInt() and 0xFF
                    val a = bytes[base + 3].toInt() and 0xFF
                    argb[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
                Bitmap.createBitmap(argb, capW, capH, Bitmap.Config.ARGB_8888)
            }

            val saved = withContext(Dispatchers.IO) { saveBitmap(bmp) }
            toast(if (saved != null) "Saved: ${saved.name}" else "Could not save screenshot")
        }
    }

    private fun saveBitmap(bmp: Bitmap): File? = try {
        val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "3DViewer")
        else
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "3DViewer")
        dir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f  = File(dir, "3DViewer_$ts.png")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        MediaScannerConnection.scanFile(this, arrayOf(f.absolutePath), null, null)
        f
    } catch (e: Exception) { null }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
