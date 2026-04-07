package com.example.musicplayer.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.example.musicplayer.model.Track
import org.json.JSONArray
import org.json.JSONObject

class RecentlyPlayedFragment : Fragment() {

    var onTrackSelected: ((Track, String) -> Unit)? = null

    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = inflater.context
        val dp = ctx.resources.displayMetrics.density

        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(ctx.getColor(com.example.musicplayer.R.color.bg_primary))
        }

        // Header
        val header = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val hp = (20 * dp).toInt()
            val vp = (20 * dp).toInt()
            setPadding(hp, vp, hp, (12 * dp).toInt())
        }
        TextView(ctx).apply {
            text = "Melodify"
            setTextColor(ctx.getColor(com.example.musicplayer.R.color.accent))
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            letterSpacing = 0.15f
            header.addView(this)
        }
        TextView(ctx).apply {
            text = "Son Dinlenenler"
            setTextColor(ctx.getColor(com.example.musicplayer.R.color.text_primary))
            textSize = 26f
            setTypeface(null, android.graphics.Typeface.BOLD)
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (2 * dp).toInt()
            layoutParams = lp
            header.addView(this)
        }
        root.addView(header)

        // Boş durum
        tvEmpty = TextView(ctx).apply {
            text = "Henüz dinlenen şarkı yok"
            setTextColor(ctx.getColor(com.example.musicplayer.R.color.text_secondary))
            textSize = 15f
            gravity = android.view.Gravity.CENTER
            visibility = View.GONE
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            )
            layoutParams = lp
        }
        root.addView(tvEmpty)

        // RecyclerView
        recyclerView = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            )
            layoutParams = lp
        }
        root.addView(recyclerView)

        return root
    }

    override fun onResume() {
        super.onResume()
        loadRecent()
    }

    private fun loadRecent() {
        val tracks = getRecentTracks(requireContext())
        if (tracks.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            recyclerView.adapter = RecentAdapter(tracks) { track ->
                val url = if (track.audio.isNotEmpty()) track.audio else ""
                onTrackSelected?.invoke(track, url)
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "recently_played"
        private const val KEY_TRACKS = "tracks"
        private const val MAX_RECENT = 20

        fun saveTrack(context: Context, track: Track) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existing = prefs.getString(KEY_TRACKS, "[]")
            val array = JSONArray(existing)

            // Aynı şarkı varsa çıkar (en üste taşıyacağız)
            val newArray = JSONArray()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                if (obj.optString("id") != track.id) {
                    newArray.put(obj)
                }
            }

            // En başa ekle
            val obj = JSONObject().apply {
                put("id", track.id)
                put("name", track.name)
                put("artistName", track.artistName)
                put("image", track.image)
                put("audio", track.audio)
                put("duration", track.duration)
                put("videoId", track.videoId)
            }
            // Başa eklemek için ters çevir
            val final = JSONArray()
            final.put(obj)
            for (i in 0 until minOf(newArray.length(), MAX_RECENT - 1)) {
                final.put(newArray.getJSONObject(i))
            }

            prefs.edit().putString(KEY_TRACKS, final.toString()).apply()
        }

        fun getRecentTracks(context: Context): List<Track> {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_TRACKS, "[]") ?: "[]"
            val array = JSONArray(json)
            val list = mutableListOf<Track>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    Track(
                        id = obj.optString("id"),
                        name = obj.optString("name"),
                        artistName = obj.optString("artistName"),
                        image = obj.optString("image"),
                        audio = obj.optString("audio"),
                        duration = obj.optInt("duration"),
                        videoId = obj.optString("videoId")
                    )
                )
            }
            return list
        }
    }
}

// ─── Adapter ──────────────────────────────────────────────────────────────────

class RecentAdapter(
    private val tracks: List<Track>,
    private val onClick: (Track) -> Unit
) : RecyclerView.Adapter<RecentAdapter.VH>() {

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivThumb: ImageView = itemView.findViewWithTag("thumb")
        val tvTitle: TextView = itemView.findViewWithTag("title")
        val tvArtist: TextView = itemView.findViewWithTag("artist")
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val dp = ctx.resources.displayMetrics.density

        val row = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            val vp = (10 * dp).toInt()
            val hp = (16 * dp).toInt()
            setPadding(hp, vp, hp, vp)
            background = android.util.TypedValue().also {
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
            }.resourceId.let { ctx.getDrawable(it) }
            isClickable = true
            isFocusable = true
        }

        val thumbSize = (52 * dp).toInt()
        val iv = ImageView(ctx).apply {
            tag = "thumb"
            layoutParams = android.widget.LinearLayout.LayoutParams(thumbSize, thumbSize)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }

        val textCol = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { setMargins((12 * dp).toInt(), 0, 0, 0) }
        }

        val tvT = TextView(ctx).apply {
            tag = "title"
            textSize = 14f
            setTextColor(ctx.getColor(com.example.musicplayer.R.color.text_primary))
            setTypeface(null, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val tvA = TextView(ctx).apply {
            tag = "artist"
            textSize = 12f
            setTextColor(ctx.getColor(com.example.musicplayer.R.color.text_secondary))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        textCol.addView(tvT)
        textCol.addView(tvA)
        row.addView(iv)
        row.addView(textCol)

        return VH(row)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val track = tracks[position]
        holder.tvTitle.text = track.name
        holder.tvArtist.text = track.artistName
        if (track.image.isNotEmpty()) {
            holder.ivThumb.load(track.image) {
                transformations(RoundedCornersTransformation(8f))
                placeholder(android.R.drawable.ic_menu_gallery)
            }
        } else {
            holder.ivThumb.setImageResource(android.R.drawable.ic_menu_gallery)
        }
        holder.itemView.setOnClickListener { onClick(track) }
    }

    override fun getItemCount() = tracks.size
}
