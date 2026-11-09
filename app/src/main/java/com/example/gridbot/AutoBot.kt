package com.example.gridbot

import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

// ───────────────────────── shared types ─────────────────────────

data class Ticker(val symbol: String, val last: Double, val quoteVol: Double, val pct24h: Double)
data class Rules(val step: BigDecimal, val minNotional: Double)
data class Fill(val qty: BigDecimal, val quote: Double, val avg: Double)

interface Market {
    fun now(): Long
    fun tradable(): Set<String>
    fun tickers(): List<Ticker>
    fun klines(symbol: String, interval: String, limit: Int): List<Candle>
    fun book(symbol: String): Pair<Double, Double>   // bid, ask
    fun rules(symbol: String): Rules
}

interface Broker {
    val paper: Boolean
    fun cash(): Double
    fun buy(symbol: String, quote: Double): Fill
    fun sell(symbol: String, qty: BigDecimal): Fill
}

/** Settings the learner may change, always inside [Bounds]. */
data class Params(
    var minSpike: Double = 3.0,   // volume spike multiple needed to consider a coin
    var sizePct: Double = 15.0,   // % of equity per trade
    var tpR: Double = 2.0,        // take-profit = stop distance x tpR
    var stopPct: Double = 1.5,    // stop-loss distance in % (fixed, not learned)
)

object Bounds {
    const val MIN_SPIKE_LO = 2.0; const val MIN_SPIKE_HI = 6.0
    const val SIZE_LO = 5.0; const val SIZE_HI = 25.0
    const val TPR_LO = 1.25; const val TPR_HI = 3.0
}

/** Hard limits. The learner can never change these. */
data class AutoConfig(
    val startBalance: Double = 100.0,      // paper mode only
    val maxPositions: Int = 3,
    val minVolUsd: Double = 5_000_000.0,   // minimum 24h volume
    val maxSpreadPct: Double = 0.15,
    val slipCapPct: Double = 0.4,
    val maxDrawdownPct: Double = 10.0,     // from peak equity -> close all and halt
    val dailyLossPct: Double = 3.0,        // no new entries for the rest of the UTC day
    val maxExposurePct: Double = 60.0,
    val maxHoldMin: Int = 90,
    val scanEveryMs: Long = 60_000L,
    val fee: Double = 0.001,
    val entryStyle: String = "momentum",   // "momentum" (buy rising coins) or "dip" (buy oversold pullbacks)
)

data class Signal(
    val symbol: String, val price: Double, val spike: Double,
    val ret15: Double, val ret1h: Double, val vol24h: Double, val newListingAgeMin: Long?,
)

data class Pos(
    val symbol: String, val entry: Double, val qty: BigDecimal, val cost: Double, var peak: Double,
    val openedMs: Long, val spike: Double, val expected: Double, val stop: Double, val tp: Double, val slipPct: Double,
) {
    fun toCsv() = listOf(symbol, entry, qty.toPlainString(), cost, peak, openedMs, spike, expected, stop, tp, slipPct).joinToString(",")
    companion object {
        fun parse(l: String): Pos? = try {
            val s = l.split(",")
            Pos(s[0], s[1].toDouble(), BigDecimal(s[2]), s[3].toDouble(), s[4].toDouble(), s[5].toLong(),
                s[6].toDouble(), s[7].toDouble(), s[8].toDouble(), s[9].toDouble(), s[10].toDouble())
        } catch (e: Exception) { null }
    }
}

data class Trade(
    val ts: Long, val symbol: String, val entry: Double, val exit: Double, val pnlUsd: Double, val pnlPct: Double,
    val spike: Double, val slipPct: Double, val reason: String, val heldMin: Long,
) {
    fun toCsv() = listOf(ts, symbol, entry, exit, pnlUsd, pnlPct, spike, slipPct, reason.replace(",", ";"), heldMin).joinToString(",")
    companion object {
        fun parse(l: String): Trade? = try {
            val s = l.split(",")
            Trade(s[0].toLong(), s[1], s[2].toDouble(), s[3].toDouble(), s[4].toDouble(), s[5].toDouble(),
                s[6].toDouble(), s[7].toDouble(), s[8], s[9].toLong())
        } catch (e: Exception) { null }
    }
}

// ───────────────────────── storage ─────────────────────────

