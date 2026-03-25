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
 * Editor bottom-sheet — uses plain android.widget.Switch (not SwitchMaterial)
 * to avoid Material dependency crash on some devices.
 */
class EditorPanelFragment : BottomSheetDialogFragment() {

    private var rotX=0f; private var rotY=0f; private var rotZ=0f
    private var posX=0f; private var posY=0f; private var posZ=0f
    private var curWmm=100f; private var curHmm=100f; private var curDmm=100f
    private var origWmm=100f; private var origHmm=100f; private var origDmm=100f
    private var colR=0.72f; private var colG=0.72f; private var colB=0.92f
    private var ambient=0.3f; private var diffuse=0.8f
    private var uniformScale = true

    private var etW: EditText? = null
    private var etH: EditText? = null
    private var etD: EditText? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()

        // Fetch mm sizes async — don't block UI creation
        (activity as? MainActivity)?.glView?.queueEvent {
            try {
                val s = NativeLib.nativeGetModelSizeMM()
                origWmm=s[0]; origHmm=s[1]; origDmm=s[2]
                curWmm =s[3]; curHmm =s[4]; curDmm =s[5]
                // Update fields on main thread
                activity?.runOnUiThread {
                    etW?.setText("%.2f".format(curWmm))
                    etH?.setText("%.2f".format(curHmm))
                    etD?.setText("%.2f".format(curDmm))
                }
            } catch (_: Exception) {}
        }

