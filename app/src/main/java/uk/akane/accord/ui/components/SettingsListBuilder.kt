package uk.akane.accord.ui.components

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import uk.akane.accord.R

/**
 * Builds settings screens in the shape the 1.0-stable build uses: uppercase section captions,
 * rounded cards holding runs of rows, chevrons or switches on the right, and an optional
 * explanatory line under a group.
 *
 * Declarative rather than a static layout per screen, because this app has a lot of settings and
 * they keep moving. A screen states its sections and rows; the row shape lives in one place.
 */
class SettingsListBuilder(private val container: LinearLayout) {

    private val inflater = LayoutInflater.from(container.context)

    /** A screen is a list of these. */
    class Section(
        val title: CharSequence,
        val rows: List<Row>,
        /** Explanatory line under the card. */
        val footer: CharSequence? = null
    )

    sealed class Row {
        abstract val title: CharSequence
        abstract val summary: CharSequence?

        /** Opens something - another screen, a dialog, an activity. */
        class Navigation(
            override val title: CharSequence,
            override val summary: CharSequence? = null,
            val onClick: () -> Unit
        ) : Row()

        /** Flips something on or off. */
        class Toggle(
            override val title: CharSequence,
            override val summary: CharSequence? = null,
            val checked: Boolean,
            val onChanged: (Boolean) -> Unit
        ) : Row()
    }

    fun build(sections: List<Section>) {
        container.removeAllViews()
        sections.forEach { section ->
            addHeader(section.title)
            addGroup(section.rows)
            section.footer?.let(::addFooter)
        }
    }

    private fun addHeader(title: CharSequence) {
        val view = inflater.inflate(R.layout.layout_settings_section_header, container, false)
        (view as TextView).text = title
        container.addView(view)
    }

    private fun addFooter(text: CharSequence) {
        val view = inflater.inflate(R.layout.layout_settings_footer, container, false)
        (view as TextView).text = text
        container.addView(view)
    }

    private fun addGroup(rows: List<Row>) {
        val card = inflater.inflate(R.layout.layout_settings_group, container, false)
        val rowContainer = card.findViewById<LinearLayout>(R.id.group_rows)
        rows.forEachIndexed { index, row ->
            rowContainer.addView(createRow(row, rowContainer, isLast = index == rows.lastIndex))
        }
        container.addView(card)
    }

    private fun createRow(row: Row, parent: ViewGroup, isLast: Boolean): View {
        val view = inflater.inflate(R.layout.layout_settings_row, parent, false)
        view.findViewById<TextView>(R.id.row_title).text = row.title
        row.summary?.let {
            view.findViewById<TextView>(R.id.row_summary).apply {
                text = it
                visibility = View.VISIBLE
            }
        }
        // The card's own rounding provides the edge, so the last row does not need a line under it.
        view.findViewById<View>(R.id.row_divider).visibility =
            if (isLast) View.GONE else View.VISIBLE

        val chevron = view.findViewById<ImageView>(R.id.row_chevron)
        val switch = view.findViewById<SwitchCompat>(R.id.row_switch)

        when (row) {
            is Row.Navigation -> view.setOnClickListener { row.onClick() }
            is Row.Toggle -> {
                chevron.visibility = View.GONE
                switch.visibility = View.VISIBLE
                switch.isChecked = row.checked
                // The whole row toggles, and the switch itself is not separately clickable, so a
                // tap anywhere behaves the same way.
                view.setOnClickListener {
                    switch.isChecked = !switch.isChecked
                    row.onChanged(switch.isChecked)
                }
            }
        }
        return view
    }
}
