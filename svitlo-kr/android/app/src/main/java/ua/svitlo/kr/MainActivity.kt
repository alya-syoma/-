package ua.svitlo.kr

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private var autoOpened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        findViewById<Button>(R.id.btnAddress).setOnClickListener {
            startActivity(Intent(this, AddressActivity::class.java))
        }
        findViewById<Button>(R.id.btnGroup).setOnClickListener { chooseGroup() }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { load(force = true) }
        Widgets.schedule(this)
    }

    override fun onResume() {
        super.onResume()
        if (prefs.group.isBlank()) {
            render(null, false)
            if (!autoOpened) {
                autoOpened = true
                startActivity(Intent(this, AddressActivity::class.java))
            }
            return
        }
        load(force = false)
    }

    private fun load(force: Boolean) {
        DataRepo.cached(this)?.let { render(it, false) }
        thread {
            val r = DataRepo.load(this, force)
            runOnUiThread {
                if (r != null) {
                    render(r.data, r.stale)
                    Widgets.renderAll(this, r.data, r.stale)
                } else {
                    findViewById<TextView>(R.id.updated).text = "Немає інтернету і збережених даних"
                }
            }
        }
    }

    private fun render(data: OutageData?, stale: Boolean) {
        val title = findViewById<TextView>(R.id.statusTitle)
        val sub = findViewById<TextView>(R.id.statusSubtitle)
        val addr = findViewById<TextView>(R.id.statusAddress)
        val grp = findViewById<TextView>(R.id.statusGroup)
        val card = findViewById<View>(R.id.card)
        val days = findViewById<LinearLayout>(R.id.days)
        days.removeAllViews()
        grp.text = if (prefs.group.isBlank()) "" else groupLabel(prefs.group)
        addr.text = prefs.address
        if (data == null || prefs.group.isBlank()) {
            title.text = if (prefs.group.isBlank()) "Оберіть адресу" else "Завантаження…"
            sub.text = ""
            return
        }
        val now = System.currentTimeMillis()
        val st = computeStatus(data, prefs.group, now)
        title.text = st.title
        sub.text = st.subtitle
        sub.visibility = if (st.subtitle.isBlank()) View.GONE else View.VISIBLE
        card.setBackgroundResource(if (st.state == Power.OFF) R.drawable.widget_bg_off else R.drawable.widget_bg)

        listOf("Сьогодні", "Завтра").forEachIndexed { i, name -> days.addView(dayView(name, st.days[i], now)) }

        val upd = data.updated.ifBlank { "—" }
        findViewById<TextView>(R.id.updated).text =
            "ДТЕК оновив: $upd" + if (stale) "\nНемає інтернету — показано збережені дані" else ""
    }

    private fun dayView(name: String, day: Day, now: Long): View {
        val v = layoutInflater.inflate(R.layout.item_day, null)
        v.findViewById<TextView>(R.id.dayName).text = name
        val bar = v.findViewById<ImageView>(R.id.dayBar)
        bar.setImageBitmap(BarRenderer.render(day, now, 960, 44))
        val note = v.findViewById<TextView>(R.id.dayNote)
        note.text = when (day.source) {
            "fact" -> ""
            "preset" -> "Точний графік ще не опубліковано — показано можливі відключення."
            else -> "Немає даних для цієї черги."
        }
        note.visibility = if (note.text.isEmpty()) View.GONE else View.VISIBLE

        val list = SpannableStringBuilder()
        val endOfDay = day.start + 48 * SLOT_MS
        for (iv in intervals(day)) {
            if (iv.state == Power.ON) continue
            val label = when (iv.state) {
                Power.OFF -> "Світла немає"
                Power.MAYBE -> "Можливе відключення"
                else -> "Немає даних"
            }
            val end = if (iv.end == endOfDay) "24:00" else hhmm(iv.end)
            val start = list.length
            list.append("●  ")
            list.setSpan(ForegroundColorSpan(Colors.of(iv.state)), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            list.append("${hhmm(iv.start)}–$end   $label\n")
        }
        if (list.isEmpty() && day.source == "fact") list.append("Відключень немає 🎉")
        val tv = v.findViewById<TextView>(R.id.dayList)
        tv.text = list.trimEnd()
        tv.typeface = Typeface.DEFAULT
        return v
    }

    private fun chooseGroup() {
        val fromData = DataRepo.cached(this)?.groups.orEmpty()
        val groups = fromData.ifEmpty {
            listOf("1.1", "1.2", "2.1", "2.2", "3.1", "3.2", "4.1", "4.2", "5.1", "5.2", "6.1", "6.2").map { "GPV$it" }
        }
        AlertDialog.Builder(this)
            .setTitle("Оберіть чергу")
            .setItems(groups.map(::groupLabel).toTypedArray()) { _, which ->
                prefs.group = groups[which]
                load(force = false)
            }
            .setNegativeButton("Скасувати", null)
            .show()
    }
}
