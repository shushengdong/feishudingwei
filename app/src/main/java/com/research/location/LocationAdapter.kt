package com.research.location

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.research.location.model.SavedLocation
import java.text.DecimalFormat

class LocationAdapter(
    private val locations: List<SavedLocation>,
    private val onApply: (SavedLocation) -> Unit,
    private val onDelete: ((SavedLocation) -> Unit)? = null
) : RecyclerView.Adapter<LocationAdapter.ViewHolder>() {

    private val df = DecimalFormat("#.000000")

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tv_loc_name)
        val tvCoords: TextView = view.findViewById(R.id.tv_loc_coords)
        val tvTargetApp: TextView = view.findViewById(R.id.tv_loc_target_app)
        val tvWifi: TextView = view.findViewById(R.id.tv_loc_wifi)
        val btnApply: View = view.findViewById(R.id.btn_apply_loc)
        val btnDelete: View = view.findViewById(R.id.btn_delete_loc)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_location, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val loc = locations[position]
        holder.tvName.text = loc.name
        holder.tvCoords.text = "${df.format(loc.lat)}, ${df.format(loc.lng)}"
        if (loc.targetAppName.isNotEmpty()) {
            holder.tvTargetApp.text = "\u2192 ${loc.targetAppName}"
            holder.tvTargetApp.visibility = View.VISIBLE
        } else {
            holder.tvTargetApp.visibility = View.GONE
        }
        if (loc.wifiSsid.isNotEmpty()) {
            holder.tvWifi.text = "\uD83D\uDCE1 ${loc.wifiSsid}"
            holder.tvWifi.visibility = View.VISIBLE
        } else {
            holder.tvWifi.visibility = View.GONE
        }
        holder.btnApply.setOnClickListener { onApply(loc) }
        holder.btnDelete.setOnClickListener { onDelete?.invoke(loc) }
        if (onDelete == null) holder.btnDelete.visibility = View.GONE
    }

    override fun getItemCount() = locations.size
}
