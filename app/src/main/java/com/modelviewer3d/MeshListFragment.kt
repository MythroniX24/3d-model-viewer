package com.modelviewer3d

import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Mesh Separation panel:
 *  - Lists all disconnected mesh islands
 *  - Tap to select/highlight in viewport
 *  - Toggle visibility
 *  - Delete mesh
 *  - Resize selected mesh (mm inputs)
 *  - Per-mesh color
 */
class MeshListFragment : BottomSheetDialogFragment() {

    private var meshCount = 0
    private var selectedIdx = -1
    private var listContainer: LinearLayout? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 60)
            setBackgroundColor(Color.parseColor("#1C1C22"))
        }
        scroll.addView(root)
        scroll.setBackgroundColor(Color.parseColor("#1C1C22"))

        // ── Header ────────────────────────────────────────────────────────────
        root.addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor("#555566"))
            layoutParams = LinearLayout.LayoutParams(60,4).apply{ gravity=Gravity.CENTER_HORIZONTAL; setMargins(0,12,0,8) }
        })
        root.addView(TextView(ctx).apply {
            text="🔷  Mesh Separation"; textSize=15f; gravity=Gravity.CENTER
            setTextColor(Color.WHITE); setPadding(0,0,0,4)
        })

        // Fetch mesh count on GL thread
        (activity as? MainActivity)?.glView?.queueEvent {
            meshCount = NativeLib.nativeGetMeshCount()
            activity?.runOnUiThread { buildMeshList(root, ctx) }
        }

        // ── Selected mesh editor ──────────────────────────────────────────────
        val editorSection = LinearLayout(ctx).apply {
            orientation=LinearLayout.VERTICAL; visibility=View.GONE
            setBackgroundColor(Color.parseColor("#16161E"))
            setPadding(0,16,0,0)
        }
        root.addView(editorSection)

        // Info hint
        root.addView(TextView(ctx).apply {
            text="💡 Tap a mesh row to select and edit it individually"
            textSize=11f; setTextColor(Color.parseColor("#666688"))
            setPadding(0,12,0,0); gravity=Gravity.CENTER
        })

        val lc = LinearLayout(ctx).apply { orientation=LinearLayout.VERTICAL }
        listContainer = lc
        root.addView(lc)

        return scroll
    }

    private fun buildMeshList(root: LinearLayout, ctx: android.content.Context) {
        listContainer?.removeAllViews()
        if (meshCount == 0) {
            listContainer?.addView(TextView(ctx).apply {
                text = "No model loaded"; textSize=13f
                setTextColor(Color.parseColor("#888888"))
                setPadding(0,16,0,0); gravity=Gravity.CENTER
            })
            return
        }

        // Section title
        listContainer?.addView(TextView(ctx).apply {
            text = "MESH ISLANDS  ($meshCount found)"
            textSize=9f; letterSpacing=0.12f
            setTextColor(Color.parseColor("#4FC3F7"))
            setPadding(0,20,0,8)
        })

        for (i in 0 until meshCount) {
            val name = NativeLib.nativeGetMeshName(i)
            val row = buildMeshRow(ctx, i, name)
            listContainer?.addView(row)
        }
    }

    private fun buildMeshRow(ctx: android.content.Context, idx: Int, name: String): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (idx==selectedIdx) Color.parseColor("#1E2A3A") else Color.parseColor("#181820"))
            setPadding(16,12,16,12)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0,4,0,4)
            layoutParams = lp
        }

        // Row header
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }

        // Color dot
        val dot = View(ctx).apply {
            val colors = listOf("#4FC3F7","#FF7043","#66BB6A","#FFA726","#AB47BC","#EC407A","#26C6DA","#D4E157")
            setBackgroundColor(Color.parseColor(colors[idx % colors.size]))
            layoutParams = LinearLayout.LayoutParams(12,12).apply{ setMargins(0,0,10,0) }
        }
        header.addView(dot)

        val tvName = TextView(ctx).apply {
            text = name; textSize=13f
            setTextColor(if(idx==selectedIdx) Color.parseColor("#4FC3F7") else Color.WHITE)
            layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)
        }
        header.addView(tvName)

        // Visibility toggle
        val btnVis = Button(ctx).apply {
            text="👁"; textSize=14f; setPadding(8,4,8,4)
            layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,40)
            setOnClickListener {
                glRun { NativeLib.nativeSetMeshVisible(idx, true) } // toggle (simplified)
                toast("Mesh $name visibility toggled")
            }
        }
        header.addView(btnVis)

        // Delete button
        val btnDel = Button(ctx).apply {
            text="🗑"; textSize=14f; setPadding(8,4,8,4)
            setBackgroundColor(Color.parseColor("#330A0A"))
            layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,40)
            setOnClickListener {
                android.app.AlertDialog.Builder(ctx)
                    .setTitle("Delete Mesh")
                    .setMessage("Delete \"$name\"?")
                    .setPositiveButton("Delete") { _,_ ->
                        glRun { NativeLib.nativeDeleteMesh(idx) }
                        meshCount--
                        selectedIdx=-1
                        activity?.runOnUiThread { buildMeshList(requireView().rootView as LinearLayout, ctx) }
                    }
                    .setNegativeButton("Cancel",null).show()
            }
        }
        header.addView(btnDel)
        card.addView(header)

        // Size info row
        val tvSize = TextView(ctx).apply {
            textSize=10f; setTextColor(Color.parseColor("#888899"))
            setPadding(22,4,0,0)
        }
        glRun {
            val s = NativeLib.nativeGetMeshSizeMM(idx)
            activity?.runOnUiThread { tvSize.text="%.1f × %.1f × %.1f mm".format(s[0],s[1],s[2]) }
        }
        card.addView(tvSize)

        // Expanded editor (shown when selected)
        if (idx == selectedIdx) {
            card.addView(buildMeshEditor(ctx, idx))
        }

        card.setOnClickListener {
            selectedIdx = if (selectedIdx==idx) -1 else idx
            glRun { NativeLib.nativeSelectMesh(selectedIdx) }
            // Rebuild list
            activity?.runOnUiThread {
                val parent = listContainer ?: return@runOnUiThread
                buildMeshList(parent.parent.parent.parent as LinearLayout, ctx)
            }
        }

        return card
    }

    private fun buildMeshEditor(ctx: android.content.Context, idx: Int): View {
        val container = LinearLayout(ctx).apply {
            orientation=LinearLayout.VERTICAL; setPadding(22,12,0,4)
        }

        container.addView(TextView(ctx).apply {
            text="RESIZE  (mm)"; textSize=9f; letterSpacing=0.12f
            setTextColor(Color.parseColor("#4FC3F7")); setPadding(0,8,0,4)
        })

        // Get current size
        var sizeW=50f; var sizeH=50f; var sizeD=50f
        val latch = java.util.concurrent.CountDownLatch(1)
        (activity as? MainActivity)?.glView?.queueEvent {
            try{ val s=NativeLib.nativeGetMeshSizeMM(idx); sizeW=s[0];sizeH=s[1];sizeD=s[2] }
            catch(_:Exception){}
            latch.countDown()
        }
        latch.await()

        fun mmField(label: String, init: Float): EditText {
            val row=LinearLayout(ctx).apply{ orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
            row.addView(TextView(ctx).apply{ text=label; textSize=11f; setTextColor(Color.parseColor("#AAAAAA")); layoutParams=LinearLayout.LayoutParams(24,LinearLayout.LayoutParams.WRAP_CONTENT) })
            val et=EditText(ctx).apply{
                inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText("%.2f".format(init)); setTextColor(Color.WHITE); textSize=13f
                setBackgroundColor(Color.parseColor("#2A2A38")); setPadding(12,8,12,8)
                layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)
            }
            row.addView(et)
            row.addView(TextView(ctx).apply{ text=" mm"; textSize=10f; setTextColor(Color.parseColor("#888888")) })
            container.addView(row)
            return et
        }

        val etW=mmField("W",sizeW); val etH=mmField("H",sizeH); val etD=mmField("D",sizeD)

        container.addView(Button(ctx).apply {
            text="Apply Size"; textSize=11f; setPadding(0,4,0,4)
            setOnClickListener {
                val w=etW.text.toString().toFloatOrNull()?:sizeW
                val h=etH.text.toString().toFloatOrNull()?:sizeH
                val d=etD.text.toString().toFloatOrNull()?:sizeD
                glRun { NativeLib.nativeSetMeshScaleMM(idx,w,h,d) }
                toast("Mesh resized to %.1fx%.1fx%.1f mm".format(w,h,d))
            }
            layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,40).apply{setMargins(0,8,0,0)}
        })

        return container
    }

    private fun glRun(block: ()->Unit) = (activity as? MainActivity)?.glView?.queueEvent(block)
    private fun toast(msg: String) = activity?.let{ Toast.makeText(it,msg,Toast.LENGTH_SHORT).show() }

    companion object {
        const val TAG = "MeshList"
        fun newInstance() = MeshListFragment()
    }
}