class Store(val dir: File) {
    init { dir.mkdirs() }
    private fun file(n: String) = File(dir, n)
    fun append(n: String, line: String) { file(n).appendText(line + "\n") }
    fun lines(n: String): List<String> = file(n).let { if (it.exists()) it.readLines() else emptyList() }
    fun write(n: String, text: String) { file(n).writeText(text) }
    fun exists(n: String) = file(n).exists()
    fun props(n: String): Properties = Properties().also { p ->
        file(n).let { if (it.exists()) it.inputStream().use(p::load) }
    }
    fun saveProps(n: String, p: Properties) { file(n).outputStream().use { p.store(it, null) } }
    fun kvGet(k: String): String? = props("kv.properties").getProperty(k)
    fun kvSet(k: String, v: String) { val p = props("kv.properties"); p.setProperty(k, v); saveProps("kv.properties", p) }
    fun trades(): List<Trade> = lines("trades.csv").mapNotNull(Trade::parse)
}

// ───────────────────────── 5. LOGGER: no trade without a reason ─────────────────────────

class AgentLogger(private val store: Store, private val clock: () -> Long, private val sink: (String) -> Unit) {
    var n = 0; private set
    init {
        val all = store.lines("agent.log")
        n = all.size
        if (all.size > 6000) { store.write("agent.log", all.takeLast(3000).joinToString("\n") + "\n") ; n = 3000 }
    }
    fun log(kind: String, symbol: String, detail: String) {
        n++
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(clock()))
        val line = "#$n $t $kind $symbol  $detail"
        store.append("agent.log", line); sink(line)
    }
}

// ───────────────────────── 1. SCANNER: flags only, never trades ─────────────────────────

class Scanner(private val m: Market, private val cfg: AutoConfig, private val logger: AgentLogger) {
    private val known = HashSet<String>()
    private val newAt = HashMap<String, Long>()
    private val lastCandle = HashMap<String, Long>()
    var lastScanned = 0; private set

    private val stable = setOf("USDC", "FDUSD", "TUSD", "USDP", "DAI", "EUR", "EURI", "AEUR", "USD1", "XUSD", "BFUSD")
    private fun excluded(sym: String): Boolean {
        val b = sym.removeSuffix("USDT")
        return b in stable || b.endsWith("UP") || b.endsWith("DOWN") || b.endsWith("BULL") || b.endsWith("BEAR")
    }

    fun scan(minSpike: Double): List<Signal> {
        val tradable = m.tradable()
        if (known.isEmpty()) known.addAll(tradable)
        else for (s in tradable - known) {
            known.add(s); newAt[s] = m.now()
            logger.log("NEWPAIR", s, "new listing detected, observing before any trade")
        }
        val tk = m.tickers().filter { it.symbol in tradable && !excluded(it.symbol) && it.quoteVol >= cfg.minVolUsd }
        val picks = (tk.sortedByDescending { it.quoteVol }.take(20) + tk.sortedByDescending { it.pct24h }.take(10))
            .distinctBy { it.symbol }
        lastScanned = picks.size
        val out = ArrayList<Signal>()
        for (t in picks) {
            val cs = try { m.klines(t.symbol, "5m", 30) } catch (e: Exception) { continue }
            if (cs.size < 25) continue
            val closed = cs.dropLast(1)            // last candle is still forming
            val last = closed.last()
            if (lastCandle[t.symbol] == last.t) continue   // each closed candle judged once
            lastCandle[t.symbol] = last.t
            val baseVol = closed.dropLast(1).takeLast(20).map { it.v }.average()
            if (baseVol <= 0.0) continue
            val spike = last.v / baseVol
            val ret15 = last.c / closed[closed.size - 4].c - 1
            val ret1h = last.c / closed[closed.size - 13].c - 1
            if (spike >= minSpike && last.c > last.o && ret15 > 0.003) {
                val age = newAt[t.symbol]?.let { (m.now() - it) / 60_000 }
                out.add(Signal(t.symbol, t.last, spike, ret15 * 100, ret1h * 100, t.quoteVol, age))
            }
        }
        return out
    }
}

// ───────────────────────── 2. RUG CHECK: filters before anything executes ─────────────────────────

class RugCheck(private val m: Market, private val cfg: AutoConfig) {
    private var btcAt = 0L
    private var btcRet = 0.0

