package fi.callshift.app.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import fi.callshift.app.databinding.ItemDialEntryBinding

/** Строка списка набора: аватар, имя, подпись, кнопка вызова. */
data class DialEntry(
    val title: String,
    val subtitle: CharSequence,
    val number: String,
    val name: String?,
    val subtitleColor: Int? = null,
)

class DialEntryAdapter(
    private val onCall: (DialEntry) -> Unit,
    private val onLong: (DialEntry) -> Unit,
    private val onClick: (DialEntry) -> Unit = onCall,
) : RecyclerView.Adapter<DialEntryAdapter.VH>() {

    var items: List<DialEntry> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }

    class VH(val b: ItemDialEntryBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemDialEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = items[position]
        h.b.tvTitle.text = e.title
        h.b.tvSubtitle.text = e.subtitle
        h.b.tvSubtitle.setTextColor(e.subtitleColor ?: 0xFFBFD6C9.toInt())
        h.b.tvAvatar.text = Keypad.initials(e.name) ?: "#"
        h.b.tvAvatar.backgroundTintList = ColorStateList.valueOf(avatarColor(e.name ?: e.number))
        h.b.root.setOnClickListener { onClick(e) }
        h.b.root.setOnLongClickListener { onLong(e); true }
        h.b.btnItemCall.setOnClickListener { onCall(e) }
    }

    companion object {
        private val PALETTE = intArrayOf(
            0xFF2E7D52.toInt(), 0xFF1565C0.toInt(), 0xFF6A1B9A.toInt(), 0xFFAD1457.toInt(),
            0xFFEF6C00.toInt(), 0xFF00838F.toInt(), 0xFF4E342E.toInt(), 0xFF283593.toInt(),
        )
        fun avatarColor(key: String) = PALETTE[(key.hashCode() and 0x7fffffff) % PALETTE.size]
    }
}
