package com.networkmonitor.app

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

enum class LogType { SUCCESS, DANGER, WARNING, INFO }
data class LogEntry(val time: String, val message: String, val type: LogType)

class LogAdapter(private val items: List<LogEntry>) :
    RecyclerView.Adapter<LogAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val time: TextView = v.findViewById(R.id.tvLogTime)
        val msg: TextView = v.findViewById(R.id.tvLogMsg)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = items[position]
        holder.time.text = e.time
        holder.msg.text = e.message
        holder.msg.setTextColor(when (e.type) {
            LogType.SUCCESS -> Color.parseColor("#3B6D11")
            LogType.DANGER  -> Color.parseColor("#A32D2D")
            LogType.WARNING -> Color.parseColor("#BA7517")
            LogType.INFO    -> Color.parseColor("#185FA5")
        })
    }

    override fun getItemCount() = items.size
}
