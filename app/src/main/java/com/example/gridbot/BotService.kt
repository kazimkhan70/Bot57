package com.example.gridbot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File
import kotlin.concurrent.thread

object Prefs {
    fun plain(c: Context) = c.getSharedPreferences("gridbot", Context.MODE_PRIVATE)
    fun secure(c: Context) = EncryptedSharedPreferences.create(
        c, "gridbot_secure",
        MasterKey.Builder(c).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
}

object BotState {
    @Volatile var running = false
    @Volatile var status = "Stopped"
    val agents = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile var equity: List<Double> = emptyList()
    val logs = ArrayDeque<String>()
    fun add(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        synchronized(logs) { logs.addFirst("$t  $msg"); while (logs.size > 200) logs.removeLast() }
    }
}

class BotService : Service() {
    @Volatile private var alive = false
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { shutdown(); return START_NOT_STICKY }
        if (alive) return START_STICKY

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("bot", "Bot status", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = notif("Starting…", open)
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, n)

        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gridbot:loop").apply { acquire() }

        alive = true; BotState.running = true
        Prefs.plain(this).edit().putBoolean("autostart", true).apply()
        thread(name = "bot-loop") { loop(nm, open) }
        return START_STICKY
    }

    private fun notif(text: String, pi: PendingIntent): Notification =
        NotificationCompat.Builder(this, "bot")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("GridBot")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()

    private fun utcDay() = System.currentTimeMillis() / 86_400_000L

    private fun loop(nm: NotificationManager, pi: PendingIntent) {
        val p = Prefs.plain(this)
        val kind = p.getString("strategy", "grid")!!
        if (kind == "agents") { agentLoop(nm, pi); return }
        val symbol = p.getString("symbol", "BTCUSDT")!!
        val paper = p.getBoolean("paper", true)
        val quote = p.getString("quoteS", "10")!!.toDouble()
        val interval = p.getString("interval", "5m")!!
        val dayWin = p.getString("dayWinS", "0")!!.toDoubleOrNull() ?: 0.0
        val dayLoss = p.getString("dayLossS", "0")!!.toDoubleOrNull() ?: 0.0

        val sec = Prefs.secure(this)
        val api = BinanceApi(sec.getString("key", "")!!, sec.getString("secret", "")!!)

        var trend: TrendEngine? = null
        val strat: Strategy = try {
            if (kind == "dca") {
                val c = DcaConfig(symbol, quote, p.getInt("dcaEvery", 60), p.getString("dcaMaxS", "0")!!.toDouble(),
                    p.getString("tpPctS", "0")!!.toDouble(), paper)
                BotState.add("Started $symbol DCA ${if (paper) "[PAPER]" else "[LIVE]"} $quote USDT every ${c.everyMin} min")
                DcaEngine(c, api, BotState::add)
            } else if (kind == "trend") {
                BotState.add("Started $symbol TREND ${if (paper) "[PAPER]" else "[LIVE]"} $interval candles, $quote USDT per trade")
                TrendEngine(
                    TrendConfig(symbol, quote, p.getString("slPctS", "0")!!.toDouble(),
                        p.getString("tpPctS", "0")!!.toDouble(), paper),
                    api, BotState::add
                ).also { trend = it }
            } else {
                val cfg = GridConfig(
                    symbol = symbol,
                    lower = p.getString("lowerS", "0")!!.toDouble(),
                    upper = p.getString("upperS", "0")!!.toDouble(),
                    levels = p.getInt("levels", 10),
                    quotePerGrid = quote,
                    paper = paper,
                    stopLoss = p.getString("slS", "0")!!.toDouble(),
                )
                BotState.add("Started $symbol GRID ${if (paper) "[PAPER]" else "[LIVE]"} ${cfg.lower}-${cfg.upper} x${cfg.levels}")
                GridEngine(cfg, api, BotState::add)
            }
        } catch (e: Exception) { BotState.add("Init failed: ${e.message}"); shutdown(); return }

        var dayKey = utcDay()
        var dayStart = strat.realizedPnl
        var lastCandle = 0L
        var errors = 0
        while (alive) {
            try {
                val price = api.price(symbol)
                strat.onPrice(price)

                trend?.let { t ->
                    val closed = api.klines(symbol, interval, 100).dropLast(1) // drop the still-forming candle
                    if (closed.isNotEmpty() && closed.last().t > lastCandle) {
                        lastCandle = closed.last().t
                        t.onCandle(closed.map { it.c }, price)
                    }
                }

                // daily profit / loss limits (realized PnL, UTC day)
                if (utcDay() != dayKey) {
                    dayKey = utcDay(); dayStart = strat.realizedPnl
                    if (strat.buysPaused) BotState.add("New day — new buys allowed again")
                    strat.buysPaused = false
                }
                val dayPnl = strat.realizedPnl - dayStart
                if (!strat.buysPaused) {
                    if (dayWin > 0 && dayPnl >= dayWin) {
                        strat.buysPaused = true
                        BotState.add("Daily profit target reached (${"%.2f".format(dayPnl)}) — no new buys until tomorrow (UTC)")
                    } else if (dayLoss > 0 && dayPnl <= -dayLoss) {
                        strat.buysPaused = true
                        BotState.add("Daily loss limit reached (${"%.2f".format(dayPnl)}) — no new buys until tomorrow (UTC)")
                    }
                }

                errors = 0
                BotState.status = "$symbol $price | open ${strat.openLots()} | PnL ${"%.2f".format(strat.realizedPnl)}" +
                    " (today ${"%.2f".format(dayPnl)})" + if (strat.buysPaused) " | PAUSED" else ""
                nm.notify(1, notif(BotState.status, pi))
                if (strat.stopped) { BotState.add("Bot stopped by stop-loss"); shutdown(); return }
            } catch (e: Exception) {
                errors++
                BotState.add("Error: ${e.message}")
                if (errors >= 20) { BotState.add("Too many errors, stopping"); shutdown(); return }
            }
            try { Thread.sleep(5000L * (1 + minOf(errors, 6))) } catch (_: InterruptedException) {}
        }
    }

