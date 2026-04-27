package com.modelviewer3d

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Export & Share — redesigned with multi-format support.
 *
 * Formats:
 *   OBJ  — Wavefront Object (text, universal)
 *   STL  — Stereolithography (binary, 3D printing)
 *   PLY  — Stanford Polygon (text, point clouds / scan data)
 *
 * Actions:
 *   • Save to Downloads/3DViewer/
 *   • Share via any app (system chooser)
 *   • Quick share to WhatsApp / Telegram / Email / Drive
 */
class ExportFragment : BottomSheetDialogFragment() {

    private var selectedFormat = "obj"
    private var formatButtons = mutableListOf<Triple<View, String, String>>() // btn, fmt, accent

    data class Format(val id: String, val label: String, val desc: String, val color: String)

    private val formats = listOf(
        Format("obj", "OBJ",  "Wavefront Object\nText · Universal · Editable",       "#00D4FF"),
        Format("stl", "STL",  "Stereolithography\nBinary · 3D Printing · Compact",   "#FF9800"),
        Format("ply", "PLY",  "Stanford Polygon\nText · Scan Data · MeshLab",        "#4CAF82")
    )

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
            gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(20, 14, 20, 4)
            addView(TextView(ctx).apply {
                text = "↑  Export & Share"; textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        })
        root.addView(TextView(ctx).apply {
            text = "Choose format and destination"
            textSize = 11f; setTextColor(Color.parseColor("#606080")); setPadding(20, 0, 20, 12)
        })
        root.addView(divider(ctx))

