package io.github.tuthan.stationar.core.scene

/**
 * The AR panel (the menu and the in-AR launcher) as rows of items that the D-pad moves through: up and down between
 * rows, left and right along one. A section's items wrap onto more rows when there are more than [perRow]; the first
 * row of a section carries its title.
 */
class PanelLayout(private val perRow: Int = 6) {
    class Item(val id: String, val label: String, val detail: String = "")

    class Row(val title: String?, val items: List<Item>)

    var rows: List<Row> = emptyList()
        private set

    var row = 0
        private set
    var col = 0
        private set

    /** Replaces the contents; the focus stays on the item with the same id if it's still there. */
    fun set(sections: List<Pair<String, List<Item>>>) {
        val keep = focusedItem()?.id
        val out = ArrayList<Row>()
        for ((title, items) in sections) {
            if (items.isEmpty()) continue
            items.chunked(perRow).forEachIndexed { i, chunk -> out.add(Row(if (i == 0) title else null, chunk)) }
        }
        rows = out
        row = 0
        col = 0
        if (keep != null) focus(keep)
    }

    /** Focuses the item with this id; false when there's none. */
    fun focus(id: String): Boolean {
        for (r in rows.indices) {
            val c = rows[r].items.indexOfFirst { it.id == id }
            if (c >= 0) {
                row = r
                col = c
                return true
            }
        }
        return false
    }

    fun focusedItem(): Item? = rows.getOrNull(row)?.items?.getOrNull(col)

    /** Moves the focus; false when it can't go that way (an edge). */
    fun move(dx: Int, dy: Int): Boolean {
        if (rows.isEmpty()) return false
        if (dy != 0) {
            val r = row + dy
            if (r !in rows.indices) return false
            row = r
            col = col.coerceAtMost(rows[r].items.size - 1)
            return true
        }
        val c = col + dx
        if (c !in rows[row].items.indices) return false
        col = c
        return true
    }
}
