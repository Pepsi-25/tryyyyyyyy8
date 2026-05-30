package com.networkmonitor.app

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class AppUsageInfo(
    val name: String,
    val packageName: String,
    val totalBytes: Long,
    val icon: Drawable?,
    val isSystem: Boolean = false
)

class AppUsageAdapter(private val items: List<AppUsageInfo>) :
    RecyclerView.Adapter<AppUsageAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.ivAppIcon)
        val name: TextView = v.findViewById(R.id.tvAppName)
        val pkg: TextView = v.findViewById(R.id.tvAppPkg)
        val usage: TextView = v.findViewById(R.id.tvAppUsage)
        val bar: View = v.findViewById(R.id.viewBar)
        val rank: TextView = v.findViewById(R.id.tvRank)
        val type: TextView = v.findViewById(R.id.tvAppType)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val maxBytes = items.maxOfOrNull { it.totalBytes } ?: 1L

        holder.icon.setImageDrawable(item.icon ?: holder.itemView.context.getDrawable(android.R.drawable.sym_def_app_icon))
        holder.name.text = item.name
        holder.pkg.text = item.packageName
        holder.usage.text = formatBytes(item.totalBytes)
        holder.rank.text = "#${position + 1}"
        holder.type.text = if (item.isSystem) "نظام" else "مستخدم"
        holder.type.setTextColor(
            holder.itemView.context.getColor(
                if (item.isSystem) R.color.text_secondary else R.color.accent
            )
        )

        val pct = (item.totalBytes.toFloat() / maxBytes.toFloat())
        holder.bar.post {
            val parent = holder.bar.parent as? View ?: return@post
            val w = (parent.width * pct).toInt().coerceAtLeast(8)
            holder.bar.layoutParams = holder.bar.layoutParams.apply { width = w }
            holder.bar.requestLayout()
        }
    }

    override fun getItemCount() = items.size

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1048576 -> "%.1f KB".format(bytes / 1024f)
        bytes < 1073741824 -> "%.1f MB".format(bytes / 1048576f)
        else -> "%.2f GB".format(bytes / 1073741824f)
    }
}
