package com.modelviewer3d

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * Bottom-sheet editor with:
 *  • Rotation / Translation / Scale sliders (X,Y,Z)
 *  • Uniform Scale toggle
 *  • Mirror X/Y/Z buttons + Reset Transform
 *  • Color (RGB sliders)
 *  • Lighting (Ambient, Diffuse)
 *  • Wireframe + Bounding Box toggles
 */
class EditorPanelFragment : BottomSheetDialogFragment() {

    // Local state mirrors the renderer's transform
    private var rotX=0f; private var rotY=0f; private var rotZ=0f
    private var posX=0f; private var posY=0f; private var posZ=0f
    private var scaX=1f; private var scaY=1f; private var scaZ=1f
    private var colR=0.7f; private var colG=0.7f; private var colB=0.9f
    private var ambient=0.3f; private var diffuse=0.8f
    private var uniformScale = true

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 48)
        }
        scroll.addView(root)

        // ── Helpers ──────────────────────────────────────────────────────────
        fun label(text: String) = TextView(ctx).apply {
            this.text = text; textSize = 12f
            setTextColor(Color.parseColor("#BBBBBB"))
            setPadding(0, 16, 0, 2)
        }
        fun sectionTitle(text: String) = TextView(ctx).apply {
            this.text = text; textSize = 10f; letterSpacing = 0.12f
            setTextColor(Color.parseColor("#4FC3F7"))
            setPadding(0, 28, 0, 0)
        }

        /** SeekBar mapped to [min, max], calls onChange with float value */
        fun slider(min: Float, max: Float, initVal: Float,
                   onChange: (Float) -> Unit): SeekBar {
            val steps = 1000
            return SeekBar(ctx).apply {
                this.max = steps
                progress = ((initVal - min) / (max - min) * steps).toInt().coerceIn(0, steps)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, p: Int, fromUser: Boolean) {
                        if (fromUser) onChange(min + p.toFloat() / steps * (max - min))
                    }
                    override fun onStartTrackingTouch(bar: SeekBar) {}
                    override fun onStopTrackingTouch(bar: SeekBar) {}
                })
            }
        }

        fun switch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) =
            SwitchMaterial(ctx).apply {
                text = label; isChecked = checked
                setTextColor(Color.WHITE)
                setOnCheckedChangeListener { _, v -> onChange(v) }
            }

        fun glRun(block: () -> Unit) =
            (activity as? MainActivity)?.glView?.queueEvent(block)

        // ── ROTATION ─────────────────────────────────────────────────────────
        root.addView(sectionTitle("ROTATION  (−180° … +180°)"))
        root.addView(label("X"))
        root.addView(slider(-180f,180f,rotX) { v -> rotX=v; glRun{NativeLib.nativeSetRotation(rotX,rotY,rotZ)} })
        root.addView(label("Y"))
        root.addView(slider(-180f,180f,rotY) { v -> rotY=v; glRun{NativeLib.nativeSetRotation(rotX,rotY,rotZ)} })
        root.addView(label("Z"))
        root.addView(slider(-180f,180f,rotZ) { v -> rotZ=v; glRun{NativeLib.nativeSetRotation(rotX,rotY,rotZ)} })

        // ── POSITION ─────────────────────────────────────────────────────────
        root.addView(sectionTitle("POSITION  (−5 … +5)"))
        root.addView(label("X"))
        root.addView(slider(-5f,5f,posX) { v -> posX=v; glRun{NativeLib.nativeSetTranslation(posX,posY,posZ)} })
        root.addView(label("Y"))
        root.addView(slider(-5f,5f,posY) { v -> posY=v; glRun{NativeLib.nativeSetTranslation(posX,posY,posZ)} })
        root.addView(label("Z"))
        root.addView(slider(-5f,5f,posZ) { v -> posZ=v; glRun{NativeLib.nativeSetTranslation(posX,posY,posZ)} })

        // ── SCALE ────────────────────────────────────────────────────────────
        root.addView(sectionTitle("SCALE  (0.05 … 5.0)"))
        root.addView(switch("Uniform Scale", true) { checked -> uniformScale = checked })

        root.addView(label("X"))
        root.addView(slider(0.05f,5f,scaX) { v ->
            scaX=v; if(uniformScale){scaY=v;scaZ=v}
            glRun{NativeLib.nativeSetScale(scaX,scaY,scaZ)}
        })
        root.addView(label("Y"))
        root.addView(slider(0.05f,5f,scaY) { v ->
            scaY=v; if(uniformScale){scaX=v;scaZ=v}
            glRun{NativeLib.nativeSetScale(scaX,scaY,scaZ)}
        })
        root.addView(label("Z"))
        root.addView(slider(0.05f,5f,scaZ) { v ->
            scaZ=v; if(uniformScale){scaX=v;scaY=v}
            glRun{NativeLib.nativeSetScale(scaX,scaY,scaZ)}
        })

        // ── GEOMETRY ─────────────────────────────────────────────────────────
        root.addView(sectionTitle("GEOMETRY"))
        val mirrorRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 0)
        }
        fun mirrorBtn(ax: String, action: () -> Unit) = Button(ctx).apply {
            text = "Flip $ax"; textSize = 12f
            setPadding(16,8,16,8)
            setOnClickListener { glRun { action() } }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        mirrorRow.addView(mirrorBtn("X") { NativeLib.nativeMirrorX() })
        mirrorRow.addView(mirrorBtn("Y") { NativeLib.nativeMirrorY() })
        mirrorRow.addView(mirrorBtn("Z") { NativeLib.nativeMirrorZ() })
        root.addView(mirrorRow)

        root.addView(Button(ctx).apply {
            text = "↺  Reset All Transforms"
            setOnClickListener {
                rotX=0f;rotY=0f;rotZ=0f;posX=0f;posY=0f;posZ=0f;scaX=1f;scaY=1f;scaZ=1f
                glRun { NativeLib.nativeResetTransform() }
            }
        })

        // ── COLOR ────────────────────────────────────────────────────────────
        root.addView(sectionTitle("MODEL COLOR"))
        root.addView(label("Red"))
        root.addView(slider(0f,1f,colR)  { v -> colR=v; glRun{NativeLib.nativeSetColor(colR,colG,colB)} })
        root.addView(label("Green"))
        root.addView(slider(0f,1f,colG)  { v -> colG=v; glRun{NativeLib.nativeSetColor(colR,colG,colB)} })
        root.addView(label("Blue"))
        root.addView(slider(0f,1f,colB)  { v -> colB=v; glRun{NativeLib.nativeSetColor(colR,colG,colB)} })

        // ── LIGHTING ─────────────────────────────────────────────────────────
        root.addView(sectionTitle("LIGHTING"))
        root.addView(label("Ambient"))
        root.addView(slider(0f,1f,ambient) { v -> ambient=v; glRun{NativeLib.nativeSetAmbient(v)} })
        root.addView(label("Diffuse"))
        root.addView(slider(0f,1f,diffuse) { v -> diffuse=v; glRun{NativeLib.nativeSetDiffuse(v)} })

        // ── DISPLAY ──────────────────────────────────────────────────────────
        root.addView(sectionTitle("DISPLAY"))
        root.addView(switch("Wireframe Mode", false) { on ->
            glRun { NativeLib.nativeSetWireframe(on) }
        })
        root.addView(switch("Bounding Box", false) { on ->
            glRun { NativeLib.nativeSetBoundingBox(on) }
        })

        return scroll
    }

    companion object {
        const val TAG = "EditorPanel"
        fun newInstance() = EditorPanelFragment()
    }
}
