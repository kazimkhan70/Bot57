package com.example.gridbot

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.*
import java.io.File
import java.math.BigDecimal
import java.math.MathContext
import kotlin.concurrent.thread

class EquityView(c: Context) : View(c) {
    var pts: List<Double> = emptyList()
        set(v) { field = v; invalidate() }
    private val paint = Paint().apply { color = 0xFF2E7D32.toInt(); strokeWidth = 4f; style = Paint.Style.STROKE; isAntiAlias = true }
    override fun onDraw(cv: Canvas) {
        if (pts.size < 2) return
        val mn = pts.min(); val mx = pts.max()
        val span = if (mx > mn) mx - mn else 1.0
        val path = Path()
        pts.forEachIndexed { i, v ->
            val x = i * (width.toFloat() / (pts.size - 1))
            val y = height - ((v - mn) / span).toFloat() * (height - 8f) - 4f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        cv.drawPath(path, paint)
    }
}

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var toggle: Button
    private lateinit var panels: TextView
    private lateinit var curve: EquityView
    private val ui = Handler(Looper.getMainLooper())
    private var agentMode = false

    private val panelOrder = listOf("SCANNER", "RUG CHECK", "SIZER", "EXECUTOR", "LOGGER", "EQUITY")

    private fun field(parent: LinearLayout, hint: String, value: String, number: Boolean = true, pw: Boolean = false): EditText {
        val e = EditText(this)
        e.hint = hint; e.setText(value)
        e.inputType = when {
            pw -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            number -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        parent.addView(e); return e
    }

    private fun group(root: LinearLayout, title: String): LinearLayout {
        val g = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        g.addView(TextView(this).apply { text = title; textSize = 14f; setPadding(0, 24, 0, 0) })
        root.addView(g); return g
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        val p = Prefs.plain(this)
        val sec = Prefs.secure(this)
        val paperStore = Store(File(filesDir, "agents_paper"))
        val liveStore = Store(File(filesDir, "agents_live"))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 60, 40, 40) }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(TextView(this).apply { text = "GridBot — Binance Spot"; textSize = 22f })

