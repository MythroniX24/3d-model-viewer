package com.modelviewer3d

import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch

/**
 * Mesh Separation Panel
 *
 * Flow:
 *   1. Opens → shows current state (single mesh if not separated yet)
 *   2. Big "Separate Meshes" button → runs Union-Find on IO thread → re-uploads on GL thread
 *   3. After separation → shows island list with select/hide/resize/delete per mesh
 */
class MeshListFragment : BottomSheetDialogFragment() {

    private var isSeparated = false
    private var meshCount   = 0
    private var selectedIdx = -1
    private val visibilityMap = mutableMapOf<Int, Boolean>()

    private var rootLayout:    LinearLayout? = null
    private var listContainer: LinearLayout? = null
    private var btnSeparate:   View?         = null
    private var separateCard:  View?         = null
    private var tvIslandTitle: TextView?     = null

    private val meshColors = listOf(
        "#00D4FF","#FF7043","#4CAF82","#FFD54F",
        "#AB47BC","#EC407A","#26C6DA","#D4E157"
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx).apply { setBackgroundColor(0x00000000) }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 48)
            setBackgroundResource(R.drawable.bg_bottom_sheet)
        }
        rootLayout = root
        scroll.addView(root)

        // Handle bar
        root.addView(LinearLayout(ctx).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(0, 14, 0, 0)
            addView(View(ctx).apply {
                setBackgroundColor(Color.parseColor("#404058"))
                layoutParams = LinearLayout.LayoutParams(48, 4)
            })
        })

        // Title row
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(20, 14, 20, 4)
        }
        titleRow.addView(TextView(ctx).apply {
            text = "⬡  Mesh Separation"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val tvIslandCount = TextView(ctx).apply {
            text = "–"
            textSize = 11f
            setTextColor(Color.parseColor("#00D4FF"))
            background = ctx.getDrawable(R.drawable.bg_pill)
            setPadding(14, 4, 14, 4)
        }
        tvIslandTitle = tvIslandCount
        titleRow.addView(tvIslandCount)
        root.addView(titleRow)

        root.addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor("#1A1A28"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                setMargins(0, 10, 0, 0)
            }
        })

        // ── SEPARATE BUTTON CARD ──────────────────────────────────────────────
        val sepCard = buildSeparateCard(ctx)
        separateCard = sepCard
        root.addView(sepCard)

        // ── MESH LIST (shown after separation) ────────────────────────────────
        val lc = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 4, 14, 8)
        }
        listContainer = lc
        root.addView(lc)

        // Load initial state
        refreshState(ctx)

        return scroll
    }

    private fun buildSeparateCard(ctx: android.content.Context): LinearLayout {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundResource(R.drawable.bg_card_dark)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(14, 16, 14, 8) }
        }

        // Icon + headline
        val headRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        headRow.addView(TextView(ctx).apply {
            text = "⬡"
            textSize = 28f
            layoutParams = LinearLayout.LayoutParams(52, LinearLayout.LayoutParams.WRAP_CONTENT)
            gravity = android.view.Gravity.CENTER
        })
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(ctx).apply {
            text = "Separate Mesh Islands"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        texts.addView(TextView(ctx).apply {
            text = "Split disconnected geometry into\nindividual selectable mesh groups"
            textSize = 11f
            setTextColor(Color.parseColor("#606080"))
            setPadding(0, 4, 0, 0)
        })
        headRow.addView(texts)
        card.addView(headRow)

        // Warning note
        card.addView(TextView(ctx).apply {
            text = "⚠ This may take a moment for large models"
            textSize = 10f
            setTextColor(Color.parseColor("#805050"))
            setPadding(0, 12, 0, 12)
        })

        // The big button
        val btn = Button(ctx).apply {
            text = "⬡  Separate Meshes"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#00D4FF"))
            background = ctx.getDrawable(R.drawable.bg_btn_accent)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 52
            )
            setOnClickListener { startSeparation() }
        }
        btnSeparate = btn
        card.addView(btn)
        return card
    }

    private fun startSeparation() {
        val main = activity as? MainActivity ?: return
        btnSeparate?.isEnabled = false
        (btnSeparate as? Button)?.text = "⏳  Separating…"

        lifecycleScope.launch {
            // CPU work on IO thread (Union-Find)
            val cpuOk = withContext(Dispatchers.IO) {
                // performSeparationCPU: pure CPU, no GL calls, safe on IO thread
                NativeLib.nativePerformSeparationCPU()
            }
            if (!cpuOk) {
                toast("Separation failed or already done")
                (btnSeparate as? Button)?.text = "⬡  Separate Meshes"
                btnSeparate?.isEnabled = true
                return@launch
            }

            // GPU upload on GL thread
            var gpuOk = false
            val latch = CountDownLatch(1)
            main.glView.queueEvent {
                gpuOk = NativeLib.nativePerformSeparationGPU()
                latch.countDown()
            }
            withContext(Dispatchers.IO) { latch.await() }

            // Refresh UI
            meshCount = NativeLib.nativeGetMeshCount()
            isSeparated = gpuOk && meshCount > 1

            withContext(Dispatchers.Main) {
                if (isSeparated) {
                    separateCard?.visibility = View.GONE
                    for (i in 0 until meshCount) visibilityMap[i] = true
                    tvIslandTitle?.text = "$meshCount"
                    buildMeshList(requireContext())
                    main.updateStatusBar()
                    toast("✅ $meshCount mesh islands separated")
                } else {
                    (btnSeparate as? Button)?.text = "⬡  Separate Meshes"
                    btnSeparate?.isEnabled = true
                    toast("Only 1 connected mesh found — nothing to separate")
                }
            }
        }
    }

    private fun refreshState(ctx: android.content.Context) {
        glRun {
            isSeparated = NativeLib.nativeIsSeparated()
            meshCount   = NativeLib.nativeGetMeshCount()
            activity?.runOnUiThread {
                if (isSeparated && meshCount > 1) {
                    separateCard?.visibility = View.GONE
                    for (i in 0 until meshCount) if (!visibilityMap.containsKey(i)) visibilityMap[i] = true
                    tvIslandTitle?.text = "$meshCount"
                    buildMeshList(ctx)
                } else {
                    separateCard?.visibility = View.VISIBLE
                    listContainer?.removeAllViews()
                    tvIslandTitle?.text = "–"
                }
            }
        }
    }

    private fun buildMeshList(ctx: android.content.Context) {
        listContainer?.removeAllViews()

        if (meshCount == 0) {
            listContainer?.addView(TextView(ctx).apply {
                text = "No model loaded"
                textSize = 13f
                setTextColor(Color.parseColor("#606080"))
                gravity = android.view.Gravity.CENTER
                setPadding(0, 24, 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            return
        }

        // Section header
        listContainer?.addView(TextView(ctx).apply {
            text = "MESH ISLANDS"
            textSize = 9f
            letterSpacing = 0.14f
            setTextColor(Color.parseColor("#505070"))
            setPadding(6, 10, 0, 6)
        })

        for (i in 0 until meshCount) {
            listContainer?.addView(buildMeshRow(ctx, i))
            listContainer?.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 6)
            })
        }
    }

    private fun buildMeshRow(ctx: android.content.Context, idx: Int): View {
        val name    = NativeLib.nativeGetMeshName(idx)
        val isVis   = visibilityMap[idx] ?: true
        val isSel   = (idx == selectedIdx)
        val colorHex = meshColors[idx % meshColors.size]

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ctx.getDrawable(if (isSel) R.drawable.bg_card_selected else R.drawable.bg_card_dark)
            setPadding(14, 12, 14, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        // ── Header row: dot · name · verts · eye · delete ──
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        // Color dot
        header.addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor(colorHex))
            layoutParams = LinearLayout.LayoutParams(10, 10).apply { setMargins(0, 0, 10, 0) }
        })

        // Name
        header.addView(TextView(ctx).apply {
            text = name
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(if (isSel) Color.parseColor("#00D4FF") else Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        // Vertex count
        val tvVerts = TextView(ctx).apply {
            text = "…"
            textSize = 10f
            setTextColor(Color.parseColor("#505070"))
            setPadding(0, 0, 8, 0)
        }
        header.addView(tvVerts)
        glRun {
            val vc = NativeLib.nativeGetMeshVertexCount(idx)
            activity?.runOnUiThread {
                tvVerts.text = when {
                    vc >= 1_000_000 -> "%.1fMv".format(vc / 1_000_000f)
                    vc >= 1_000     -> "%.1fKv".format(vc / 1_000f)
                    else            -> "${vc}v"
                }
            }
        }

        // Visibility toggle
        val btnVis = ImageButton(ctx).apply {
            setImageResource(if (isVis) R.drawable.ic_visibility else R.drawable.ic_visibility_off)
            setColorFilter(if (isVis) Color.parseColor("#00D4FF") else Color.parseColor("#404060"))
            background = null
            layoutParams = LinearLayout.LayoutParams(36, 36).apply { setMargins(0, 0, 4, 0) }
            setOnClickListener {
                val newVis = !(visibilityMap[idx] ?: true)
                visibilityMap[idx] = newVis
                glRun { NativeLib.nativeSetMeshVisible(idx, newVis) }
                setImageResource(if (newVis) R.drawable.ic_visibility else R.drawable.ic_visibility_off)
                setColorFilter(if (newVis) Color.parseColor("#00D4FF") else Color.parseColor("#404060"))
            }
        }
        header.addView(btnVis)

        // Delete
        val btnDel = ImageButton(ctx).apply {
            setImageResource(android.R.drawable.ic_menu_delete)
            setColorFilter(Color.parseColor("#FF7043"))
            background = null
            layoutParams = LinearLayout.LayoutParams(36, 36)
            setOnClickListener {
                android.app.AlertDialog.Builder(ctx)
                    .setTitle("Delete Mesh")
                    .setMessage("Delete \"$name\"? Cannot be undone.")
                    .setPositiveButton("Delete") { _, _ ->
                        glRun { NativeLib.nativeDeleteMesh(idx) }
                        meshCount--
                        visibilityMap.remove(idx)
                        val newMap = mutableMapOf<Int, Boolean>()
                        var ni = 0
                        for (k in 0..meshCount) {
                            if (k == idx) continue
                            newMap[ni++] = visibilityMap[k] ?: true
                        }
                        visibilityMap.clear(); visibilityMap.putAll(newMap)
                        if (selectedIdx == idx) selectedIdx = -1
                        else if (selectedIdx > idx) selectedIdx--
                        tvIslandTitle?.text = "$meshCount"
                        activity?.runOnUiThread {
                            buildMeshList(ctx)
                            (activity as? MainActivity)?.updateStatusBar()
                        }
                    }
                    .setNegativeButton("Cancel", null).show()
            }
        }
        header.addView(btnDel)
        card.addView(header)

        // Size info
        val tvSize = TextView(ctx).apply {
            textSize = 10f
            setTextColor(Color.parseColor("#505070"))
            setPadding(20, 4, 0, 0)
        }
        glRun {
            val s = NativeLib.nativeGetMeshSizeMM(idx)
            activity?.runOnUiThread { tvSize.text = "%.1f × %.1f × %.1f mm".format(s[0], s[1], s[2]) }
        }
        card.addView(tvSize)

        // Resize editor (only when selected)
        if (isSel) card.addView(buildResizeEditor(ctx, idx))

        // Tap to select/deselect
        card.setOnClickListener {
            selectedIdx = if (selectedIdx == idx) -1 else idx
            glRun { NativeLib.nativeSelectMesh(selectedIdx) }
            activity?.runOnUiThread { buildMeshList(ctx) }
        }

        return card
    }

    private fun buildResizeEditor(ctx: android.content.Context, idx: Int): View {
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4, 14, 4, 4)
        }
        container.addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor("#252538"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { setMargins(0, 0, 0, 12) }
        })
        container.addView(TextView(ctx).apply {
            text = "RESIZE MESH  (mm)"
            textSize = 9f; letterSpacing = 0.14f
            setTextColor(Color.parseColor("#00D4FF"))
            setPadding(0, 0, 0, 8)
        })

        var sW = 50f; var sH = 50f; var sD = 50f
        val latch = CountDownLatch(1)
        (activity as? MainActivity)?.glView?.queueEvent {
            try { val s = NativeLib.nativeGetMeshSizeMM(idx); sW=s[0]; sH=s[1]; sD=s[2] }
            catch (_: Exception) {}
            latch.countDown()
        }
        latch.await()
        val origW = sW; val origH = sH; val origD = sD

        var lockRatio = true
        container.addView(Switch(ctx).apply {
            text = "Lock Aspect Ratio"
            isChecked = lockRatio
            setTextColor(Color.parseColor("#AAAACC"))
            textSize = 11f
            setPadding(0, 0, 0, 8)
            setOnCheckedChangeListener { _, v -> lockRatio = v }
        })

        fun makeField(label: String, initVal: Float): EditText {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 4, 0, 4)
            }
            row.addView(TextView(ctx).apply {
                text = label; textSize = 11f
                setTextColor(Color.parseColor("#808099"))
                layoutParams = LinearLayout.LayoutParams(26, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            val et = EditText(ctx).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText("%.2f".format(initVal))
                setTextColor(Color.WHITE); textSize = 13f
                background = ctx.getDrawable(R.drawable.bg_input_field)
                setPadding(12, 8, 12, 8)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(et)
            row.addView(TextView(ctx).apply { text = " mm"; textSize = 10f; setTextColor(Color.parseColor("#505070")) })
            container.addView(row)
            return et
        }

        val etW = makeField("W", sW)
        val etH = makeField("H", sH)
        val etD = makeField("D", sD)

        container.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 10, 0, 0)
            addView(Button(ctx).apply {
                text = "Apply"
                textSize = 11f
                setTextColor(Color.parseColor("#00D4FF"))
                background = ctx.getDrawable(R.drawable.bg_btn_accent)
                layoutParams = LinearLayout.LayoutParams(0, 44, 1f).apply { setMargins(0, 0, 8, 0) }
                setOnClickListener {
                    val w = etW.text.toString().toFloatOrNull() ?: sW
                    val h = etH.text.toString().toFloatOrNull() ?: sH
                    val d = etD.text.toString().toFloatOrNull() ?: sD
                    if (lockRatio && origW > 0) {
                        val r = w / origW
                        val nh = origH * r; val nd = origD * r
                        glRun { NativeLib.nativeSetMeshScaleMM(idx, w, nh, nd) }
                        etH.setText("%.2f".format(nh)); etD.setText("%.2f".format(nd))
                    } else {
                        glRun { NativeLib.nativeSetMeshScaleMM(idx, w, h, d) }
                    }
                    toast("Resized")
                }
            })
            addView(Button(ctx).apply {
                text = "Reset"
                textSize = 11f
                setTextColor(Color.parseColor("#FF7043"))
                background = ctx.getDrawable(R.drawable.bg_btn_danger)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 44)
                setPadding(16, 0, 16, 0)
                setOnClickListener {
                    etW.setText("%.2f".format(origW)); etH.setText("%.2f".format(origH)); etD.setText("%.2f".format(origD))
                    glRun { NativeLib.nativeSetMeshScaleMM(idx, origW, origH, origD) }
                }
            })
        })
        return container
    }

    private fun glRun(block: () -> Unit) = (activity as? MainActivity)?.glView?.queueEvent(block)
    private fun toast(msg: String) = activity?.let { Toast.makeText(it, msg, Toast.LENGTH_SHORT).show() }

    companion object {
        const val TAG = "MeshList"
        fun newInstance() = MeshListFragment()
    }
}