    /** BTC move over the last hour, refreshed every 5 minutes. */
    fun btcHour(): Double {
        val now = m.now()
        if (now - btcAt > 300_000) {
            btcAt = now
            btcRet = try {
                val c = m.klines("BTCUSDT", "1h", 3)
                if (c.size >= 2) c.last().c / c[c.size - 2].c - 1 else 0.0
            } catch (e: Exception) { 0.0 }
        }
        return btcRet
    }

    /** Market regime label used by memory: down, up or flat. */
    fun regimeNow(): String = btcHour().let { if (it < -0.01) "down" else if (it > 0.01) "up" else "flat" }

    /** Returns null when the coin passes, otherwise the reason it was filtered. */
    fun check(sig: Signal, openSymbols: Set<String>, recent: List<Trade>, openCount: Int): String? {
        if (sig.symbol in openSymbols) return "already holding it"
        if (openCount >= cfg.maxPositions) return "max ${cfg.maxPositions} positions open"
        sig.newListingAgeMin?.let { if (it < 360) return "new listing only ${it} min old (waits 6h)" }
        val now = m.now()
        val mine = recent.filter { it.symbol == sig.symbol }.sortedByDescending { it.ts }
        if (mine.isNotEmpty() && mine[0].pnlUsd < 0) {
            val hours = if (mine.size >= 2 && mine[1].pnlUsd < 0) 24 else 6
            if (now - mine[0].ts < hours * 3_600_000L) return "cooling down ${hours}h after a loss on it"
        }
        if (sig.ret1h > 12.0) return "already up ${"%.1f".format(sig.ret1h)}% in 1h (chasing a pump)"
        val (bid, ask) = m.book(sig.symbol)
        val spread = (ask - bid) / ask * 100
        if (spread > cfg.maxSpreadPct) return "spread ${"%.2f".format(spread)}% too wide"
        if (ask / sig.price - 1 > cfg.slipCapPct / 100) return "ask already ${"%.2f".format((ask / sig.price - 1) * 100)}% above signal price"
        if (btcHour() < -0.02) return "BTC dropping more than 2% in the last hour"
        return null
    }
}

// ───────────────────────── 3. SIZER: from account equity, never a fixed ticket ─────────────────────────

class Sizer(private val m: Market, private val cfg: AutoConfig) {
    /** Returns (USDT to spend, explanation) or (0, reason it cannot size). */
    fun size(sym: String, equity: Double, cash: Double, exposure: Double, p: Params): Pair<Double, String> {
        var q = equity * p.sizePct / 100
        val room = equity * cfg.maxExposurePct / 100 - exposure
        if (room <= 0) return 0.0 to "total exposure already at ${cfg.maxExposurePct.toInt()}% of equity"
        q = minOf(q, room, cash * 0.98)
        val min = m.rules(sym).minNotional * 1.2
        if (q < min) return 0.0 to "size ${"%.2f".format(q)} USDT below exchange minimum ${"%.2f".format(min)}"
        return q to "${"%.0f".format(p.sizePct)}% of ${"%.2f".format(equity)} equity"
    }
}

// ───────────────────────── 4. EXECUTOR: opens and closes, tracks slippage ─────────────────────────