        val scroll = ScrollView(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 60)
            setBackgroundColor(Color.parseColor("#1C1C22"))
        }
        scroll.addView(root)
        scroll.setBackgroundColor(Color.parseColor("#1C1C22"))

        // ── Helpers ──────────────────────────────────────────────────────────
        fun sectionTitle(t: String) = TextView(ctx).apply {
            text=t; textSize=10f; letterSpacing=0.14f
            setTextColor(Color.parseColor("#4FC3F7"))
            setPadding(0,28,0,6)
        }
        fun fieldLabel(t: String) = TextView(ctx).apply {
            text=t; textSize=11f
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(0,8,0,2)
        }
        fun slider(min: Float, max: Float, init: Float, cb: (Float)->Unit): SeekBar {
            val steps = 1000
            return SeekBar(ctx).apply {
                this.max = steps
                progress = ((init-min)/(max-min)*steps).toInt().coerceIn(0, steps)
                setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(b: SeekBar, p: Int, fromUser: Boolean) {
                        if (fromUser) cb(min + p.toFloat()/steps*(max-min))
                    }
                    override fun onStartTrackingTouch(b: SeekBar) {}
                    override fun onStopTrackingTouch(b: SeekBar) {}
                })
            }
        }
        // Use plain android Switch — works on all API 24+ devices
        fun toggle(label: String, checked: Boolean, cb: (Boolean)->Unit) =
            Switch(ctx).apply {
                text=label; isChecked=checked
                setTextColor(Color.WHITE)
                setPadding(0,8,0,8)
                setOnCheckedChangeListener { _, v -> cb(v) }
            }
        fun divider() = View(ctx).apply {
            setBackgroundColor(Color.parseColor("#33334A"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { setMargins(0,20,0,0) }
        }

        // Handle at top
        root.addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor("#555566"))
            layoutParams = LinearLayout.LayoutParams(60,4).apply {
                gravity=Gravity.CENTER_HORIZONTAL; setMargins(0,12,0,8)
            }
        })
        root.addView(TextView(ctx).apply {
            text="✏️  Model Editor"; textSize=15f; gravity=Gravity.CENTER
            setTextColor(Color.WHITE); setPadding(0,0,0,8)
        })
        root.addView(divider())

        // ── ROTATION ─────────────────────────────────────────────────────────
        root.addView(sectionTitle("ROTATION  (degrees)"))
        listOf(
            Triple("X", { v:Float -> rotX=v }, rotX),
            Triple("Y", { v:Float -> rotY=v }, rotY),
            Triple("Z", { v:Float -> rotZ=v }, rotZ)
        ).forEach { (ax, assign, init) ->
            root.addView(fieldLabel(ax))
            root.addView(slider(-180f, 180f, init) { v ->
                assign(v)
                glRun { NativeLib.nativeSetRotation(rotX, rotY, rotZ) }
            })
        }
        root.addView(divider())

        // ── POSITION ─────────────────────────────────────────────────────────
        root.addView(sectionTitle("POSITION"))
        listOf(
            Triple("X", { v:Float -> posX=v }, posX),
            Triple("Y", { v:Float -> posY=v }, posY),
            Triple("Z", { v:Float -> posZ=v }, posZ)
        ).forEach { (ax, assign, init) ->
            root.addView(fieldLabel(ax))
            root.addView(slider(-5f, 5f, init) { v ->
                assign(v)
                glRun { NativeLib.nativeSetTranslation(posX, posY, posZ) }
            })
        }
        root.addView(divider())

        // ── DIMENSIONS in MM ─────────────────────────────────────────────────
        root.addView(sectionTitle("DIMENSIONS  (mm)"))
        root.addView(TextView(ctx).apply {
            text="Original: %.1f × %.1f × %.1f mm".format(origWmm, origHmm, origDmm)
            textSize=10f; setTextColor(Color.parseColor("#888888"))
        })
        root.addView(toggle("Uniform Scale (lock ratio)", true) { checked -> uniformScale=checked })

        fun mmInputRow(axLabel: String, initVal: Float, origVal: Float): EditText {
            val row = LinearLayout(ctx).apply {
                orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
                setPadding(0,6,0,0)
            }
            row.addView(TextView(ctx).apply {
                text=axLabel; textSize=12f; setTextColor(Color.parseColor("#CCCCCC"))
                layoutParams=LinearLayout.LayoutParams(28, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            val et = EditText(ctx).apply {
                inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText("%.2f".format(initVal))
                setTextColor(Color.WHITE); textSize=14f
                setBackgroundColor(Color.parseColor("#2A2A38"))
                setPadding(16,10,16,10)
                layoutParams=LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(et)
            row.addView(TextView(ctx).apply {
                text=" mm"; textSize=11f; setTextColor(Color.parseColor("#888888"))
            })
            root.addView(row)

            et.addTextChangedListener(object: TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val v = s?.toString()?.toFloatOrNull() ?: return
                    if (v < 0.001f) return
                    val wv: Float; val hv: Float; val dv: Float
                    if (uniformScale && origWmm>0 && origHmm>0 && origDmm>0) {
                        val ratio = v / origVal
                        val nw = origWmm*ratio; val nh = origHmm*ratio; val nd = origDmm*ratio
                        // Update the other fields without triggering their watchers
                        if (axLabel == "W") { silentSet(etH, nh); silentSet(etD, nd) }
                        if (axLabel == "H") { silentSet(etW, nw); silentSet(etD, nd) }
                        if (axLabel == "D") { silentSet(etW, nw); silentSet(etH, nh) }
                        glRun { NativeLib.nativeSetScaleMM(nw, nh, nd) }
                    } else {
                        wv = etW?.text?.toString()?.toFloatOrNull() ?: curWmm
                        hv = etH?.text?.toString()?.toFloatOrNull() ?: curHmm
                        dv = etD?.text?.toString()?.toFloatOrNull() ?: curDmm
                        glRun { NativeLib.nativeSetScaleMM(wv, hv, dv) }
                    }
                }
            })
            return et
        }

        etW = mmInputRow("W", curWmm, origWmm)
        etH = mmInputRow("H", curHmm, origHmm)
        etD = mmInputRow("D", curDmm, origDmm)

        root.addView(Button(ctx).apply {
            text = "Reset to Original Size"
            textSize = 11f
            setOnClickListener {
                silentSet(etW, origWmm); silentSet(etH, origHmm); silentSet(etD, origDmm)
                glRun { NativeLib.nativeSetScaleMM(origWmm, origHmm, origDmm) }
            }
        })
        root.addView(divider())

        // ── GEOMETRY ─────────────────────────────────────────────────────────
        root.addView(sectionTitle("GEOMETRY"))
        val mirrorRow = LinearLayout(ctx).apply {
            orientation=LinearLayout.HORIZONTAL; setPadding(0,8,0,0)
        }
        listOf(
            "Flip X" to { NativeLib.nativeMirrorX() },
            "Flip Y" to { NativeLib.nativeMirrorY() },
            "Flip Z" to { NativeLib.nativeMirrorZ() }
        ).forEach { (lbl, action) ->
            mirrorRow.addView(Button(ctx).apply {
                text=lbl; textSize=11f
                setOnClickListener { glRun { action() } }
                layoutParams=LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        root.addView(mirrorRow)
        root.addView(Button(ctx).apply {
            text = "↺  Reset All Transforms"
            setOnClickListener {
                rotX=0f; rotY=0f; rotZ=0f; posX=0f; posY=0f; posZ=0f
                glRun { NativeLib.nativeResetTransform() }
            }
        })
        root.addView(divider())

        // ── COLOR ─────────────────────────────────────────────────────────────
        root.addView(sectionTitle("MODEL COLOR"))
        root.addView(fieldLabel("Red"))
        root.addView(slider(0f,1f,colR)   { v -> colR=v; glRun { NativeLib.nativeSetColor(colR,colG,colB) } })
        root.addView(fieldLabel("Green"))
        root.addView(slider(0f,1f,colG)   { v -> colG=v; glRun { NativeLib.nativeSetColor(colR,colG,colB) } })
        root.addView(fieldLabel("Blue"))
        root.addView(slider(0f,1f,colB)   { v -> colB=v; glRun { NativeLib.nativeSetColor(colR,colG,colB) } })
        root.addView(divider())

        // ── LIGHTING ─────────────────────────────────────────────────────────
        root.addView(sectionTitle("LIGHTING"))
        root.addView(fieldLabel("Ambient"))
        root.addView(slider(0f,1f,ambient) { v -> ambient=v; glRun { NativeLib.nativeSetAmbient(v) } })
        root.addView(fieldLabel("Diffuse"))
        root.addView(slider(0f,1f,diffuse) { v -> diffuse=v; glRun { NativeLib.nativeSetDiffuse(v) } })
        root.addView(divider())

        // ── DISPLAY ───────────────────────────────────────────────────────────
        root.addView(sectionTitle("DISPLAY"))
        root.addView(toggle("Wireframe Mode", false) { on -> glRun { NativeLib.nativeSetWireframe(on) } })
        root.addView(toggle("Bounding Box",   false) { on -> glRun { NativeLib.nativeSetBoundingBox(on) } })

        return scroll
    }

    /** Set EditText value without triggering TextWatcher */
    private fun silentSet(et: EditText?, value: Float) {
        et ?: return
        val txt = "%.2f".format(value)
        if (et.text?.toString() != txt) {
            val watcher = et.tag as? TextWatcher
            watcher?.let { et.removeTextChangedListener(it) }
            et.setText(txt)
            watcher?.let { et.addTextChangedListener(it) }
        }
    }

    private fun glRun(block: () -> Unit) =
        (activity as? MainActivity)?.glView?.queueEvent(block)

    companion object {
        const val TAG = "EditorPanel"
        fun newInstance() = EditorPanelFragment()
    }
}