        // ── Format selector ───────────────────────────────────────────────────
        root.addView(sectionLabel(ctx, "FORMAT"))
        val fmtRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(14, 6, 14, 14)
        }

        for (fmt in formats) {
            val btn = buildFormatCard(ctx, fmt, fmt.id == selectedFormat)
            btn.setOnClickListener {
                selectedFormat = fmt.id
                refreshFormatButtons()
            }
            formatButtons.add(Triple(btn, fmt.id, fmt.color))
            fmtRow.addView(btn)
            if (fmt != formats.last()) fmtRow.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(8, 1)
            })
        }
        root.addView(fmtRow)
        root.addView(divider(ctx))

        // ── Save to device ────────────────────────────────────────────────────
        root.addView(sectionLabel(ctx, "SAVE TO DEVICE"))
        root.addView(buildActionRow(ctx,
            icon = "💾", title = "Save to Downloads",
            sub  = "Downloads/3DViewer/ · visible in Files app",
            color = "#00D4FF"
        ) {
            (activity as? MainActivity)?.exportModel(selectedFormat, share = false)
            dismiss()
        })
        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 8)
        })
        root.addView(divider(ctx))

        // ── Share ─────────────────────────────────────────────────────────────
        root.addView(sectionLabel(ctx, "SHARE VIA"))
        root.addView(buildActionRow(ctx,
            icon = "↗", title = "Share via Any App",
            sub  = "Opens system share sheet",
            color = "#9090B0"
        ) {
            (activity as? MainActivity)?.exportModel(selectedFormat, share = true)
            dismiss()
        })

        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 6)
        })

        // App quick-share grid
        val appGrid = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(14, 4, 14, 14)
        }
        val shareApps = listOf(
            Triple("💬", "WhatsApp",  "com.whatsapp"),
            Triple("✈️", "Telegram",  "org.telegram.messenger"),
            Triple("📧", "Email",     null),
            Triple("☁️", "Drive",     "com.google.android.apps.docs"),
            Triple("📁", "Files",     "com.google.android.apps.nbu.files")
        )
        for ((emoji, label, pkg) in shareApps) {
            appGrid.addView(buildAppPill(ctx, emoji, label) {
                if (pkg != null && !isInstalled(pkg)) {
                    Toast.makeText(ctx, "$label not installed", Toast.LENGTH_SHORT).show()
                } else {
                    (activity as? MainActivity)?.exportModel(selectedFormat, share = true, shareApp = pkg)
                    dismiss()
                }
            })
        }
        root.addView(appGrid)

        return scroll
    }

    // ── Build format card ─────────────────────────────────────────────────────
    private fun buildFormatCard(ctx: android.content.Context, fmt: Format, selected: Boolean): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            background = ctx.getDrawable(
                if (selected) R.drawable.bg_card_selected else R.drawable.bg_card_dark)
            setPadding(10, 14, 10, 14)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            isClickable = true; isFocusable = true

            addView(TextView(ctx).apply {
                text = fmt.label; textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(if (selected) Color.parseColor(fmt.color) else Color.WHITE)
                gravity = android.view.Gravity.CENTER
            })
            addView(TextView(ctx).apply {
                text = fmt.desc; textSize = 9f
                setTextColor(if (selected) Color.parseColor("#9090B0") else Color.parseColor("#505070"))
                gravity = android.view.Gravity.CENTER
                setPadding(0, 4, 0, 0)
            })
        }
    }

    private fun refreshFormatButtons() {
        val ctx = context ?: return
        for ((btn, fmtId, color) in formatButtons) {
            val isSel = fmtId == selectedFormat
            btn.background = ctx.getDrawable(
                if (isSel) R.drawable.bg_card_selected else R.drawable.bg_card_dark)
            val tv0 = (btn as? LinearLayout)?.getChildAt(0) as? TextView
            val tv1 = (btn as? LinearLayout)?.getChildAt(1) as? TextView
            tv0?.setTextColor(if (isSel) Color.parseColor(color) else Color.WHITE)
            tv0?.setTypeface(null, if (isSel) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            tv1?.setTextColor(if (isSel) Color.parseColor("#9090B0") else Color.parseColor("#505070"))
        }
    }

    // ── Build action row ──────────────────────────────────────────────────────
    private fun buildActionRow(
        ctx: android.content.Context,
        icon: String, title: String, sub: String, color: String,
        onClick: () -> Unit
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        background = ctx.getDrawable(R.drawable.bg_card_dark)
        setPadding(20, 0, 20, 0)
        minimumHeight = 60
        isClickable = true; isFocusable = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(14, 0, 14, 0) }

        addView(TextView(ctx).apply {
            text = icon; textSize = 22f; setPadding(0, 0, 16, 0)
        })
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(ctx).apply {
                text = title; textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor(color))
            })
            addView(TextView(ctx).apply {
                text = sub; textSize = 9f; setTextColor(Color.parseColor("#505070"))
            })
        })
        addView(TextView(ctx).apply {
            text = "›"; textSize = 20f; setTextColor(Color.parseColor("#303050"))
        })
        setOnClickListener { onClick() }
    }

    // ── Build app pill ─────────────────────────────────────────────────────────
    private fun buildAppPill(
        ctx: android.content.Context, emoji: String, label: String, onClick: () -> Unit
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = android.view.Gravity.CENTER
        background = ctx.getDrawable(R.drawable.bg_card_dark)
        setPadding(0, 10, 0, 10)
        isClickable = true; isFocusable = true
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { setMargins(4, 0, 4, 0) }
        addView(TextView(ctx).apply { text = emoji; textSize = 20f; gravity = android.view.Gravity.CENTER })
        addView(TextView(ctx).apply {
            text = label; textSize = 9f; setTextColor(Color.parseColor("#606080"))
            gravity = android.view.Gravity.CENTER
        })
        setOnClickListener { onClick() }
    }

    private fun isInstalled(pkg: String) = try {
        requireContext().packageManager.getPackageInfo(pkg, 0); true
    } catch (_: Exception) { false }

    private fun sectionLabel(ctx: android.content.Context, text: String) = TextView(ctx).apply {
        this.text = text; textSize = 9f; letterSpacing = 0.14f
        setTextColor(Color.parseColor("#00D4FF")); setPadding(20, 14, 20, 6)
    }

    private fun divider(ctx: android.content.Context) = View(ctx).apply {
        setBackgroundColor(Color.parseColor("#1A1A28"))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }

    companion object {
        const val TAG = "ExportSheet"
        fun newInstance() = ExportFragment()
    }
}