class Executor(
    private val m: Market, private val broker: Broker, private val store: Store,
    private val cfg: AutoConfig, private val logger: AgentLogger,
) {
    val positions = ArrayList<Pos>().also { l -> store.lines("positions.csv").mapNotNullTo(l, Pos::parse) }
    private val slips = ArrayList<Double>()
    val avgSlip get() = if (slips.isEmpty()) 0.0 else slips.average()

    private fun persist() = store.write("positions.csv", positions.joinToString("\n") { it.toCsv() } + if (positions.isEmpty()) "" else "\n")

    fun open(sig: Signal, quote: Double, stopPct: Double, tpPct: Double, why: String): Boolean {
        return try {
            val f = broker.buy(sig.symbol, quote)
            if (f.qty.signum() <= 0) throw RuntimeException("order returned zero quantity")
            val slip = (f.avg / sig.price - 1) * 100
            slips.add(slip)
            val pos = Pos(sig.symbol, f.avg, f.qty, f.quote, f.avg, m.now(), sig.spike, sig.price,
                f.avg * (1 - stopPct / 100), f.avg * (1 + tpPct / 100), slip)
            positions.add(pos); persist()
            logger.log("ENTER", sig.symbol,
                "size ${"%.2f".format(f.quote)} USDT | $why | stop ${"%.2f".format(stopPct)}% tp ${"%.2f".format(tpPct)}% | spike ${"%.1f".format(sig.spike)}x, 15m +${"%.1f".format(sig.ret15)}% | " +
                    "slip ${"%.2f".format(slip)}% | stop ${"%.6g".format(pos.stop)} tp ${"%.6g".format(pos.tp)}")
            true
        } catch (e: Exception) { logger.log("ERROR", sig.symbol, "buy failed: ${e.message}"); false }
    }

    /** Why this position should close now, or null. Also ratchets the peak price. */
    fun exitReason(pos: Pos, bid: Double, now: Long, p: Params): String? {
        if (bid > pos.peak) { pos.peak = bid; persist() }
        if (bid <= pos.stop) return "stop-loss"
        if (bid >= pos.tp) return "take-profit"
        val stopPct = (pos.entry - pos.stop) / pos.entry * 100
        val trailStart = pos.entry * (1 + stopPct / 100)
        if (pos.peak >= trailStart && bid <= pos.peak * (1 - stopPct * 0.7 / 100)) return "trailing stop"
        if ((now - pos.openedMs) / 60_000 >= cfg.maxHoldMin) return "time stop ${cfg.maxHoldMin}min"
        return null
    }

    fun close(pos: Pos, reason: String): Trade? {
        return try {
            val rules = m.rules(pos.symbol)
            val qty = pos.qty.divide(rules.step, 0, RoundingMode.DOWN).multiply(rules.step)
            val (bid, _) = m.book(pos.symbol)
            if (qty.signum() <= 0 || qty.toDouble() * bid < rules.minNotional) {
                positions.remove(pos); persist()
                logger.log("DUST", pos.symbol, "position too small to sell, dropped from tracking ($reason)")
                return null
            }
            val f = broker.sell(pos.symbol, qty)
            positions.remove(pos); persist()
            val pnl = f.quote - pos.cost
            val tr = Trade(m.now(), pos.symbol, pos.entry, f.avg, pnl, pnl / pos.cost * 100, pos.spike, pos.slipPct,
                reason, (m.now() - pos.openedMs) / 60_000)
            logger.log("EXIT", pos.symbol, "$reason | ${if (pnl >= 0) "+" else ""}${"%.2f".format(pnl)} USDT (${"%.2f".format(tr.pnlPct)}%) | held ${tr.heldMin}min")
            tr
        } catch (e: Exception) { logger.log("ERROR", pos.symbol, "sell failed (will retry): ${e.message}"); null }
    }
}

// ───────────────────────── learner: adjusts settings inside hard bounds ─────────────────────────

class Learner(private val store: Store, private val logger: AgentLogger) {
    var params: Params = load()
        private set

    private fun load(): Params {
        val p = store.props("params.properties")
        val d = Params()
        return Params(
            (p.getProperty("minSpike")?.toDoubleOrNull() ?: d.minSpike).coerceIn(Bounds.MIN_SPIKE_LO, Bounds.MIN_SPIKE_HI),
            (p.getProperty("sizePct")?.toDoubleOrNull() ?: d.sizePct).coerceIn(Bounds.SIZE_LO, Bounds.SIZE_HI),
            d.tpR, // fixed: the learner no longer changes take-profit distance
            d.stopPct,
        )
    }

    private fun save() {
        val p = Properties()
        p.setProperty("minSpike", params.minSpike.toString()); p.setProperty("sizePct", params.sizePct.toString())
        p.setProperty("tpR", params.tpR.toString())
        store.saveProps("params.properties", p)
    }

