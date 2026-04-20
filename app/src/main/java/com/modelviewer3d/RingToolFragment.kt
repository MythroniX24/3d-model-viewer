package com.modelviewer3d

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.*
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.util.concurrent.CountDownLatch

/**
 * Ring Deformation Tool
 *
 * Two independent tools built on top of the Jacobi-PCA ring analysis engine:
 *
 * 1. Band Width (wall thickness): changes outer radius, keeps inner fixed.
 *    Formula: r_new = innerR + f*(newOuterR - innerR)  where f = (r-innerR)/(outerR-innerR)
 *
 * 2. Inner Diameter: changes inner radius, keeps outer (band) fixed.
 *    Formula: r_new = newInnerR + f*(outerR - newInnerR)
 *
 * Both operations use the same radial-fraction interpolation so that face
 * vertices (top/bottom of ring) stretch smoothly between inner and outer edges.
 *
 * All heavy work happens on the GL thread via glView.queueEvent{}.
 */
class RingToolFragment : BottomSheetDialogFragment() {

    // Current ring params (loaded async from GL thread)
    private var innerRadMM = 0f
    private var outerRadMM = 0f
    private var bandWidthMM = 0f
    private var innerDiaMM = 0f
    private var outerDiaMM = 0f
    private var heightMM = 0f
    private var ringAnalyzed = false

    // Selected mesh index for ring analysis
    private var targetMeshIdx = 0

    // UI refs
    private var tvStatus: TextView? = null
    private var tvInfo: TextView? = null
    private var sbBandWidth: SeekBar? = null
    private var etBandWidth: EditText? = null
    private var tvBandCurrent: TextView? = null
    private var sbInnerDia: SeekBar? = null
    private var etInnerDia: EditText? = null
    private var tvInnerCurrent: TextView? = null
    private var btnAnalyze: Button? = null
    private var sectionBandWidth: View? = null
    private var sectionInnerDia: View? = null

    // Slider range bounds
    private val BW_MIN = 0.1f; private val BW_MAX = 20f
    private val ID_MIN = 1.0f; private val ID_MAX = 50f
    private val STEPS = 1000

