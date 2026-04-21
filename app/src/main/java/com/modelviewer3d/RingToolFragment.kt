package com.modelviewer3d

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.*
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Ring Deformation Tool  v2
 *
 * Uses the v2 additive-displacement engine in renderer.cpp:
 * - Band Width:      moves outer wall, inner bore fixed. r_new = r + smoothstep(t)*deltaOuter
 * - Inner Diameter:  moves inner bore, outer wall fixed. r_new = r + (1-smoothstep(t))*deltaInner
 *
 * Both operations always deform from origVerts — zero cumulative distortion.
 * Slider ranges are computed dynamically from actual ring analysis results.
 */
class RingToolFragment : BottomSheetDialogFragment() {

    // ── Live ring params (all in mm) ──────────────────────────────────────────
    private var origInnerRadMM  = 0f
    private var origOuterRadMM  = 0f
    private var origBandWidthMM = 0f
    private var origInnerDiaMM  = 0f
    private var origHeightMM    = 0f

    private var currBandWidthMM = 0f
    private var currInnerDiaMM  = 0f

    // Dynamic slider ranges (set from ring analysis)
    private var bwMin = 0.1f;  private var bwMax = 20f
    private var idMin = 1.0f;  private var idMax = 50f
    private val STEPS = 2000

    // Selected mesh index
    private var targetMeshIdx = 0
    private var ringAnalyzed  = false

    // UI refs
    private var tvStatus: TextView?  = null
    private var tvInfo: TextView?    = null
    private var etMeshIdx: EditText? = null

    private var sbBandWidth: SeekBar?  = null
    private var etBandWidth: EditText? = null
    private var tvBandInfo: TextView?  = null

    private var sbInnerDia: SeekBar?   = null
    private var etInnerDia: EditText?  = null
    private var tvInnerInfo: TextView? = null

    private var cardBandWidth: View? = null
    private var cardInnerDia: View?  = null

