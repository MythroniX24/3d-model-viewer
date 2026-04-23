package com.modelviewer3d

import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.util.concurrent.CountDownLatch

/**
 * Mesh Tools Panel — Algorithms from MeshLab & OpenSCAD source analysis
 *
 * Included:
 * • QEM Decimation     — Garland-Heckbert quadric error metric (MeshLab quadric_simp)
 * • Vertex Weld        — Spatial-hash duplicate removal (OpenSCAD RemoveDuplicateVertex)
 * • Degenerate Cleanup — Zero-area face removal (MeshLab RemoveFaceOutOfRangeArea)
 * • Mesh Statistics    — Surface area, volume, bbox, watertight check (MeshLab FilterMeshing)
 */
class MeshToolsFragment : BottomSheetDialogFragment() {

    private var tvStats: TextView? = null
    private var statsLoaded = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx).apply { setBackgroundColor(0x00000000) }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 64)
            setBackgroundResource(R.drawable.bg_bottom_sheet)
        }
        scroll.addView(root)

        // Handle bar
        root.addView(handle(ctx))

        // Title
        root.addView(titleRow(ctx, "🔧  Mesh Tools", "MeshLab+OpenSCAD"))
        root.addView(divider(ctx))

        // ── Mesh Statistics ───────────────────────────────────────────────────
        root.addView(sectionLabel(ctx, "MESH STATISTICS"))
        val tvSt = TextView(ctx).apply {
            text = "Tap Analyze to compute mesh statistics"
            textSize = 10f; setTextColor(Color.parseColor("#606080"))
            background = ctx.getDrawable(R.drawable.bg_hint_card)
            setPadding(16, 14, 16, 14)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(14, 4, 14, 0) }
        }
        tvStats = tvSt; root.addView(tvSt)

        root.addView(actionButton(ctx, "📊  Analyze Mesh #0", "#00D4FF") {
            loadStats(0)
        })
        root.addView(divider(ctx))

        // ── QEM Decimation ────────────────────────────────────────────────────
        root.addView(sectionLabel(ctx, "QEM DECIMATION  (Garland-Heckbert)"))
        root.addView(infoLabel(ctx,
            "Reduces triangle count using Quadric Error Metrics. " +
            "Preserves shape — collapses low-cost edges first."))

        var decimPct = 0.5f
        val decimSlider = percentSlider(ctx, 0.05f, 0.95f, 0.5f, "%d%%") { v -> decimPct = v }
        root.addView(decimSlider)

        val tvDecimInfo = TextView(ctx).apply {
            text = "Target: 50% of faces"; textSize = 10f
            setTextColor(Color.parseColor("#606080")); setPadding(20, 2, 20, 0)
        }
        root.addView(tvDecimInfo)

        decimSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(b: SeekBar) {}
            override fun onStopTrackingTouch(b: SeekBar) {}
            override fun onProgressChanged(b: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                decimPct = 0.05f + p.toFloat() / 100f * 0.90f
                tvDecimInfo.text = "Target: ${(decimPct * 100).toInt()}% of original faces"
            }
        })

        root.addView(actionButton(ctx, "▶  Run Decimation on Mesh #0", "#FF9800") {
            val glv = (activity as? MainActivity)?.glView ?: return@actionButton
            val pct = decimPct
            tvDecimInfo.text = "⏳ Running QEM decimation…"
            glv.queueEvent {
                val ok = NativeLib.nativeDecimateMesh(0, pct)
                activity?.runOnUiThread {
                    tvDecimInfo.text = if (ok)
                        "✓ Done — retap Analyze to see new stats"
                    else
                        "✗ Decimation failed (too few faces?)"
                    if (ok) loadStats(0)
                    activity?.sendBroadcast(
                        android.content.Intent(EditorPanelFragment.ACTION_DIMS_CHANGED))
                }
            }
        })
        root.addView(divider(ctx))

        // ── Vertex Weld ───────────────────────────────────────────────────────
        root.addView(sectionLabel(ctx, "VERTEX WELD  (Duplicate Removal)"))
        root.addView(infoLabel(ctx,
            "Merges duplicate vertices within tolerance. " +
            "Fixes cracked seams and reduces vertex count."))

        val tvWeldResult = TextView(ctx).apply {
            text = ""; textSize = 10f; setTextColor(Color.parseColor("#4CAF82"))
            setPadding(20, 4, 20, 0)
        }
        root.addView(actionButton(ctx, "⊕  Weld Duplicate Vertices (0.01mm)", "#4CAF82") {
            val glv = (activity as? MainActivity)?.glView ?: return@actionButton
            tvWeldResult.text = "⏳ Welding…"
            glv.queueEvent {
                val n = NativeLib.nativeWeldVertices(0, 0.01f)
                activity?.runOnUiThread {
                    tvWeldResult.text = if (n > 0) "✓ Merged $n duplicate vertices"
                    else "✓ No duplicates found"
                    if (n > 0) loadStats(0)
                }
            }
        })
        root.addView(tvWeldResult)
        root.addView(divider(ctx))

        // ── Degenerate Face Cleanup ───────────────────────────────────────────
        root.addView(sectionLabel(ctx, "CLEANUP  (Degenerate Faces)"))
        root.addView(infoLabel(ctx,
            "Removes zero-area triangles and degenerate faces. " +
            "Improves mesh quality for 3D printing."))

        val tvCleanResult = TextView(ctx).apply {
            text = ""; textSize = 10f; setTextColor(Color.parseColor("#4CAF82"))
            setPadding(20, 4, 20, 0)
        }
        root.addView(actionButton(ctx, "✂  Remove Zero-Area Faces", "#FF7043") {
            val glv = (activity as? MainActivity)?.glView ?: return@actionButton
            tvCleanResult.text = "⏳ Cleaning…"
            glv.queueEvent {
                val n = NativeLib.nativeRemoveZeroAreaFaces(0)
                activity?.runOnUiThread {
                    tvCleanResult.text = if (n > 0) "✓ Removed $n degenerate faces"
                    else "✓ No degenerate faces found"
                    if (n > 0) loadStats(0)
                }
            }
        })
        root.addView(tvCleanResult)

        // Auto-load stats if already loaded
        if (!statsLoaded) loadStats(0)

        return scroll
    }

    private fun loadStats(meshIdx: Int) {
        val glv = (activity as? MainActivity)?.glView ?: return
        glv.queueEvent {
            val s = NativeLib.nativeGetMeshStats(meshIdx)
            activity?.runOnUiThread {
                if (s.size >= 9) {
                    statsLoaded = true
                    val watertight = s[8] > 0.5f
                    tvStats?.text = buildString {
                        appendLine("Surface Area:  %.2f mm²".format(s[0]))
                        appendLine("Volume:        %.2f mm³".format(s[1]))
                        appendLine("Bounding Box:  %.2f × %.2f × %.2f mm".format(s[2], s[3], s[4]))
                        appendLine("Vertices:      ${s[5].toInt()}")
                        appendLine("Triangles:     ${s[6].toInt()}")
                        appendLine("Edges:         ${s[7].toInt()}")
                        append("Watertight:    ${if (watertight) "✓ Yes (printable)" else "✗ No (open mesh)"}")
                    }
                    tvStats?.setTextColor(if (watertight) Color.parseColor("#4CAF82") else Color.WHITE)
                }
            }
        }
    }

    // ── Helper builders ───────────────────────────────────────────────────────
    private fun percentSlider(
        ctx: android.content.Context,
        min: Float, max: Float, init: Float,
        @Suppress("UNUSED_PARAMETER") fmt: String,
        onChange: (Float) -> Unit
    ): SeekBar {
        return SeekBar(ctx).apply {
            this.max = 100
            progress = ((init - min) / (max - min) * 100).toInt().coerceIn(0, 100)
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF9800"))
            thumbTintList    = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF9800"))
            setPadding(20, 8, 20, 0)
        }
    }

    private fun actionButton(
        ctx: android.content.Context,
        text: String, accentHex: String,
        onClick: () -> Unit
    ) = Button(ctx).apply {
        this.text = text; textSize = 11f
        setTextColor(Color.parseColor(accentHex))
        background = ctx.getDrawable(R.drawable.bg_card_dark)
        setPadding(24, 0, 24, 0)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 48
        ).apply { setMargins(14, 8, 14, 0) }
        setOnClickListener { onClick() }
    }

    private fun infoLabel(ctx: android.content.Context, msg: String) = TextView(ctx).apply {
        text = msg; textSize = 9f; setTextColor(Color.parseColor("#505070"))
        setPadding(20, 2, 20, 4)
    }

    private fun handle(ctx: android.content.Context) = LinearLayout(ctx).apply {
        gravity = android.view.Gravity.CENTER_HORIZONTAL; setPadding(0, 14, 0, 0)
        addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor("#404058"))
            layoutParams = LinearLayout.LayoutParams(48, 4)
        })
    }

    private fun titleRow(ctx: android.content.Context, t: String, badge: String) =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(20, 14, 20, 6)
            addView(TextView(ctx).apply {
                text = t; textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(ctx).apply {
                text = badge; textSize = 8f; letterSpacing = 0.12f
                setTextColor(Color.parseColor("#FF9800"))
                background = ctx.getDrawable(R.drawable.bg_pill); setPadding(10, 3, 10, 3)
            })
        }

    private fun sectionLabel(ctx: android.content.Context, text: String) = TextView(ctx).apply {
        this.text = text; textSize = 9f; letterSpacing = 0.14f
        setTextColor(Color.parseColor("#00D4FF")); setPadding(20, 18, 20, 6)
    }

    private fun divider(ctx: android.content.Context) = View(ctx).apply {
        setBackgroundColor(Color.parseColor("#1A1A28"))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }

    companion object {
        const val TAG = "MeshTools"
        fun newInstance() = MeshToolsFragment()
    }
}