    /** Called after every closed trade. Needs 20+ trades and acts once per 10 trades, in small steps. */
    fun onClosed(all: List<Trade>) {
        val n = all.size
        if (n < 20 || n % 10 != 0) return
        val w = all.takeLast(30)
        val winRate = w.count { it.pnlUsd > 0 }.toDouble() / w.size
        val avg = w.map { it.pnlPct }.average()
        val old = params.copy()
        var notes = ArrayList<String>()

        val sorted = w.sortedBy { it.spike }
        val lo = sorted.take(w.size / 2); val hi = sorted.takeLast(w.size / 2)
        if (lo.size >= 5 && hi.size >= 5) {
            val loAvg = lo.map { it.pnlPct }.average(); val hiAvg = hi.map { it.pnlPct }.average()
            if (hiAvg - loAvg > 0.3) {
                params.minSpike = (params.minSpike + 0.25).coerceIn(Bounds.MIN_SPIKE_LO, Bounds.MIN_SPIKE_HI)
                notes.add("bigger spikes did better (${"%+.2f".format(hiAvg)}% vs ${"%+.2f".format(loAvg)}%) -> minSpike up")
            } else if (loAvg - hiAvg > 0.3) {
                params.minSpike = (params.minSpike - 0.25).coerceIn(Bounds.MIN_SPIKE_LO, Bounds.MIN_SPIKE_HI)
                notes.add("smaller spikes did better (${"%+.2f".format(loAvg)}% vs ${"%+.2f".format(hiAvg)}%) -> minSpike down")
            }
        }
        if (winRate < 0.35 && avg < 0) {
            params.sizePct = (params.sizePct * 0.8).coerceIn(Bounds.SIZE_LO, Bounds.SIZE_HI)
            notes.add("win rate ${"%.0f".format(winRate * 100)}% and avg ${"%+.2f".format(avg)}% -> smaller size")
        } else if (winRate > 0.55 && avg > 0.2) {
            params.sizePct = (params.sizePct * 1.1).coerceIn(Bounds.SIZE_LO, Bounds.SIZE_HI)
            notes.add("win rate ${"%.0f".format(winRate * 100)}% and avg ${"%+.2f".format(avg)}% -> slightly larger size")
        }
        if (params != old) {
            save()
            logger.log("LEARN", "-", "after $n trades: ${notes.joinToString("; ")} | minSpike ${old.minSpike}->${params.minSpike}, " +
                "size ${"%.1f".format(old.sizePct)}->${"%.1f".format(params.sizePct)}%, tpR ${old.tpR}->${params.tpR}")
        } else logger.log("LEARN", "-", "after $n trades: reviewed (win ${"%.0f".format(winRate * 100)}%, avg ${"%+.2f".format(avg)}%), no change needed")
    }
}

// ───────────────────────── paper broker (real prices, simulated wallet) ─────────────────────────

class PaperBroker(private val m: Market, private val store: Store, start: Double, private val fee: Double = 0.001) : Broker {
    override val paper = true
    private var cash: Double = store.kvGet("paperCash")?.toDoubleOrNull() ?: start.also { store.kvSet("paperCash", it.toString()) }
    override fun cash() = cash
    override fun buy(symbol: String, quote: Double): Fill {
        if (quote > cash + 1e-9) throw IllegalStateException("not enough paper cash")
        val px = m.book(symbol).second * 1.0005       // ask plus modelled slippage
        val st = m.rules(symbol).step
        val qty = BigDecimal(quote * (1 - fee) / px).divide(st, 0, RoundingMode.DOWN).multiply(st)
        cash -= quote; store.kvSet("paperCash", cash.toString())
        return Fill(qty, quote, px)
    }
    override fun sell(symbol: String, qty: BigDecimal): Fill {
        val px = m.book(symbol).first * 0.9995
        val proceeds = qty.toDouble() * px * (1 - fee)
        cash += proceeds; store.kvSet("paperCash", cash.toString())
        return Fill(qty, proceeds, px)
    }
}

object Unlock {
    /** Live trading unlocks only after paper results: 7 days, 10 closed trades, positive PnL. */
    fun check(paperStore: Store, now: Long): Pair<Boolean, String> {
        val start = paperStore.kvGet("paperStart")?.toLongOrNull() ?: return false to "Live locked: paper mode has not run yet"
        val days = (now - start) / 86_400_000.0
        val tr = paperStore.trades()
        val pnl = tr.sumOf { it.pnlUsd }
        val why = ArrayList<String>()
        if (days < 7) why.add("needs 7 days of paper trading (${"%.1f".format(days)} so far)")
        if (tr.size < 10) why.add("needs 10 closed trades (${tr.size} so far)")
        if (tr.size >= 10 && pnl <= 0) why.add("paper result is not profitable (${"%.2f".format(pnl)} USDT)")
        return if (why.isEmpty()) true to "Live unlocked (paper: ${tr.size} trades, ${"%+.2f".format(pnl)} USDT)"
        else false to "Live locked: " + why.joinToString("; ")
    }
}

// ───────────────────────── orchestrator ─────────────────────────

