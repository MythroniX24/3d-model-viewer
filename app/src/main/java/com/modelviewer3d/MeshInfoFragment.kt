package com.modelviewer3d

import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.util.concurrent.CountDownLatch

/**
 * Mesh Info Panel — replaces the useless "MeshTools/Wrench" button.
 *
 * Shows real-time mesh statistics for every loaded mesh:
 *  • Surface area (mm²)
 *  • Enclosed volume (mm³)  — using divergence theorem
 *  • Bounding box (mm)
 *  • Vertex / Triangle / Edge count
 *  • Watertight check (is it 3D-printable?)
 *
 * All values computed on the GL thread and displayed here.
 */
class MeshInfoFragment : BottomSheetDialogFragment() {

    private var tvContent: TextView? = null
    private var pbLoading: ProgressBar? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx).apply { setBackgroundColor(0x00000000) }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 56)
            setBackgroundResource(R.drawable.bg_bottom_sheet)
        }
        scroll.addView(root)

        // Handle bar
        root.addView(LinearLayout(ctx).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL; setPadding(0, 14, 0, 0)
            addView(View(ctx).apply {
                setBackgroundColor(Color.parseColor("#404058"))
                layoutParams = LinearLayout.LayoutParams(48, 4)
            })
        })

        // Title
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(20, 14, 20, 6)
            addView(TextView(ctx).apply {
                text = "ℹ️  Mesh Info"; textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(Button(ctx).apply {
                text = "↻ Refresh"; textSize = 10f
                setTextColor(Color.parseColor("#00D4FF"))
                background = ctx.getDrawable(R.drawable.bg_btn_accent)
                setPadding(16, 0, 16, 0)
                setOnClickListener { loadStats() }
            })
        })

        // Divider
        root.addView(divider(ctx))

        // Loading indicator
        val pb = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; isIndeterminate = true
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00D4FF"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 3)
        }
        pbLoading = pb; root.addView(pb)

        // Content area
        val tvC = TextView(ctx).apply {
            text = "⏳ Computing mesh statistics…"
            textSize = 11f; setTextColor(Color.parseColor("#9090B0"))
            fontFamily = "monospace"
            background = ctx.getDrawable(R.drawable.bg_hint_card)
            setPadding(16, 16, 16, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(14, 12, 14, 0) }
        }
        tvContent = tvC; root.addView(tvC)

        // Printability tips
        root.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 16) })
        root.addView(sectionLabel(ctx, "WHAT THESE MEAN"))
        root.addView(TextView(ctx).apply {
            text = "✓ Watertight = every edge shared by exactly 2 faces → 3D printable\n" +
                   "✗ Not watertight = open mesh (e.g. flat plane, or missing faces)\n\n" +
                   "Volume uses the divergence theorem (signed sum of triangle volumes).\n" +
                   "Negative = face normals are flipped inside-out."
            textSize = 10f; setTextColor(Color.parseColor("#404060"))
            setPadding(20, 0, 20, 0)
        })

        loadStats()
        return scroll
    }

    private fun loadStats() {
        pbLoading?.isIndeterminate = true
        tvContent?.text = "⏳ Computing…"
        tvContent?.setTextColor(Color.parseColor("#9090B0"))

        val glv = (activity as? MainActivity)?.glView ?: return

        glv.queueEvent {
            val meshCount = try { NativeLib.nativeGetMeshCount() } catch (_: Exception) { 0 }
            if (meshCount == 0) {
                activity?.runOnUiThread {
                    tvContent?.text = "No model loaded. Open an STL, OBJ, or GLB file first."
                    pbLoading?.isIndeterminate = false
                }
                return@queueEvent
            }

            val sb = StringBuilder()
            for (i in 0 until meshCount) {
                val name = try { NativeLib.nativeGetMeshName(i) } catch (_: Exception) { "Mesh #$i" }
                val s = try { NativeLib.nativeGetMeshStats(i) } catch (_: Exception) { continue }
                if (s.size < 9) continue

                val watertight = s[8] > 0.5f
                val wtIcon = if (watertight) "✓" else "✗"
                val vtx = s[5].toInt(); val tri = s[6].toInt()

                sb.appendLine("── $name ──────────────")
                sb.appendLine("  Vertices :  ${fmtNum(vtx)}")
                sb.appendLine("  Triangles:  ${fmtNum(tri)}")
                sb.appendLine("  Edges    :  ${fmtNum(s[7].toInt())}")
                sb.appendLine("  Bbox W   :  %.2f mm".format(s[2]))
                sb.appendLine("  Bbox H   :  %.2f mm".format(s[3]))
                sb.appendLine("  Bbox D   :  %.2f mm".format(s[4]))
                sb.appendLine("  Area     :  %.2f mm²".format(s[0]))
                sb.appendLine("  Volume   :  %.2f mm³".format(s[1]))
                sb.appendLine("  Watertight: $wtIcon ${if (watertight) "Yes (printable)" else "No (open mesh)"}")
                if (i < meshCount - 1) sb.appendLine()
            }

            activity?.runOnUiThread {
                tvContent?.text = sb.toString().trimEnd()
                tvContent?.setTextColor(Color.WHITE)
                pbLoading?.isIndeterminate = false
            }
        }
    }

    private fun fmtNum(n: Int): String = when {
        n >= 1_000_000 -> "%.2fM".format(n / 1_000_000f)
        n >= 1_000     -> "%.1fK".format(n / 1_000f)
        else           -> n.toString()
    }

    private fun sectionLabel(ctx: android.content.Context, text: String) = TextView(ctx).apply {
        this.text = text; textSize = 9f; letterSpacing = 0.14f
        setTextColor(Color.parseColor("#00D4FF")); setPadding(20, 14, 20, 6)
    }

    private fun divider(ctx: android.content.Context) = View(ctx).apply {
        setBackgroundColor(Color.parseColor("#1A1A28"))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }

    companion object {
        const val TAG = "MeshInfo"
        fun newInstance() = MeshInfoFragment()
    }
}
