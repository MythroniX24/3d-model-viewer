package com.modelviewer3d

import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Enhanced Mesh Separation Panel
 * - Lists disconnected mesh islands via Union-Find (done in C++)
 * - Select / highlight individual mesh in viewport
 * - Toggle visibility per mesh (with eye icon state)
 * - Delete mesh with confirmation
 * - Resize selected mesh independently (W/H/D in mm, with lock-ratio option)
 * - Per-mesh color badge
 * - Vertex count display per mesh
 */
class MeshListFragment : BottomSheetDialogFragment() {

    private var meshCount = 0
    private var selectedIdx = -1
    private val visibilityMap = mutableMapOf<Int, Boolean>()  // track per-mesh visibility
    private var listContainer: LinearLayout? = null

    private val meshColors = listOf(
        "#00D4FF","#FF7043","#4CAF82","#FFD54F",
        "#AB47BC","#EC407A","#26C6DA","#D4E157"
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx).apply {
            setBackgroundColor(0x00000000)
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 40)
            setBackgroundResource(R.drawable.bg_bottom_sheet)
        }
        scroll.addView(root)

        // ── Handle bar ───────────────────────────────────────────────────────
        val handleWrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(0, 14, 0, 0)
        }
        handleWrap.addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor("#404058"))
            layoutParams = LinearLayout.LayoutParams(48, 4).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL }
        })
        root.addView(handleWrap)

        // ── Header ───────────────────────────────────────────────────────────
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(20, 16, 20, 4)
        }
        headerRow.addView(TextView(ctx).apply {
            text = "⬡  Mesh Separation"
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val tvCount = TextView(ctx).apply {
            text = "–"
            textSize = 12f
            setTextColor(Color.parseColor("#00D4FF"))
            background = ctx.getDrawable(R.drawable.bg_pill)
            setPadding(14, 4, 14, 4)
        }
        headerRow.addView(tvCount)
        root.addView(headerRow)

        // subtitle
        root.addView(TextView(ctx).apply {
            text = "Disconnected geometry is auto-separated into independent mesh islands"
            textSize = 11f
            setTextColor(Color.parseColor("#606080"))
            setPadding(20, 0, 20, 14)
        })

        // Divider
        root.addView(divider(ctx))

        // ── Mesh list container ──────────────────────────────────────────────
        val lc = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 8, 14, 8)
        }
        listContainer = lc
        root.addView(lc)

        // Fetch mesh count on GL thread
        (activity as? MainActivity)?.glView?.queueEvent {
            meshCount = NativeLib.nativeGetMeshCount()
            // Init visibility map
            for (i in 0 until meshCount) visibilityMap[i] = true
            activity?.runOnUiThread {
                tvCount.text = "$meshCount"
                buildMeshList(ctx)
            }
        }

        return scroll
    }

    private fun buildMeshList(ctx: android.content.Context) {
        listContainer?.removeAllViews()
        if (meshCount == 0) {
            listContainer?.addView(TextView(ctx).apply {
                text = "No model loaded.\nOpen a 3D file first."
                textSize = 13f
                setTextColor(Color.parseColor("#606080"))
                gravity = android.view.Gravity.CENTER
                setPadding(0, 30, 0, 10)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            return
        }

        for (i in 0 until meshCount) {
            val name   = NativeLib.nativeGetMeshName(i)
            val isVis  = visibilityMap[i] ?: true
            val isSel  = (i == selectedIdx)
            val row    = buildMeshRow(ctx, i, name, isVis, isSel)
            listContainer?.addView(row)

            // Spacing between rows
            listContainer?.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 6)
            })
        }
    }

    private fun buildMeshRow(ctx: android.content.Context, idx: Int, name: String, isVisible: Boolean, isSelected: Boolean): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ctx.getDrawable(if (isSelected) R.drawable.bg_card_selected else R.drawable.bg_card_dark)
            setPadding(14, 12, 14, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        // ── Row header (dot · name · vertex count · vis button · delete) ──
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        // Color dot
        val colorHex = meshColors[idx % meshColors.size]
        val dot = View(ctx).apply {
            setBackgroundColor(Color.parseColor(colorHex))
            layoutParams = LinearLayout.LayoutParams(10, 10).apply {
                setMargins(0, 0, 10, 0)
            }
        }
        header.addView(dot)

        // Name
        header.addView(TextView(ctx).apply {
            text = name
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(if (isSelected) Color.parseColor("#00D4FF") else Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        // Vertex count badge (loaded async)
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
                    vc >= 1_000_000 -> "%.1fM v".format(vc / 1_000_000f)
                    vc >= 1_000     -> "%.1fK v".format(vc / 1_000f)
                    else            -> "$vc v"
                }
            }
        }

        // Visibility toggle button
        val btnVis = ImageButton(ctx).apply {
            setImageResource(if (isVisible) R.drawable.ic_visibility else R.drawable.ic_visibility_off)
            setColorFilter(if (isVisible) Color.parseColor("#00D4FF") else Color.parseColor("#404060"))
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

        // Delete button
        val btnDel = ImageButton(ctx).apply {
            setImageResource(android.R.drawable.ic_menu_delete)
            setColorFilter(Color.parseColor("#FF7043"))
            background = null
            layoutParams = LinearLayout.LayoutParams(36, 36)
            setOnClickListener {
                android.app.AlertDialog.Builder(ctx)
                    .setTitle("Delete Mesh")
                    .setMessage("Delete \"$name\"?\nThis cannot be undone.")
                    .setPositiveButton("Delete") { _, _ ->
                        glRun { NativeLib.nativeDeleteMesh(idx) }
                        meshCount--
                        visibilityMap.remove(idx)
                        // Re-index visibility map
                        val newMap = mutableMapOf<Int, Boolean>()
                        var ni = 0
                        for (k in 0 until meshCount + 1) {
                            if (k == idx) continue
                            newMap[ni++] = visibilityMap[k] ?: true
                        }
                        visibilityMap.clear(); visibilityMap.putAll(newMap)
                        if (selectedIdx == idx) selectedIdx = -1
                        else if (selectedIdx > idx) selectedIdx--
                        activity?.runOnUiThread {
                            buildMeshList(ctx)
                            (activity as? MainActivity)?.updateStatusBar()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        header.addView(btnDel)
        card.addView(header)

        // Size info row
        val tvSize = TextView(ctx).apply {
            textSize = 10f
            setTextColor(Color.parseColor("#505070"))
            setPadding(20, 6, 0, 0)
        }
        glRun {
            val s = NativeLib.nativeGetMeshSizeMM(idx)
            activity?.runOnUiThread { tvSize.text = "Size: %.1f × %.1f × %.1f mm".format(s[0], s[1], s[2]) }
        }
        card.addView(tvSize)

        // Expanded resize editor (shown only when selected)
        if (isSelected) {
            card.addView(buildResizeEditor(ctx, idx))
        }

        // Click to select
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

        // Divider
        container.addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor("#252538"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                setMargins(0, 0, 0, 12)
            }
        })

        // Section label
        container.addView(TextView(ctx).apply {
            text = "RESIZE MESH  (mm)"
            textSize = 9f
            letterSpacing = 0.14f
            setTextColor(Color.parseColor("#00D4FF"))
            setPadding(0, 0, 0, 8)
        })

        // Get current size (blocking on GL – small latch)
        var sW = 50f; var sH = 50f; var sD = 50f
        val latch = java.util.concurrent.CountDownLatch(1)
        (activity as? MainActivity)?.glView?.queueEvent {
            try { val s = NativeLib.nativeGetMeshSizeMM(idx); sW=s[0]; sH=s[1]; sD=s[2] }
            catch (_: Exception) {}
            latch.countDown()
        }
        latch.await()

        // Lock ratio toggle
        var lockRatio = true
        val swLock = Switch(ctx).apply {
            text = "Lock Aspect Ratio"
            isChecked = lockRatio
            setTextColor(Color.parseColor("#AAAACC"))
            textSize = 11f
            setPadding(0, 0, 0, 8)
            setOnCheckedChangeListener { _, v -> lockRatio = v }
        }
        container.addView(swLock)

        // Input fields
        fun makeField(label: String, initVal: Float): EditText {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 4, 0, 4)
            }
            row.addView(TextView(ctx).apply {
                text = label
                textSize = 11f
                setTextColor(Color.parseColor("#808099"))
                layoutParams = LinearLayout.LayoutParams(26, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            val et = EditText(ctx).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText("%.2f".format(initVal))
                setTextColor(Color.WHITE)
                textSize = 13f
                background = ctx.getDrawable(R.drawable.bg_input_field)
                setPadding(12, 8, 12, 8)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(et)
            row.addView(TextView(ctx).apply {
                text = " mm"
                textSize = 10f
                setTextColor(Color.parseColor("#505070"))
            })
            container.addView(row)
            return et
        }

        val etW = makeField("W", sW)
        val etH = makeField("H", sH)
        val etD = makeField("D", sD)

        // Store originals for ratio calc
        val origW = sW; val origH = sH; val origD = sD

        // Apply button
        container.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 10, 0, 0)

            addView(Button(ctx).apply {
                text = "Apply Size"
                textSize = 11f
                setTextColor(Color.parseColor("#00D4FF"))
                background = ctx.getDrawable(R.drawable.bg_btn_accent)
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, 40, 1f).apply { setMargins(0, 0, 6, 0) }
                setOnClickListener {
                    val w = etW.text.toString().toFloatOrNull() ?: sW
                    val h = etH.text.toString().toFloatOrNull() ?: sH
                    val d = etD.text.toString().toFloatOrNull() ?: sD
                    if (lockRatio && origW > 0 && origH > 0 && origD > 0) {
                        // Use W as reference
                        val ratio = w / origW
                        val nh = origH * ratio; val nd = origD * ratio
                        glRun { NativeLib.nativeSetMeshScaleMM(idx, w, nh, nd) }
                        // Update fields
                        etH.setText("%.2f".format(nh))
                        etD.setText("%.2f".format(nd))
                    } else {
                        glRun { NativeLib.nativeSetMeshScaleMM(idx, w, h, d) }
                    }
                    toast("Mesh resized")
                }
            })

            addView(Button(ctx).apply {
                text = "Reset"
                textSize = 11f
                setTextColor(Color.parseColor("#FF7043"))
                background = ctx.getDrawable(R.drawable.bg_btn_danger)
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 40).apply {
                    setMargins(0, 0, 0, 0)
                }
                setPadding(16, 0, 16, 0)
                setOnClickListener {
                    etW.setText("%.2f".format(origW))
                    etH.setText("%.2f".format(origH))
                    etD.setText("%.2f".format(origD))
                    glRun { NativeLib.nativeSetMeshScaleMM(idx, origW, origH, origD) }
                    toast("Reset to original")
                }
            })
        })

        return container
    }

    private fun divider(ctx: android.content.Context) = View(ctx).apply {
        setBackgroundColor(Color.parseColor("#1E1E2E"))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }

    private fun glRun(block: () -> Unit) = (activity as? MainActivity)?.glView?.queueEvent(block)
    private fun toast(msg: String) = activity?.let { Toast.makeText(it, msg, Toast.LENGTH_SHORT).show() }

    companion object {
        const val TAG = "MeshList"
        fun newInstance() = MeshListFragment()
    }
}
