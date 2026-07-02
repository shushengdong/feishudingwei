package com.research.location

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.research.location.model.AppInfo

class AppListAdapter(
    private val apps: List<AppInfo>,
    private val onSelect: (AppInfo) -> Unit
) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivIcon: ImageView = view.findViewById(R.id.iv_app_icon)
        val tvAppName: TextView = view.findViewById(R.id.tv_app_name)
        val tvPkgName: TextView = view.findViewById(R.id.tv_app_pkg)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        holder.tvAppName.text = app.appName
        holder.tvPkgName.text = app.packageName
        holder.ivIcon.setImageDrawable(app.icon)
        holder.itemView.setOnClickListener { onSelect(app) }
    }

    override fun getItemCount() = apps.size
}
