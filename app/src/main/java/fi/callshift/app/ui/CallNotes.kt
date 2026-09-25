package fi.callshift.app.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Примечания к звонкам: текст, номер, время. Хранятся локально. */
object CallNotes {
    data class Note(val ts: Long, val number: String?, val name: String?, val text: String)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("call_notes", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Note> = runCatching {
        val arr = JSONArray(prefs(ctx).getString("notes", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Note(o.getLong("ts"), o.optString("number").ifEmpty { null }, o.optString("name").ifEmpty { null }, o.getString("text"))
        }.sortedByDescending { it.ts }
    }.getOrDefault(emptyList())

    fun add(ctx: Context, note: Note) = save(ctx, listOf(note) + all(ctx))

    fun delete(ctx: Context, note: Note) = save(ctx, all(ctx).filter { it != note })

    fun forNumber(ctx: Context, number: String?): List<Note> {
        val d = number?.filter { it.isDigit() }?.takeLast(9) ?: return emptyList()
        if (d.isEmpty()) return emptyList()
        return all(ctx).filter { it.number?.filter { c -> c.isDigit() }?.takeLast(9) == d }
    }

    private fun save(ctx: Context, notes: List<Note>) {
        val arr = JSONArray()
        notes.take(500).forEach {
            arr.put(JSONObject().put("ts", it.ts).put("number", it.number ?: "").put("name", it.name ?: "").put("text", it.text))
        }
        prefs(ctx).edit().putString("notes", arr.toString()).apply()
    }
}