        val strategy = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Grid trading", "Trend (EMA cross + RSI)", "DCA (buy every N minutes)", "6-Agent auto bot (scans Binance)"))
            setSelection(when (p.getString("strategy", "agents")) { "trend" -> 1; "dca" -> 2; "grid" -> 0; else -> 3 })
        }
        root.addView(strategy)

        // ── single-coin strategies ──
        val common = group(root, "Coin")
        val symbol = field(common, "Symbol (e.g. BTCUSDT)", p.getString("symbol", "BTCUSDT")!!, number = false)
        val interval = field(common, "Candle interval: 1m, 5m, 15m, 1h, 4h or 1d", p.getString("interval", "5m")!!, number = false)
            .apply { inputType = InputType.TYPE_CLASS_TEXT }
        val quote = field(common, "USDT per grid / per trade", p.getString("quoteS", "10")!!)

        val gridG = group(root, "Grid settings")
        val lower = field(gridG, "Lower price", p.getString("lowerS", "")!!)
        val upper = field(gridG, "Upper price", p.getString("upperS", "")!!)
        val levels = field(gridG, "Grid lines (min 3)", p.getInt("levels", 10).toString())
        val sl = field(gridG, "Grid stop-loss price (0 = off)", p.getString("slS", "0")!!)
        val tight = Button(this).apply { text = "Auto tight range (±3%, 15 lines)" }
        gridG.addView(tight)

        val trendG = group(root, "Trend settings (take-profit % is also used by DCA)")
        val slPct = field(trendG, "Trend stop-loss % (0 = off)", p.getString("slPctS", "1.5")!!)
        val tpPct = field(trendG, "Trend / DCA take-profit % (0 = off)", p.getString("tpPctS", "3")!!)

        val dcaG = group(root, "DCA settings (0 = never sell)")
        val dcaEvery = field(dcaG, "DCA: buy every N minutes", p.getInt("dcaEvery", 60).toString())
        val dcaMax = field(dcaG, "DCA: max open cost in USDT (0 = no cap)", p.getString("dcaMaxS", "0")!!)

        val limitsG = group(root, "Daily limits (realized PnL, resets 00:00 UTC; 0 = off)")
        val dayWin = field(limitsG, "Daily profit target (USDT)", p.getString("dayWinS", "0")!!)
        val dayLoss = field(limitsG, "Daily loss limit (USDT)", p.getString("dayLossS", "0")!!)

        val paper = CheckBox(this).apply { text = "Paper trading (no real orders)"; isChecked = p.getBoolean("paper", true) }
        val backtest = Button(this).apply { text = "Run backtest (last ~1000 candles)" }

        // ── six-agent bot ──
        val agentG = group(root, "6-agent bot: scanner → rug check → sizer → executor → logger → equity")
        val aStart = field(agentG, "Paper starting balance (USDT)", p.getString("agentStartS", "100")!!)
        val aMaxPos = field(agentG, "Max open positions", p.getInt("agentMaxPos", 3).toString())
        val aVol = field(agentG, "Min 24h volume (million USDT)", p.getString("agentVolS", "5")!!)
        val aDd = field(agentG, "Max drawdown % (closes all and halts)", p.getString("agentDdS", "10")!!)
        val unlockText = TextView(this).apply { setPadding(0, 16, 0, 0) }
        agentG.addView(unlockText)
        val aLive = CheckBox(this).apply { text = "Trade LIVE with real money"; isChecked = p.getBoolean("agentLive", false) }
        agentG.addView(aLive)
        val clearHalt = Button(this).apply { text = "Clear halt (resume after drawdown stop)" }
        agentG.addView(clearHalt)

        val accountG = group(root, "Account")
        val key = field(accountG, "Binance API key", sec.getString("key", "")!!, number = false, pw = true)
        val secret = field(accountG, "Binance API secret", sec.getString("secret", "")!!, number = false, pw = true)
        accountG.addView(paper)

        root.addView(backtest)
        toggle = Button(this); root.addView(toggle)
        val battery = Button(this).apply { text = "Allow background running (battery)" }
        root.addView(battery)

        panels = TextView(this).apply { textSize = 13f; typeface = android.graphics.Typeface.MONOSPACE; setPadding(0, 20, 0, 0) }
        root.addView(panels)
        curve = EquityView(this)
        root.addView(curve, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 300))
        status = TextView(this).apply { textSize = 16f; setPadding(0, 20, 0, 10) }; root.addView(status)
        logView = TextView(this).apply { textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE }; root.addView(logView)

        fun isTrend() = strategy.selectedItemPosition == 1
        fun isDca() = strategy.selectedItemPosition == 2
        fun isAgents() = strategy.selectedItemPosition == 3
        fun validInterval() = interval.text.toString().trim() in listOf("1m", "5m", "15m", "1h", "4h", "1d")
        fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
        fun vis(v: View, on: Boolean) { v.visibility = if (on) View.VISIBLE else View.GONE }

        fun refreshUnlock() {
            val (ok, msg) = Unlock.check(paperStore, System.currentTimeMillis())
            unlockText.text = msg
            aLive.isEnabled = ok
            if (!ok) aLive.isChecked = false
        }

        fun applyMode() {
            agentMode = isAgents()
            val single = !agentMode
            vis(common, single); vis(gridG, strategy.selectedItemPosition == 0)
            vis(trendG, isTrend() || isDca()); vis(dcaG, isDca()); vis(limitsG, single)
            vis(paper, single); vis(backtest, single); vis(agentG, agentMode)
            vis(panels, agentMode); vis(curve, agentMode)
            if (agentMode) refreshUnlock()
        }
        strategy.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(a: AdapterView<*>?, v: View?, pos: Int, id: Long) = applyMode()
            override fun onNothingSelected(a: AdapterView<*>?) {}
        }
        applyMode()

        clearHalt.setOnClickListener {
            for (st in listOf(paperStore, liveStore)) { st.kvSet("halted", "0"); st.kvSet("peak", "0") }
            toast("Halt cleared. The drawdown limit now counts from the current balance.")
        }

        // Returns an error message, or null when the inputs are usable.
        fun validate(): String? {
            if (isAgents()) {
                if ((aMaxPos.text.toString().toIntOrNull() ?: 0) !in 1..10) return "Max positions must be 1 to 10"
                if ((aStart.text.toString().toDoubleOrNull() ?: 0.0) < 20) return "Paper balance must be at least 20 USDT"
                if ((aDd.text.toString().toDoubleOrNull() ?: 0.0) !in 1.0..50.0) return "Max drawdown must be between 1 and 50 %"
                if ((aVol.text.toString().toDoubleOrNull() ?: -1.0) < 0) return "Enter a minimum volume"
                return null
            }
            val q = quote.text.toString().toDoubleOrNull()
            if (q == null || q <= 0) return "Enter the USDT amount"
            if (symbol.text.isBlank()) return "Enter a symbol"
            if (!validInterval()) return "Interval must be 1m, 5m, 15m, 1h, 4h or 1d"
            if (isDca()) {
                val m = dcaEvery.text.toString().toIntOrNull()
                if (m == null || m < 1) return "DCA interval must be at least 1 minute"
            } else if (!isTrend()) {
                val lo = lower.text.toString().toDoubleOrNull()
                val up = upper.text.toString().toDoubleOrNull()
                val n = levels.text.toString().toIntOrNull()
                if (lo == null || up == null || n == null || lo >= up || n < 3) return "Check price range and grid lines (≥3)"
                val spacing = (up - lo) / (n - 1) / lo * 100
                if (spacing < 0.3) return "Grid spacing is ${"%.2f".format(spacing)}% — below 0.3% the 0.2% round-trip fees eat the profit. Use fewer lines or a wider range."
            }
            return null
        }

        fun symbolNow() = symbol.text.toString().trim().uppercase()

        tight.setOnClickListener {
            thread {
                try {
                    val price = BinanceApi("", "").price(symbolNow())
                    fun fmt(x: Double) = BigDecimal(x).round(MathContext(6)).toPlainString()
                    ui.post {
                        lower.setText(fmt(price * 0.97)); upper.setText(fmt(price * 1.03)); levels.setText("15")
                        toast("Range set around $price")
                    }
                } catch (e: Exception) { ui.post { toast("Could not get price: ${e.message}") } }
            }
        }

        backtest.setOnClickListener {
            validate()?.let { toast(it); return@setOnClickListener }
            val sym = symbolNow(); val q = quote.text.toString().toDouble(); val iv = interval.text.toString().trim()
            val trendMode = isTrend()
            val dcaMode = isDca()
            val dcaCfg = if (dcaMode) DcaConfig(sym, q, dcaEvery.text.toString().toInt(), dcaMax.text.toString().toDoubleOrNull() ?: 0.0,
                tpPct.text.toString().toDoubleOrNull() ?: 0.0, true) else null
            val grid = if (!trendMode && !dcaMode) GridConfig(sym, lower.text.toString().toDouble(), upper.text.toString().toDouble(),
                levels.text.toString().toInt(), q, true, sl.text.toString().toDoubleOrNull() ?: 0.0) else null
            val trend = if (trendMode && !dcaMode) TrendConfig(sym, q, slPct.text.toString().toDoubleOrNull() ?: 0.0,
                tpPct.text.toString().toDoubleOrNull() ?: 0.0, true) else null
            backtest.isEnabled = false; backtest.text = "Running…"
            thread {
                val result = try {
                    Backtest.run(BinanceApi("", "").klines(sym, iv, 1000), grid, trend, dcaCfg)
                } catch (e: Exception) { "Backtest failed: ${e.message}" }
                ui.post {
                    backtest.isEnabled = true; backtest.text = "Run backtest (last ~1000 candles)"
                    AlertDialog.Builder(this).setTitle("Backtest result").setMessage(result)
                        .setPositiveButton("OK", null).show()
                }
            }
        }

        battery.setOnClickListener {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName))
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            else toast("Already allowed")
        }

        toggle.setOnClickListener {
            if (BotState.running) {
                startService(Intent(this, BotService::class.java).setAction("STOP"))
                return@setOnClickListener
            }
            validate()?.let { toast(it); return@setOnClickListener }
            val live = if (isAgents()) aLive.isChecked else !paper.isChecked
            if (isAgents() && live && !Unlock.check(paperStore, System.currentTimeMillis()).first) {
                toast("Live is still locked: run paper mode first"); return@setOnClickListener
            }
            if (live && (key.text.isBlank() || secret.text.isBlank())) {
                toast("API key/secret needed for live mode"); return@setOnClickListener
            }
            val e = p.edit()
                .putString("strategy", when { isAgents() -> "agents"; isDca() -> "dca"; isTrend() -> "trend"; else -> "grid" })
                .putBoolean("paper", paper.isChecked)
            if (isAgents()) {
                e.putString("agentStartS", aStart.text.toString()).putInt("agentMaxPos", aMaxPos.text.toString().toInt())
                    .putString("agentVolS", aVol.text.toString()).putString("agentDdS", aDd.text.toString())
                    .putBoolean("agentLive", aLive.isChecked)
            } else {
                e.putString("symbol", symbolNow())
                    .putString("interval", interval.text.toString().trim())
                    .putString("lowerS", lower.text.toString().ifBlank { "0" })
                    .putString("upperS", upper.text.toString().ifBlank { "0" })
                    .putInt("levels", levels.text.toString().toIntOrNull() ?: 10)
                    .putString("quoteS", quote.text.toString())
                    .putString("slS", sl.text.toString().ifBlank { "0" })
                    .putString("slPctS", slPct.text.toString().ifBlank { "0" })
                    .putString("tpPctS", tpPct.text.toString().ifBlank { "0" })
                    .putInt("dcaEvery", dcaEvery.text.toString().toIntOrNull() ?: 60)
                    .putString("dcaMaxS", dcaMax.text.toString().ifBlank { "0" })
                    .putString("dayWinS", dayWin.text.toString().ifBlank { "0" })
                    .putString("dayLossS", dayLoss.text.toString().ifBlank { "0" })
            }
            e.apply()
            sec.edit().putString("key", key.text.toString().trim()).putString("secret", secret.text.toString().trim()).apply()
            startForegroundService(Intent(this, BotService::class.java))
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            toggle.text = if (BotState.running) "STOP BOT" else "START BOT"
            status.text = "Status: ${BotState.status}"
            logView.text = synchronized(BotState.logs) { BotState.logs.take(40).joinToString("\n") }
            if (agentMode) {
                panels.text = panelOrder.joinToString("\n") { "${it.padEnd(10)} ${BotState.agents[it] ?: "-"}" }
                curve.pts = BotState.equity
            }
            ui.postDelayed(this, 1000)
        }
    }
    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }
}