    // Suppress TextWatcher re-entry
    @Volatile private var suppressBW = false
    @Volatile private var suppressID = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx).apply { setBackgroundColor(0x00000000) }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 72)
            setBackgroundResource(R.drawable.bg_bottom_sheet)
        }
        scroll.addView(root)

        // ── Handle bar ────────────────────────────────────────────────────────
        root.addView(LinearLayout(ctx).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL; setPadding(0, 14, 0, 0)
            addView(View(ctx).apply {
                setBackgroundColor(Color.parseColor("#404058"))
                layoutParams = LinearLayout.LayoutParams(48, 4)
            })
        })

        // ── Title ─────────────────────────────────────────────────────────────
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(20, 14, 20, 6)
            addView(TextView(ctx).apply {
                text = "💍  Ring Tool"; textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(ctx).apply {
                text = "v2"; textSize = 9f; letterSpacing = 0.12f
                setTextColor(Color.parseColor("#00D4FF"))
                background = ctx.getDrawable(R.drawable.bg_pill); setPadding(10, 3, 10, 3)
            })
        })
        root.addView(divider(ctx))

        // ── Detection section ─────────────────────────────────────────────────
        root.addView(sectionLabel(ctx, "RING DETECTION"))

        // Mesh index input
        val meshRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(20, 8, 20, 0)
        }
        meshRow.addView(TextView(ctx).apply {
            text = "Mesh index:"; textSize = 11f
            setTextColor(Color.parseColor("#9090B0")); setPadding(0, 0, 12, 0)
        })
        val etIdx = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; setText("0")
            setTextColor(Color.WHITE); textSize = 13f
            background = ctx.getDrawable(R.drawable.bg_input_field); setPadding(10, 8, 10, 8)
            layoutParams = LinearLayout.LayoutParams(80, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        etIdx.addTextChangedListener(simpleWatcher { targetMeshIdx = etIdx.text.toString().toIntOrNull() ?: 0 })
        etMeshIdx = etIdx; meshRow.addView(etIdx)
        root.addView(meshRow)

        // Analyze button
        val btnAnalyze = Button(ctx).apply {
            text = "▶  Auto-Detect Ring Geometry"
            textSize = 12f; setTextColor(Color.parseColor("#050508"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = ctx.getDrawable(R.drawable.bg_btn_accent); setPadding(24, 0, 24, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 52).apply { setMargins(20, 12, 20, 0) }
        }
        root.addView(btnAnalyze)

        // Status
        val tvSt = TextView(ctx).apply {
            text = "⚠  Tap button above to detect ring. Open a ring STL/OBJ first."
            textSize = 10f; setTextColor(Color.parseColor("#FF7043")); setPadding(20, 10, 20, 2)
        }
        tvStatus = tvSt; root.addView(tvSt)

        val tvInf = TextView(ctx).apply {
            text = ""; textSize = 10f; setTextColor(Color.parseColor("#606080")); setPadding(20, 0, 20, 8)
        }
        tvInfo = tvInf; root.addView(tvInf)
        root.addView(divider(ctx))

        // ── Band Width card ───────────────────────────────────────────────────
        val bwCard = buildDeformCard(
            ctx, root,
            title     = "⟵⟶  BAND WIDTH  (Wall Thickness)",
            subtitle  = "Outer wall moves • Inner bore stays fixed",
            accentHex = "#00D4FF"
        ) { valueMM ->
            currBandWidthMM = valueMM
            glRun { NativeLib.nativeSetRingBandWidth(valueMM) }
            activity?.runOnUiThread {
                tvBandInfo?.text = "Band: %.2f mm   →   outer ⌀ %.2f mm"
                    .format(valueMM, (origInnerDiaMM / 2f + valueMM) * 2f)
            }
        }
        cardBandWidth = bwCard
        root.addView(bwCard)
        // Wire refs
        sbBandWidth = bwCard.getTag() as? SeekBar
        etBandWidth = bwCard.findViewWithTag("et") as? EditText
        tvBandInfo  = bwCard.findViewWithTag("info") as? TextView
        bwCard.visibility = View.GONE

        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 10) })

        // ── Inner Diameter card ───────────────────────────────────────────────
        val idCard = buildDeformCard(
            ctx, root,
            title     = "⊙  INNER DIAMETER  (Ring Size)",
            subtitle  = "Inner bore moves • Outer wall & band stay fixed",
            accentHex = "#FF7043"
        ) { valueMM ->
            currInnerDiaMM = valueMM
            glRun { NativeLib.nativeSetRingInnerDiameter(valueMM) }
            activity?.runOnUiThread {
                val innerR = valueMM / 2f
                tvInnerInfo?.text = "Inner ⌀ %.2f mm  (R %.2f mm)  →  finger ~US %.1f"
                    .format(valueMM, innerR, diamToUSRingSize(valueMM))
            }
        }
        cardInnerDia = idCard
        root.addView(idCard)
        sbInnerDia = idCard.getTag() as? SeekBar
        etInnerDia = idCard.findViewWithTag("et") as? EditText
        tvInnerInfo = idCard.findViewWithTag("info") as? TextView
        idCard.visibility = View.GONE

        root.addView(divider(ctx))

        // ── Action row ────────────────────────────────────────────────────────
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(20, 12, 20, 0)
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(Button(ctx).apply {
                text = "↺ Reset"
                textSize = 11f; setTextColor(Color.parseColor("#FF7043"))
                background = ctx.getDrawable(R.drawable.bg_btn_danger); setPadding(20, 0, 20, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, 44).apply { setMargins(0,0,12,0) }
                setOnClickListener {
                    glRun {
                        NativeLib.nativeResetRingDeformation()
                        val p = NativeLib.nativeGetRingParams()
                        activity?.runOnUiThread { if (p.size >= 6) applyRingParams(p) }
                    }
                }
            })
            addView(Button(ctx).apply {
                text = "Re-Detect"
                textSize = 11f; setTextColor(Color.parseColor("#9090B0"))
                background = ctx.getDrawable(R.drawable.bg_card_dark); setPadding(20, 0, 20, 0)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 44)
                setOnClickListener { btnAnalyze.performClick() }
            })
        })

        // ── Analyze button logic ──────────────────────────────────────────────
        btnAnalyze.setOnClickListener {
            val idx = targetMeshIdx
            tvStatus?.text = "⏳  Analyzing ring geometry (PCA + percentile detection)…"
            tvStatus?.setTextColor(Color.parseColor("#FFD54F"))
            tvInfo?.text = ""
            bwCard.visibility = View.GONE
            idCard.visibility = View.GONE

            glRun {
                val ok = NativeLib.nativeAnalyzeRing(idx)
                if (ok) {
                    val p = NativeLib.nativeGetRingParams()
                    activity?.runOnUiThread {
                        if (p.size >= 6) applyRingParams(p)
                        else {
                            tvStatus?.text = "✗ Parameter read failed"
                            tvStatus?.setTextColor(Color.parseColor("#FF5252"))
                        }
                    }
                } else {
                    activity?.runOnUiThread {
                        tvStatus?.text = "✗ Mesh #$idx not detected as a ring.\n  Try mesh index 0 or separate meshes first."
                        tvStatus?.setTextColor(Color.parseColor("#FF5252"))
                    }
                }
            }
        }

        return scroll
    }

    // ── Apply ring params to UI after successful analysis ─────────────────────
    private fun applyRingParams(p: FloatArray) {
        // p = [innerRadMM, outerRadMM, bandWidthMM, innerDiaMM, outerDiaMM, heightMM]
        origInnerRadMM  = p[0]; origOuterRadMM = p[1]; origBandWidthMM = p[2]
        origInnerDiaMM  = p[3];                        origHeightMM    = p[5]
        currBandWidthMM = origBandWidthMM
        currInnerDiaMM  = origInnerDiaMM
        ringAnalyzed    = true

        tvStatus?.text = "✓ Ring detected — adjust sliders below"
        tvStatus?.setTextColor(Color.parseColor("#4CAF82"))
        tvInfo?.text  = "Inner ⌀ %.2fmm  |  Outer ⌀ %.2fmm  |  Band %.2fmm  |  H %.2fmm"
            .format(origInnerDiaMM, p[4], origBandWidthMM, origHeightMM)

        // Set dynamic slider ranges based on actual ring geometry
        bwMin = origBandWidthMM * 0.1f   // 10% of current band
        bwMax = origBandWidthMM * 3.5f   // 350% of current band
        idMin = origInnerDiaMM  * 0.5f   // 50% of current inner diameter
        idMax = origInnerDiaMM  * 2.0f   // 200% of current inner diameter

        // Update band width card
        setSliderRange(sbBandWidth, etBandWidth, bwMin, bwMax, origBandWidthMM,
            "Band: %.2f mm", suppressToken = "bw")
        tvBandInfo?.text = "Band: %.2f mm   →   outer ⌀ %.2f mm"
            .format(origBandWidthMM, p[4])
        cardBandWidth?.visibility = View.VISIBLE

        // Update inner diameter card
        setSliderRange(sbInnerDia, etInnerDia, idMin, idMax, origInnerDiaMM,
            "Inner ⌀: %.2f mm", suppressToken = "id")
        tvInnerInfo?.text = "Inner ⌀ %.2f mm  →  finger ~US %.1f"
            .format(origInnerDiaMM, diamToUSRingSize(origInnerDiaMM))
        cardInnerDia?.visibility = View.VISIBLE
    }

    // ── Compute US ring size from inner diameter (mm) ─────────────────────────
    private fun diamToUSRingSize(diamMM: Float): Float {
        // US ring size ≈ (circumference_mm - 36.5) / 2.55
        val circumference = diamMM * Math.PI.toFloat()
        return ((circumference - 36.5f) / 2.55f).coerceAtLeast(0f)
    }

    // ── Set slider position and range ─────────────────────────────────────────
    private fun setSliderRange(
        sb: SeekBar?, et: EditText?,
        min: Float, max: Float, value: Float,
        @Suppress("UNUSED_PARAMETER") fmt: String,
        suppressToken: String
    ) {
        val progress = ((value - min) / (max - min) * STEPS).toInt().coerceIn(0, STEPS)
        when (suppressToken) {
            "bw" -> { suppressBW = true; sb?.progress = progress
                       et?.setText("%.2f".format(value)); suppressBW = false }
            "id" -> { suppressID = true; sb?.progress = progress
                       et?.setText("%.2f".format(value)); suppressID = false }
        }
    }

    // ── Build a deformation control card ─────────────────────────────────────
    private fun buildDeformCard(
        ctx: android.content.Context,
        @Suppress("UNUSED_PARAMETER") parent: LinearLayout,
        title: String, subtitle: String, accentHex: String,
        onChange: (Float) -> Unit
    ): LinearLayout {
        val accent = Color.parseColor(accentHex)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card_dark)
            setPadding(0, 0, 0, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(14, 8, 14, 0) }
        }

        // Title
        card.addView(TextView(ctx).apply {
            text = title; textSize = 10f; letterSpacing = 0.12f
            setTextColor(Color.parseColor(accentHex)); setPadding(16, 14, 16, 2)
        })
        card.addView(TextView(ctx).apply {
            text = subtitle; textSize = 9f; setTextColor(Color.parseColor("#505070"))
            setPadding(16, 0, 16, 10)
        })

        // Input row
        val inputRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(16, 4, 16, 0)
        }
        val et = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("0.00"); setTextColor(Color.WHITE); textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = ctx.getDrawable(R.drawable.bg_input_field); setPadding(14, 10, 14, 10)
            layoutParams = LinearLayout.LayoutParams(130, LinearLayout.LayoutParams.WRAP_CONTENT)
            tag = "et"  // used by outer code to find this view
        }
        inputRow.addView(et)
        inputRow.addView(TextView(ctx).apply {
            text = " mm"; textSize = 12f; setTextColor(Color.parseColor("#505070"))
        })
        card.addView(inputRow)

        // Seekbar
        val sb = SeekBar(ctx).apply {
            max = STEPS; progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            thumbTintList    = android.content.res.ColorStateList.valueOf(accent)
            setPadding(16, 6, 16, 0)
            tag = this  // self-tag for retrieval
        }
        card.setTag(sb)  // store seekbar as card's tag for retrieval
        card.addView(sb)

        // Info label
        val tvInfo = TextView(ctx).apply {
            text = ""; textSize = 10f; setTextColor(Color.parseColor("#606080"))
            setPadding(16, 6, 16, 0); tag = "info"
        }
        card.addView(tvInfo)

        // ── Wire seekbar → onChange ───────────────────────────────────────────
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(b: SeekBar) {}
            override fun onStopTrackingTouch(b: SeekBar) {}
            override fun onProgressChanged(b: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser || !ringAnalyzed) return
                // Determine which card this is by checking accent color
                val isOuter = accentHex == "#00D4FF"
                val min = if (isOuter) bwMin else idMin
                val max = if (isOuter) bwMax else idMax
                val v = min + p.toFloat() / STEPS * (max - min)
                val txt = "%.2f".format(v)
                if (isOuter) {
                    if (!suppressBW) { suppressBW = true; et.setText(txt); et.setSelection(txt.length); suppressBW = false }
                } else {
                    if (!suppressID) { suppressID = true; et.setText(txt); et.setSelection(txt.length); suppressID = false }
                }
                onChange(v)
            }
        })

        // ── Wire EditText → seekbar + onChange ───────────────────────────────
        val isOuter = accentHex == "#00D4FF"
        et.addTextChangedListener(simpleWatcher {
            if (isOuter && suppressBW) return@simpleWatcher
            if (!isOuter && suppressID) return@simpleWatcher
            if (!ringAnalyzed) return@simpleWatcher
            val v = et.text.toString().toFloatOrNull() ?: return@simpleWatcher
            val min = if (isOuter) bwMin else idMin
            val max = if (isOuter) bwMax else idMax
            if (v < min || v > max) return@simpleWatcher
            val prog = ((v - min) / (max - min) * STEPS).toInt().coerceIn(0, STEPS)
            if (isOuter) { suppressBW = true; sb.progress = prog; suppressBW = false }
            else          { suppressID = true; sb.progress = prog; suppressID = false }
            onChange(v)
        })

        return card
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun simpleWatcher(action: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { action() }
    }

    private fun sectionLabel(ctx: android.content.Context, text: String) = TextView(ctx).apply {
        this.text = text; textSize = 9f; letterSpacing = 0.14f
        setTextColor(Color.parseColor("#00D4FF")); setPadding(20, 18, 20, 6)
    }

    private fun divider(ctx: android.content.Context) = View(ctx).apply {
        setBackgroundColor(Color.parseColor("#1A1A28"))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }

    private fun glRun(block: () -> Unit) =
        (activity as? MainActivity)?.glView?.queueEvent(block)

    companion object {
        const val TAG = "RingTool"
        fun newInstance() = RingToolFragment()
    }
}