    // Suppress TextWatcher re-entry
    private var suppressBW = false
    private var suppressID = false

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
        root.addView(LinearLayout(ctx).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(0, 14, 0, 0)
            addView(View(ctx).apply {
                setBackgroundColor(Color.parseColor("#404058"))
                layoutParams = LinearLayout.LayoutParams(48, 4)
            })
        })

        // Title
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(20, 14, 20, 4)
            addView(TextView(ctx).apply {
                text = "💍  Ring Tool"
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(ctx).apply {
                text = "BETA"
                textSize = 8f; letterSpacing = 0.15f
                setTextColor(Color.parseColor("#FFD54F"))
                background = ctx.getDrawable(R.drawable.bg_pill)
                setPadding(10, 3, 10, 3)
            })
        })
        root.addView(divider(ctx))

        // ── Status / detection panel ────────────────────────────────────────
        root.addView(sectionLabel(ctx, "RING DETECTION"))

        // Mesh selector row
        val meshRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(20, 8, 20, 0)
        }
        meshRow.addView(TextView(ctx).apply {
            text = "Mesh #"; textSize = 11f; setTextColor(Color.parseColor("#9090B0"))
            setPadding(0, 0, 8, 0)
        })
        val etMeshIdx = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("0")
            setTextColor(Color.WHITE); textSize = 13f
            background = ctx.getDrawable(R.drawable.bg_input_field)
            setPadding(10, 6, 10, 6)
            layoutParams = LinearLayout.LayoutParams(80, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        etMeshIdx.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                targetMeshIdx = s?.toString()?.toIntOrNull() ?: 0
            }
        })
        meshRow.addView(etMeshIdx)
        root.addView(meshRow)

        // Analyze button
        val analyzeBtn = Button(ctx).apply {
            text = "▶  Auto-Detect Ring"
            textSize = 12f; setTextColor(Color.parseColor("#050508"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = ctx.getDrawable(R.drawable.bg_btn_accent)
            setPadding(24, 0, 24, 0)
        }
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(20, 12, 20, 0)
            addView(analyzeBtn)
        })
        btnAnalyze = analyzeBtn

        // Status label
        val tvSt = TextView(ctx).apply {
            text = "⚠ No ring analyzed yet. Tap the button above."
            textSize = 11f; setTextColor(Color.parseColor("#FF7043"))
            setPadding(20, 10, 20, 4)
        }
        tvStatus = tvSt; root.addView(tvSt)

        // Info label
        val tvInf = TextView(ctx).apply {
            text = ""; textSize = 11f; setTextColor(Color.parseColor("#00D4FF"))
            setPadding(20, 0, 20, 8)
        }
        tvInfo = tvInf; root.addView(tvInf)

        root.addView(divider(ctx))

        // ── Band Width section ──────────────────────────────────────────────
        val bwSection = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        sectionBandWidth = bwSection

        bwSection.addView(sectionLabel(ctx, "BAND WIDTH  (Wall Thickness)"))
        bwSection.addView(TextView(ctx).apply {
            text = "Controls outer radius — inner hole stays fixed"
            textSize = 9f; setTextColor(Color.parseColor("#505070")); setPadding(20, 0, 20, 8)
        })

        // Slider + input row for band width
        val (sbBW, etBW, tvBWCur) = buildSliderRow(ctx, bwSection, "mm",
            BW_MIN, BW_MAX, Color.parseColor("#00D4FF")) { valueMM ->
            if (!suppressBW) glRun { NativeLib.nativeSetRingBandWidth(valueMM) }
        }
        sbBandWidth = sbBW; etBandWidth = etBW; tvBandCurrent = tvBWCur

        // Wire EditText → slider
        etBW.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val v = s?.toString()?.toFloatOrNull() ?: return
                if (v < BW_MIN || v > BW_MAX) return
                suppressBW = true
                sbBW.progress = ((v - BW_MIN) / (BW_MAX - BW_MIN) * STEPS).toInt().coerceIn(0, STEPS)
                suppressBW = false
                glRun { NativeLib.nativeSetRingBandWidth(v) }
            }
        })

        root.addView(bwSection)
        root.addView(divider(ctx))

        // ── Inner Diameter section ──────────────────────────────────────────
        val idSection = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        sectionInnerDia = idSection

        idSection.addView(sectionLabel(ctx, "INNER DIAMETER"))
        idSection.addView(TextView(ctx).apply {
            text = "Controls hole size — band width stays fixed"
            textSize = 9f; setTextColor(Color.parseColor("#505070")); setPadding(20, 0, 20, 8)
        })

        val (sbID, etID, tvIDCur) = buildSliderRow(ctx, idSection, "mm",
            ID_MIN, ID_MAX, Color.parseColor("#FF7043")) { valueMM ->
            if (!suppressID) glRun { NativeLib.nativeSetRingInnerDiameter(valueMM) }
        }
        sbInnerDia = sbID; etInnerDia = etID; tvInnerCurrent = tvIDCur

        etID.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val v = s?.toString()?.toFloatOrNull() ?: return
                if (v < ID_MIN || v > ID_MAX) return
                suppressID = true
                sbID.progress = ((v - ID_MIN) / (ID_MAX - ID_MIN) * STEPS).toInt().coerceIn(0, STEPS)
                suppressID = false
                glRun { NativeLib.nativeSetRingInnerDiameter(v) }
            }
        })

        root.addView(idSection)
        root.addView(divider(ctx))

        // ── Action buttons ──────────────────────────────────────────────────
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(20, 12, 20, 0); gravity = android.view.Gravity.CENTER_VERTICAL

            // Reset
            addView(Button(ctx).apply {
                text = "↺ Reset"
                textSize = 11f; setTextColor(Color.parseColor("#FF7043"))
                background = ctx.getDrawable(R.drawable.bg_btn_danger)
                setPadding(20, 0, 20, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, 44).apply { setMargins(0,0,12,0) }
                setOnClickListener {
                    glRun {
                        NativeLib.nativeResetRingDeformation()
                        // Re-fetch params after reset
                        val p = NativeLib.nativeGetRingParams()
                        activity?.runOnUiThread { applyRingParams(p) }
                    }
                }
            })

            // Re-analyze
            addView(Button(ctx).apply {
                text = "Re-Detect"
                textSize = 11f; setTextColor(Color.parseColor("#9090B0"))
                background = ctx.getDrawable(R.drawable.bg_card_dark)
                setPadding(20, 0, 20, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, 44)
                setOnClickListener { analyzeBtn.performClick() }
            })
        })

        // ── Analyze button wiring ───────────────────────────────────────────
        analyzeBtn.setOnClickListener {
            val idx = targetMeshIdx
            tvStatus?.text = "⏳ Analyzing ring geometry…"
            tvStatus?.setTextColor(Color.parseColor("#FFD54F"))
            tvInfo?.text = ""

            glRun {
                val ok = NativeLib.nativeAnalyzeRing(idx)
                if (ok) {
                    val p = NativeLib.nativeGetRingParams()
                    activity?.runOnUiThread { applyRingParams(p) }
                } else {
                    activity?.runOnUiThread {
                        tvStatus?.text = "✗ Mesh #$idx is not a valid ring shape.\n  Try a different mesh index."
                        tvStatus?.setTextColor(Color.parseColor("#FF5252"))
                        bwSection.visibility = View.GONE
                        idSection.visibility = View.GONE
                    }
                }
            }
        }

        return scroll
    }

    /** Called when ring analysis succeeds — populate all UI fields */
    private fun applyRingParams(p: FloatArray) {
        // p = [innerRadMM, outerRadMM, bandWidthMM, innerDiaMM, outerDiaMM, heightMM]
        innerRadMM  = p[0]; outerRadMM = p[1]; bandWidthMM = p[2]
        innerDiaMM  = p[3]; outerDiaMM = p[4]; heightMM    = p[5]
        ringAnalyzed = true

        tvStatus?.text = "✓ Ring detected successfully"
        tvStatus?.setTextColor(Color.parseColor("#4CAF82"))
        tvInfo?.text = "Inner ⌀ %.2fmm  |  Outer ⌀ %.2fmm  |  Band %.2fmm  |  H %.2fmm"
            .format(innerDiaMM, outerDiaMM, bandWidthMM, heightMM)

        // Show sections
        sectionBandWidth?.visibility = View.VISIBLE
        sectionInnerDia?.visibility  = View.VISIBLE

        // Set slider positions (suppress callbacks)
        suppressBW = true
        sbBandWidth?.progress = ((bandWidthMM - BW_MIN) / (BW_MAX - BW_MIN) * STEPS)
            .toInt().coerceIn(0, STEPS)
        etBandWidth?.setText("%.2f".format(bandWidthMM))
        tvBandCurrent?.text = "Current: %.2f mm  (outer ⌀ %.2f mm)".format(bandWidthMM, outerDiaMM)
        suppressBW = false

        suppressID = true
        sbInnerDia?.progress = ((innerDiaMM - ID_MIN) / (ID_MAX - ID_MIN) * STEPS)
            .toInt().coerceIn(0, STEPS)
        etInnerDia?.setText("%.2f".format(innerDiaMM))
        tvInnerCurrent?.text = "Current: ⌀ %.2f mm  (inner R %.2f mm)".format(innerDiaMM, innerRadMM)
        suppressID = false
    }

    /**
     * Build a slider + EditText input row.
     * Returns (SeekBar, EditText, status TextView).
     */
    private fun buildSliderRow(
        ctx: android.content.Context,
        parent: LinearLayout,
        unit: String,
        min: Float, max: Float,
        accentColor: Int,
        onChange: (Float) -> Unit
    ): Triple<SeekBar, EditText, TextView> {

        // Input row (EditText + unit label)
        val inputRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(20, 8, 20, 0)
        }
        val et = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("%.2f".format(min))
            setTextColor(Color.WHITE); textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = ctx.getDrawable(R.drawable.bg_input_field)
            setPadding(14, 10, 14, 10)
            layoutParams = LinearLayout.LayoutParams(130, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        inputRow.addView(et)
        inputRow.addView(TextView(ctx).apply {
            text = " $unit"; textSize = 12f; setTextColor(Color.parseColor("#505070"))
            setPadding(4, 0, 0, 0)
        })
        // Range info
        inputRow.addView(TextView(ctx).apply {
            text = "  [%.1f – %.1f %s]".format(min, max, unit)
            textSize = 9f; setTextColor(Color.parseColor("#404060"))
        })
        parent.addView(inputRow)

        // Seek bar
        val sb = SeekBar(ctx).apply {
            this.max = STEPS
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(accentColor)
            thumbTintList    = android.content.res.ColorStateList.valueOf(accentColor)
            setPadding(20, 4, 20, 4)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val v = min + p.toFloat() / STEPS * (max - min)
                    // Update edit text silently
                    val txt = "%.2f".format(v)
                    if (et.text?.toString() != txt) {
                        et.setText(txt)
                        et.setSelection(txt.length)
                    }
                    onChange(v)
                }
                override fun onStartTrackingTouch(b: SeekBar) {}
                override fun onStopTrackingTouch(b: SeekBar) {}
            })
        }
        parent.addView(sb)

        // Status label
        val tvCur = TextView(ctx).apply {
            text = ""; textSize = 10f
            setTextColor(Color.parseColor("#606080")); setPadding(20, 2, 20, 10)
        }
        parent.addView(tvCur)

        return Triple(sb, et, tvCur)
    }

    private fun sectionLabel(ctx: android.content.Context, text: String) = TextView(ctx).apply {
        this.text = text; textSize = 9f; letterSpacing = 0.14f
        setTextColor(Color.parseColor("#00D4FF"))
        setPadding(20, 18, 20, 6)
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