class AutoBot(
    private val m: Market, private val broker: Broker, private val store: Store, private val cfg: AutoConfig,
    sink: (String) -> Unit, private val panel: (String, String) -> Unit,
) {
    private val logger = AgentLogger(store, m::now, sink)
    private val learner = Learner(store, logger)
    private val scanner = Scanner(m, cfg, logger)
    private val rug = RugCheck(m, cfg)
    private val sizer = Sizer(m, cfg)
    private val exec = Executor(m, broker, store, cfg, logger)
    private val memory = Memory(store)

    private var lastScan = 0L
    private var lastEquityLog = 0L
    private var filtered = 0
    private var passed = 0
    private var lastFilter = "-"
    private var lastRegime = "-"
    val curve = ArrayList<Double>().also { l -> store.lines("equity.csv").takeLast(300).mapNotNullTo(l) { it.split(",").getOrNull(1)?.toDoubleOrNull() } }
    val halted get() = store.kvGet("halted") == "1"

    fun params() = learner.params

    private fun equity(bids: Map<String, Double>): Double =
        broker.cash() + exec.positions.sumOf { it.qty.toDouble() * (bids[it.symbol] ?: it.entry) }

    private fun record(tr: Trade) {
        store.append("trades.csv", tr.toCsv())
        memory.record(tr, tr.ts - tr.heldMin * 60_000L, store.kvGet("regimeAt:${tr.symbol}") ?: "flat")
        logger.log("TAG", tr.symbol, LossTagger.tag(tr))
        learner.onClosed(store.trades())
    }

    private fun halt(why: String, bids: Map<String, Double>) {
        store.kvSet("halted", "1")
        logger.log("HALT", "-", "$why — closing everything and stopping new trades until you clear the halt")
        for (p in exec.positions.toList()) exec.close(p, "halt")?.let(::record)
    }

    /** Dip-buy candidates: liquid coins whose hourly candles show an oversold pullback. */
    private fun dipScan(): List<Signal> {
        val tradable = m.tradable()
        val top = m.tickers()
            .filter { it.symbol in tradable && it.quoteVol >= cfg.minVolUsd &&
                !it.symbol.endsWith("UPUSDT") && !it.symbol.endsWith("DOWNUSDT") }
            .sortedByDescending { it.quoteVol }
            .take(15)
        val out = ArrayList<Signal>()
        for (t in top) {
            val cs = try { m.klines(t.symbol, "1h", 120) } catch (e: Exception) { continue }
            if (!Signals.dipBuy(cs)) continue
            val ret1h = cs.last().c / cs[cs.size - 2].c - 1
            out.add(Signal(t.symbol, t.last, 0.0, 0.0, ret1h * 100, t.quoteVol, null))
        }
        return out
    }

    fun tick() {
        val now = m.now()
        val p = learner.params
        val bids = HashMap<String, Double>()
        var netErr = false

        // EXECUTOR: manage open positions every tick
        for (pos in exec.positions.toList()) {
            try {
                val bid = m.book(pos.symbol).first
                bids[pos.symbol] = bid
                exec.exitReason(pos, bid, now, p)?.let { r -> exec.close(pos, r)?.let(::record) }
            } catch (e: Exception) { netErr = true; logger.log("ERROR", pos.symbol, "price check failed (exit still pending): ${e.message}") }
        }

        // EQUITY: curve + circuit breakers
        val eq = equity(bids)
        if (store.kvGet("startEquity") == null) store.kvSet("startEquity", eq.toString())
        val peak = maxOf(store.kvGet("peak")?.toDoubleOrNull() ?: eq, eq)
        store.kvSet("peak", peak.toString())
        val day = (now / 86_400_000L).toString()
        if (store.kvGet("dayKey") != day) { store.kvSet("dayKey", day); store.kvSet("dayStart", eq.toString()) }
        val dayStart = store.kvGet("dayStart")?.toDoubleOrNull() ?: eq
        if (now - lastEquityLog >= 600_000 || curve.isEmpty()) {
            lastEquityLog = now; curve.add(eq); store.append("equity.csv", "$now,$eq")
        }
        val trades = store.trades()
        val wins = trades.count { it.pnlUsd > 0 }; val losses = trades.size - wins
        val dd = (peak - eq) / peak * 100
        val pausedToday = eq <= dayStart * (1 - cfg.dailyLossPct / 100)

        panel("EQUITY", "${"%.2f".format(eq)} USDT · ${trades.size} closed · ${wins}W/${losses}L · drawdown ${"%.1f".format(dd)}%")
        panel("LOGGER", "line #${logger.n} · every decision has a reason")
        panel("EXECUTOR", "open ${exec.positions.size} · avg slippage ${"%.2f".format(exec.avgSlip)}% · cap ${cfg.slipCapPct}%")
        panel("SIZER", "${"%.0f".format(p.sizePct)}% of equity = ${"%.2f".format(eq * p.sizePct / 100)} USDT per trade")
        panel("RUG CHECK", "passed $passed · filtered $filtered · last: $lastFilter")
        panel("MEMORY", memory.summary())
        panel("REGIME", lastRegime)

        if (halted) { panel("SCANNER", "HALTED — clear the halt in the app to resume"); return }
        if (dd >= cfg.maxDrawdownPct) { halt("drawdown ${"%.1f".format(dd)}% hit the ${cfg.maxDrawdownPct}% limit", bids); return }
        if (pausedToday) { panel("SCANNER", "paused: daily loss limit ${cfg.dailyLossPct}% reached, resumes tomorrow (UTC)"); return }

        // SCANNER -> RUG CHECK -> SIZER -> EXECUTOR
        if (now - lastScan < cfg.scanEveryMs) return
        lastScan = now
        val sigs = try { if (cfg.entryStyle == "dip") dipScan() else scanner.scan(p.minSpike) } catch (e: Exception) { logger.log("ERROR", "-", "scan failed: ${e.message}"); return }
        if (netErr) { logger.log("WAIT", "-", "connection problems this tick: no new entries until it recovers"); return }
        panel("SCANNER", "checked ${scanner.lastScanned} pairs · ${sigs.size} spikes ≥ ${"%.2f".format(p.minSpike)}x" +
            (sigs.maxByOrNull { it.spike }?.let { " · best ${it.symbol} ${"%.1f".format(it.spike)}x" } ?: ""))

        for (sig in sigs.sortedByDescending { it.spike }) {
            val open = exec.positions.map { it.symbol }.toSet()
            val why = try { rug.check(sig, open, trades, exec.positions.size) ?: memory.block(sig.symbol, rug.regimeNow(), m.now()) } catch (e: Exception) { "check failed: ${e.message}" }
            if (why != null) {
                filtered++; lastFilter = "${sig.symbol} $why"
                logger.log("FILTERED", sig.symbol, "not traded: $why")
                continue
            }
            passed++
            val reg = try { Regimes.of(m.klines(sig.symbol, "1h", 80)) } catch (e: Exception) { Regime.UNKNOWN }
            lastRegime = "${sig.symbol} ${reg.name}"
            if (reg == Regime.DOWN) {
                filtered++; lastFilter = "${sig.symbol} 1h downtrend"
                logger.log("FILTERED", sig.symbol, "not traded: 1h downtrend, no new long entries")
                continue
            }
            if (cfg.entryStyle == "momentum" && reg == Regime.RANGE && sig.spike < p.minSpike * 1.5) {
                filtered++; lastFilter = "${sig.symbol} weak range spike"
                logger.log("FILTERED", sig.symbol, "not traded: sideways market needs a spike of ${"%.2f".format(p.minSpike * 1.5)}x or more")
                continue
            }
            val exposure = exec.positions.sumOf { it.cost }
            val (base, note) = try { sizer.size(sig.symbol, eq, broker.cash(), exposure, p) } catch (e: Exception) { 0.0 to "sizing failed: ${e.message}" }
            val quote = if (reg == Regime.VOLATILE) base * 0.5 else base
            if (quote <= 0.0 || quote < m.rules(sig.symbol).minNotional) {
                logger.log("FILTERED", sig.symbol, "not traded: size ${"%.2f".format(quote)} USDT too small ($note)")
                continue
            }
            store.kvSet("regimeAt:${sig.symbol}", rug.regimeNow())
            // Dynamic per coin: stop is about one hour of normal movement, target is a multiple of the stop.
            val atr = try { Regimes.atrPct(m.klines(sig.symbol, "1h", 30)) } catch (e: Exception) { 2.0 }
            val stopPct = (atr * 1.0).coerceIn(0.8, 3.0)
            val tpPct = (stopPct * p.tpR).coerceIn(1.0, 6.0)
            exec.open(sig, quote, stopPct, tpPct, "$note | regime ${reg.name} | 1h volatility ${"%.2f".format(atr)}%")
        }
    }
}