    private fun agentLoop(nm: NotificationManager, pi: PendingIntent) {
        val p = Prefs.plain(this)
        val live = p.getBoolean("agentLive", false)
        val paperStore = Store(File(filesDir, "agents_paper"))
        val market: BinanceMarket
        val api: BinanceApi
        val sec = Prefs.secure(this)
        api = BinanceApi(sec.getString("key", "")!!, sec.getString("secret", "")!!)
        market = BinanceMarket(api)

        if (paperStore.kvGet("paperStart") == null) paperStore.kvSet("paperStart", System.currentTimeMillis().toString())
        if (live) {
            val (ok, msg) = Unlock.check(paperStore, System.currentTimeMillis())
            if (!ok) { BotState.add(msg); shutdown(); return }
        }
        val store = if (live) Store(File(filesDir, "agents_live")) else paperStore
        if (live && !store.exists("params.properties") && paperStore.exists("params.properties"))
            store.write("params.properties", paperStore.lines("params.properties").joinToString("\n") + "\n") // start live with what paper learned

        val cfg = AutoConfig(
            startBalance = p.getString("agentStartS", "100")!!.toDoubleOrNull() ?: 100.0,
            maxPositions = p.getInt("agentMaxPos", 3),
            minVolUsd = (p.getString("agentVolS", "5")!!.toDoubleOrNull() ?: 5.0) * 1_000_000,
            maxDrawdownPct = p.getString("agentDdS", "10")!!.toDoubleOrNull() ?: 10.0,
            entryStyle = p.getString("entryStyle", "momentum")!!,
        )
        val broker: Broker = if (live) LiveBroker(api) else PaperBroker(market, store, cfg.startBalance)
        val bot = AutoBot(market, broker, store, cfg, BotState::add) { k, v -> BotState.agents[k] = v }
        BotState.add("6-agent bot started ${if (live) "[LIVE]" else "[PAPER]"} — every decision is logged with a reason")

        var errors = 0
        while (alive) {
            try {
                bot.tick()
                BotState.equity = bot.curve.toList()
                errors = 0
                BotState.status = (BotState.agents["EQUITY"] ?: "running") + if (bot.halted) " | HALTED" else ""
                nm.notify(1, notif(BotState.status, pi))
            } catch (e: Exception) {
                errors++
                BotState.add("Error: ${e.message}")
                if (errors >= 30) { BotState.add("Too many errors, stopping"); shutdown(); return }
            }
            try { Thread.sleep(10_000L * (1 + minOf(errors, 6))) } catch (_: InterruptedException) {}
        }
    }

    private fun shutdown() {
        alive = false; BotState.running = false; BotState.status = "Stopped"
        Prefs.plain(this).edit().putBoolean("autostart", false).apply()
        wake?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { alive = false; wake?.let { if (it.isHeld) it.release() }; super.onDestroy() }
}
