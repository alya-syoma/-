package ua.svitlo.kr

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Finds the outage queue for an address in Kryvyi Rih.
 *
 * Opens the official DTEK Dnipro Grid page in a WebView (so anti-bot checks and
 * CSRF cookies work like in a normal browser) and calls the same AJAX methods
 * the site's address form uses: getStreets and getHomeNum.
 */
class AddressActivity : Activity() {
    companion object {
        const val DTEK_URL = "https://www.dtek-dnem.com.ua/ua/shutdowns"
        val CITY_RE = Regex("кривий\\s*р[іi]г", RegexOption.IGNORE_CASE)
        const val CITY_FALLBACK = "м. Кривий Ріг"
    }

    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private lateinit var hint: TextView
    private lateinit var search: EditText
    private lateinit var list: ListView
    private val main = Handler(Looper.getMainLooper())
    private val callbacks = HashMap<Int, (JSONObject) -> Unit>()
    private var nextId = 1
    private var ready = false
    private var pollCount = 0

    private var city = CITY_FALLBACK
    private var streets: List<String> = emptyList()
    private var street: String? = null
    private var houses: JSONObject? = null
    private var shown: List<String> = emptyList()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_address)
        web = findViewById(R.id.web)
        progress = findViewById(R.id.progress)
        hint = findViewById(R.id.hint)
        search = findViewById(R.id.search)
        list = findViewById(R.id.list)

        findViewById<View>(R.id.btnManual).setOnClickListener { manual() }

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.userAgentString = web.settings.userAgentString.replace("; wv", "")
        web.addJavascriptInterface(Bridge(), "SvitloBridge")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) = checkReady()
        }
        web.loadUrl(DTEK_URL)
        setStage("Підключаюся до сайту ДТЕК…", searching = false)
        main.postDelayed(::checkReady, 1500)

        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = filter()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        list.setOnItemClickListener { _, _, pos, _ ->
            val item = shown.getOrNull(pos) ?: return@setOnItemClickListener
            if (street == null) pickStreet(item) else pickHouse(item)
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        web.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (street != null) {
            street = null
            houses = null
            search.setText("")
            setStage("Введіть назву вулиці", searching = true)
            filter()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ───────────── page readiness ─────────────

    private fun checkReady() {
        if (ready || isFinishing) return
        web.evaluateJavascript(
            "!!document.querySelector('meta[name=\"csrf-token\"]') || !!window.DisconSchedule || !!window.jQuery",
        ) { r ->
            if (ready) return@evaluateJavascript
            if (r == "true") {
                ready = true
                web.visibility = View.GONE
                loadStreets()
            } else {
                pollCount++
                if (pollCount == 12) {
                    // Probably an anti-bot check: show the page so the user can pass it.
                    web.visibility = View.VISIBLE
                    setStage("Сайт ДТЕК просить перевірку. Пройдіть її нижче — далі все продовжиться автоматично.", searching = false)
                }
                main.postDelayed(::checkReady, 1000)
            }
        }
    }

    // ───────────── DTEK calls ─────────────

    private inner class Bridge {
        @JavascriptInterface
        fun onResult(id: Int, json: String) {
            main.post {
                val cb = callbacks.remove(id) ?: return@post
                cb(try { JSONObject(json) } catch (e: Exception) { JSONObject().put("error", "json") })
            }
        }
    }

    private fun js(id: Int, kind: String, params: JSONObject) = """
        (function(){
          function send(o){ SvitloBridge.onResult($id, JSON.stringify(o)); }
          try {
            if ('$kind' === 'page') {
              var s = window.DisconSchedule && DisconSchedule.streets;
              return send({ ok: s || null });
            }
            var params = $params;
            var m = document.querySelector('meta[name="csrf-token"]');
            var p = document.querySelector('meta[name="csrf-param"]');
            var a = document.querySelector('meta[name="ajaxUrl"]');
            var body = new URLSearchParams();
            Object.keys(params).forEach(function(k){ body.append(k, params[k]); });
            if (p && m && !body.has(p.content)) body.append(p.content, m.content);
            var h = { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
                      'X-Requested-With': 'XMLHttpRequest',
                      'Accept': 'application/json, text/javascript, */*; q=0.01' };
            if (m) h['X-CSRF-Token'] = m.content;
            fetch((a && a.content) || '/ua/ajax', { method: 'POST', credentials: 'include', headers: h, body: body.toString() })
              .then(function(r){ return r.text(); })
              .then(function(t){ try { send({ ok: JSON.parse(t) }); } catch(e) { send({ error: 'Сайт повернув не JSON' }); } })
              .catch(function(e){ send({ error: String(e) }); });
          } catch (e) { send({ error: String(e) }); }
        })();
    """.trimIndent()

    private fun call(kind: String, params: JSONObject, cb: (JSONObject) -> Unit) {
        val id = nextId++
        callbacks[id] = cb
        web.evaluateJavascript(js(id, kind, params), null)
    }

    private fun loadStreets() {
        setStage("Завантажую список вулиць…", searching = false)
        call("page", JSONObject()) { r ->
            if (!applyStreets(r.opt("ok"))) {
                call("ajax", JSONObject().put("method", "getStreets")) { r2 ->
                    val ok = r2.optJSONObject("ok")
                    val src = ok?.opt("streets") ?: ok?.opt("data") ?: ok
                    if (!applyStreets(src)) fail(r2.optString("error").ifBlank { "Кривий Ріг не знайдено в довіднику ДТЕК" })
                }
            }
        }
    }

    private fun applyStreets(src: Any?): Boolean {
        val arr: JSONArray? = when (src) {
            is JSONArray -> src
            is JSONObject -> {
                val key = src.keys().asSequence().firstOrNull { CITY_RE.containsMatchIn(it) }
                if (key != null) city = key
                key?.let { src.optJSONArray(it) }
            }
            else -> null
        }
        if (arr == null || arr.length() == 0) return false
        streets = (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        setStage("Введіть назву вулиці", searching = true)
        filter()
        return true
    }

    private fun pickStreet(s: String) {
        street = s
        setStage("Завантажую будинки…", searching = false)
        val params = JSONObject()
            .put("method", "getHomeNum")
            .put("data[0][name]", "city").put("data[0][value]", city)
            .put("data[1][name]", "street").put("data[1][value]", s)
        call("ajax", params) { r ->
            val data = r.optJSONObject("ok")?.optJSONObject("data")
            if (data == null || data.length() == 0) {
                fail(r.optString("error").ifBlank { "ДТЕК не повернув будинків для цієї вулиці" })
                return@call
            }
            houses = data
            search.setText("")
            setStage("$s — оберіть будинок", searching = true)
            search.hint = "Номер будинку"
            filter()
        }
    }

    private fun pickHouse(h: String) {
        val entry = houses?.optJSONObject(h)
        val groups = entry?.optJSONArray("sub_type_reason")
        val g = groups?.optString(0).orEmpty()
        if (g.isBlank()) {
            fail("Для цього будинку черга не вказана (можливо, він не відключається за графіком)")
            return
        }
        val prefs = Prefs(this)
        prefs.street = street.orEmpty()
        prefs.house = h
        prefs.group = g
        Widgets.renderAll(this)
        Widgets.refreshNow(this)
        AlertDialog.Builder(this)
            .setTitle(groupLabel(g))
            .setMessage("${street}, $h\n\nТепер додайте віджет «Світло КР» на робочий стіл: утримуйте палець на порожньому місці → Віджети.")
            .setPositiveButton("Готово") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    // ───────────── UI ─────────────

    private fun filter() {
        val q = norm(search.text.toString())
        val src = if (street == null) streets else houses?.keys()?.asSequence()?.toList().orEmpty().sortedWith(houseOrder)
        shown = if (q.isEmpty()) {
            if (street == null) emptyList() else src
        } else if (street == null) {
            src.filter { norm(it).contains(q) }
        } else {
            src.filter { norm(it).startsWith(q) }
        }.take(200)
        list.adapter = ArrayAdapter(this, R.layout.item_text, shown)
    }

    private val houseOrder = compareBy<String>({ it.takeWhile(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE }, { it })

    private fun norm(s: String) = s.lowercase().replace(Regex("[’'`ʼ]"), "").replace(Regex("\\s+"), " ").trim()

    private fun setStage(text: String, searching: Boolean) {
        hint.text = text
        progress.visibility = if (searching) View.GONE else View.VISIBLE
        search.visibility = if (searching) View.VISIBLE else View.GONE
        list.visibility = if (searching) View.VISIBLE else View.GONE
        if (searching && street == null) search.hint = "Напр. Ушинського"
    }

    private fun fail(msg: String) {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle("Не вдалося визначити чергу")
            .setMessage("$msg\n\nМожна обрати чергу вручну — її видно на сайті ДТЕК або в рахунку за світло.")
            .setPositiveButton("Обрати вручну") { _, _ -> manual() }
            .setNegativeButton("Назад") { _, _ ->
                street = null
                if (streets.isNotEmpty()) { setStage("Введіть назву вулиці", true); filter() } else finish()
            }
            .show()
    }

    private fun manual() {
        val groups = DataRepo.cached(this)?.groups.orEmpty().ifEmpty {
            listOf("1.1", "1.2", "2.1", "2.2", "3.1", "3.2", "4.1", "4.2", "5.1", "5.2", "6.1", "6.2").map { "GPV$it" }
        }
        AlertDialog.Builder(this)
            .setTitle("Оберіть чергу")
            .setItems(groups.map(::groupLabel).toTypedArray()) { _, i ->
                val prefs = Prefs(this)
                prefs.group = groups[i]
                if (street == null) { prefs.street = ""; prefs.house = "" }
                Widgets.renderAll(this)
                Widgets.refreshNow(this)
                finish()
            }
            .setNegativeButton("Скасувати", null)
            .show()
    }
}
