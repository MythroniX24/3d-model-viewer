package com.modelviewer3d

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.*
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlin.math.abs

/**
 * Enhanced editor bottom-sheet:
 *  • Rotation X/Y/Z sliders (degrees)
 *  • Position X/Y/Z sliders
 *  • Scale in mm (EditText fields, like Blender) — no sliders
 *  • Mirror + Reset buttons
 *  • Color RGB sliders
 *  • Lighting (Ambient, Diffuse)
 *  • Wireframe + Bounding Box toggles
 *  • Ruler Measurement section (activate from MainActivity)
 */
class EditorPanelFragment : BottomSheetDialogFragment() {

    private var rotX=0f; private var rotY=0f; private var rotZ=0f
    private var posX=0f; private var posY=0f; private var posZ=0f
    private var curWmm=100f; private var curHmm=100f; private var curDmm=100f
    private var origWmm=100f; private var origHmm=100f; private var origDmm=100f
    private var colR=0.72f; private var colG=0.72f; private var colB=0.92f
    private var ambient=0.3f; private var diffuse=0.8f
    private var uniformScale = true

    // EditText refs so we can update them without triggering callbacks
    private var etW: EditText? = null
    private var etH: EditText? = null
    private var etD: EditText? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        // Fetch current mm sizes from renderer
        glRun {
            val s = NativeLib.nativeGetModelSizeMM()
            origWmm=s[0]; origHmm=s[1]; origDmm=s[2]
            curWmm=s[3];  curHmm=s[4];  curDmm=s[5]
        }

        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 60)
            setBackgroundColor(Color.parseColor("#1C1C22"))
        }
        scroll.addView(root)
        scroll.setBackgroundColor(Color.parseColor("#1C1C22"))

        // ── UI helpers ────────────────────────────────────────────────────────
        fun sectionTitle(t: String) = TextView(ctx).apply {
            text = t; textSize = 10f; letterSpacing = 0.14f
            setTextColor(Color.parseColor("#4FC3F7"))
            setPadding(0, 32, 0, 8)
        }
        fun fieldLabel(t: String) = TextView(ctx).apply {
            text = t; textSize = 11f
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(0, 8, 0, 2)
        }
        fun slider(min: Float, max: Float, init: Float, cb: (Float)->Unit): SeekBar {
            val steps = 1000
            return SeekBar(ctx).apply {
                this.max = steps
                progress = ((init-min)/(max-min)*steps).toInt().coerceIn(0,steps)
                setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(b: SeekBar, p: Int, fromUser: Boolean) {
                        if (fromUser) cb(min+p.toFloat()/steps*(max-min))
                    }
                    override fun onStartTrackingTouch(b: SeekBar){}
                    override fun onStopTrackingTouch(b: SeekBar){}
                })
            }
        }
        fun switch(label: String, checked: Boolean, cb: (Boolean)->Unit) =
            SwitchMaterial(ctx).apply {
                text=label; isChecked=checked
                setTextColor(Color.WHITE)
                setPadding(0,8,0,0)
                setOnCheckedChangeListener{_,v->cb(v)}
            }

        // ── Divider ───────────────────────────────────────────────────────────
        fun divider() = View(ctx).apply {
            setBackgroundColor(Color.parseColor("#333340"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { setMargins(0,24,0,0) }
        }

        // ── Handle at top ─────────────────────────────────────────────────────
        root.addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor("#555566"))
            layoutParams = LinearLayout.LayoutParams(60, 4).apply {
                gravity = Gravity.CENTER_HORIZONTAL; setMargins(0,12,0,8)
            }
        })
        root.addView(TextView(ctx).apply {
            text = "✏️  Model Editor"; textSize = 15f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE); setPadding(0,0,0,8)
        })
        root.addView(divider())

        // ── ROTATION ─────────────────────────────────────────────────────────
        root.addView(sectionTitle("ROTATION  (degrees)"))
        listOf("X" to {v:Float->rotX=v}, "Y" to {v:Float->rotY=v}, "Z" to {v:Float->rotZ=v}).forEach{(ax,assign)->
            root.addView(fieldLabel(ax))
            root.addView(slider(-180f,180f,0f){ v-> assign(v); glRun{NativeLib.nativeSetRotation(rotX,rotY,rotZ)} })
        }
        root.addView(divider())

        // ── POSITION ─────────────────────────────────────────────────────────
        root.addView(sectionTitle("POSITION"))
        listOf("X" to {v:Float->posX=v}, "Y" to {v:Float->posY=v}, "Z" to {v:Float->posZ=v}).forEach{(ax,assign)->
            root.addView(fieldLabel(ax))
            root.addView(slider(-5f,5f,0f){ v-> assign(v); glRun{NativeLib.nativeSetTranslation(posX,posY,posZ)} })
        }
        root.addView(divider())

        // ── SCALE in MM ───────────────────────────────────────────────────────
        root.addView(sectionTitle("DIMENSIONS  (mm)"))
        root.addView(TextView(ctx).apply {
            text = "Original: %.1f × %.1f × %.1f mm".format(origWmm,origHmm,origDmm)
            textSize = 10f; setTextColor(Color.parseColor("#888888"))
        })
        root.addView(switch("Uniform Scale (lock ratio)", true){ checked -> uniformScale=checked })

        // Helper: build mm input row  "W  [____] mm"
        fun mmRow(axLabel: String, initVal: Float, getOther1: ()->Float, getOther2: ()->Float,
                  orig1: Float, orig2: Float,
                  onSet: (Float)->Unit): EditText {
            val row = LinearLayout(ctx).apply {
                orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
                setPadding(0,8,0,0)
            }
            row.addView(TextView(ctx).apply {
                text=axLabel; textSize=12f; setTextColor(Color.parseColor("#BBBBBB"))
                layoutParams=LinearLayout.LayoutParams(32,LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            val et = EditText(ctx).apply {
                inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText("%.2f".format(initVal))
                setTextColor(Color.WHITE); textSize=14f
                setBackgroundColor(Color.parseColor("#2A2A35"))
                setPadding(16,10,16,10)
                layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)
            }
            row.addView(et)
            row.addView(TextView(ctx).apply {
                text=" mm"; textSize=11f; setTextColor(Color.parseColor("#888888"))
            })
            root.addView(row)

            et.addTextChangedListener(object: TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){}
                override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){}
                override fun afterTextChanged(s: Editable?) {
                    val v = s?.toString()?.toFloatOrNull() ?: return
                    if (v < 0.001f) return
                    onSet(v)
                    val w:Float; val h:Float; val d:Float
                    if (uniformScale && orig1 > 0f && orig2 > 0f) {
                        val factor = v / initVal  // ratio change won't work well; use orig ratio
                        // Lock: scale others proportionally based on original aspect
                    }
                    // Simple approach: read all 3 ET values when any changes
                    val wv = etW?.text?.toString()?.toFloatOrNull() ?: curWmm
                    val hv = etH?.text?.toString()?.toFloatOrNull() ?: curHmm
                    val dv = etD?.text?.toString()?.toFloatOrNull() ?: curDmm
                    if (uniformScale && axLabel != "?") {
                        // Propagate proportionally from the changed axis
                        val ratio = v / when(axLabel){"W"->origWmm;"H"->origHmm;else->origDmm}
                        val newW = origWmm*ratio; val newH = origHmm*ratio; val newD = origDmm*ratio
                        if (axLabel=="W"){ etH?.setText("%.2f".format(newH)); etD?.setText("%.2f".format(newD)) }
                        if (axLabel=="H"){ etW?.setText("%.2f".format(newW)); etD?.setText("%.2f".format(newD)) }
                        if (axLabel=="D"){ etW?.setText("%.2f".format(newW)); etH?.setText("%.2f".format(newH)) }
                        glRun { NativeLib.nativeSetScaleMM(newW,newH,newD) }
                    } else {
                        glRun { NativeLib.nativeSetScaleMM(wv,hv,dv) }
                    }
                }
            })
            return et
        }

        etW = mmRow("W", curWmm, {curHmm},{curDmm}, origHmm,origDmm, {curWmm=it})
        etH = mmRow("H", curHmm, {curWmm},{curDmm}, origWmm,origDmm, {curHmm=it})
        etD = mmRow("D", curDmm, {curWmm},{curHmm}, origWmm,origHmm, {curDmm=it})

        // Reset to original mm
        root.addView(Button(ctx).apply {
            text = "Reset to Original Size (%.0f×%.0f×%.0f mm)".format(origWmm,origHmm,origDmm)
            textSize = 11f
            setOnClickListener {
                etW?.setText("%.2f".format(origWmm))
                etH?.setText("%.2f".format(origHmm))
                etD?.setText("%.2f".format(origDmm))
                glRun { NativeLib.nativeSetScaleMM(origWmm,origHmm,origDmm) }
            }
        })
        root.addView(divider())

        // ── GEOMETRY ─────────────────────────────────────────────────────────
        root.addView(sectionTitle("GEOMETRY"))
        val mirrorRow = LinearLayout(ctx).apply {
            orientation=LinearLayout.HORIZONTAL; setPadding(0,8,0,0)
        }
        listOf("Flip X" to {NativeLib.nativeMirrorX()},
               "Flip Y" to {NativeLib.nativeMirrorY()},
               "Flip Z" to {NativeLib.nativeMirrorZ()}).forEach{(lbl,action)->
            mirrorRow.addView(Button(ctx).apply {
                text=lbl; textSize=11f; setPadding(8,4,8,4)
                setOnClickListener{ glRun{action()} }
                layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)
            })
        }
        root.addView(mirrorRow)
        root.addView(Button(ctx).apply {
            text = "↺  Reset All Transforms"
            setOnClickListener {
                rotX=0f;rotY=0f;rotZ=0f;posX=0f;posY=0f;posZ=0f
                glRun { NativeLib.nativeResetTransform() }
            }
        })
        root.addView(divider())

        // ── COLOR ─────────────────────────────────────────────────────────────
        root.addView(sectionTitle("MODEL COLOR"))
        root.addView(fieldLabel("Red"))
        root.addView(slider(0f,1f,colR){ v->colR=v; glRun{NativeLib.nativeSetColor(colR,colG,colB)} })
        root.addView(fieldLabel("Green"))
        root.addView(slider(0f,1f,colG){ v->colG=v; glRun{NativeLib.nativeSetColor(colR,colG,colB)} })
        root.addView(fieldLabel("Blue"))
        root.addView(slider(0f,1f,colB){ v->colB=v; glRun{NativeLib.nativeSetColor(colR,colG,colB)} })
        root.addView(divider())

        // ── LIGHTING ─────────────────────────────────────────────────────────
        root.addView(sectionTitle("LIGHTING"))
        root.addView(fieldLabel("Ambient"))
        root.addView(slider(0f,1f,ambient){ v->ambient=v; glRun{NativeLib.nativeSetAmbient(v)} })
        root.addView(fieldLabel("Diffuse"))
        root.addView(slider(0f,1f,diffuse){ v->diffuse=v; glRun{NativeLib.nativeSetDiffuse(v)} })
        root.addView(divider())

        // ── DISPLAY ───────────────────────────────────────────────────────────
        root.addView(sectionTitle("DISPLAY"))
        root.addView(switch("Wireframe Mode", false){ on-> glRun{NativeLib.nativeSetWireframe(on)} })
        root.addView(switch("Bounding Box",   false){ on-> glRun{NativeLib.nativeSetBoundingBox(on)} })

        return scroll
    }

    private fun glRun(block: ()->Unit) =
        (activity as? MainActivity)?.glView?.queueEvent(block)

    companion object {
        const val TAG = "EditorPanel"
        fun newInstance() = EditorPanelFragment()
    }
}
