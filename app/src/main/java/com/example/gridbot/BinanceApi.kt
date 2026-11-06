package com.example.gridbot

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** v = quote-asset volume (USDT) of the candle. */
data class Candle(val t: Long, val o: Double, val h: Double, val l: Double, val c: Double, val v: Double = 0.0)

/** Minimal Binance Spot REST client: public market data + signed market orders. */
class BinanceApi(private val apiKey: String, private val secret: String) {
    private val http = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
    private val base = "https://api.binance.com"

    fun price(symbol: String): Double {
        val body = get("$base/api/v3/ticker/price?symbol=$symbol", false)
        return JSONObject(body).getString("price").toDouble()
    }

    /** Public candle data (no API key needed). Last candle may still be forming. */
    fun klines(symbol: String, interval: String, limit: Int): List<Candle> {
        val arr = JSONArray(get("$base/api/v3/klines?symbol=$symbol&interval=$interval&limit=$limit", false))
        return (0 until arr.length()).map {
            val k = arr.getJSONArray(it)
            Candle(k.getLong(0), k.getString(1).toDouble(), k.getString(2).toDouble(),
                k.getString(3).toDouble(), k.getString(4).toDouble(), k.getString(7).toDouble())
        }
    }

    /** All 24h tickers (one call). */
    fun tickers24h(): List<Ticker> {
        val arr = JSONArray(get("$base/api/v3/ticker/24hr", false))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Ticker(o.getString("symbol"), o.getString("lastPrice").toDouble(),
                o.getString("quoteVolume").toDouble(), o.getString("priceChangePercent").toDouble())
        }
    }

    /** USDT spot symbols that are currently TRADING. */
    fun tradableUsdt(): Set<String> {
        val syms = JSONObject(get("$base/api/v3/exchangeInfo?permissions=SPOT", false)).getJSONArray("symbols")
        val out = HashSet<String>()
        for (i in 0 until syms.length()) {
            val s = syms.getJSONObject(i)
            if (s.getString("quoteAsset") == "USDT" && s.getString("status") == "TRADING") out.add(s.getString("symbol"))
        }
        return out
    }

    /** Returns (bid, ask). */
    fun book(symbol: String): Pair<Double, Double> {
        val o = JSONObject(get("$base/api/v3/ticker/bookTicker?symbol=$symbol", false))
        return o.getString("bidPrice").toDouble() to o.getString("askPrice").toDouble()
    }

    /** Step size and minimum order value for a symbol. */
    fun rules(symbol: String): Rules {
        val body = get("$base/api/v3/exchangeInfo?symbol=$symbol", false)
        val filters = JSONObject(body).getJSONArray("symbols").getJSONObject(0).getJSONArray("filters")
        var step = BigDecimal("0.00001"); var minNotional = 5.0
        for (i in 0 until filters.length()) {
            val f = filters.getJSONObject(i)
            when (f.getString("filterType")) {
                "LOT_SIZE" -> step = BigDecimal(f.getString("stepSize"))
                "NOTIONAL", "MIN_NOTIONAL" -> minNotional = f.optString("minNotional", "5").toDoubleOrNull() ?: 5.0
            }
        }
        return Rules(step, minNotional)
    }

    /** Returns LOT_SIZE stepSize for the symbol. */
    fun stepSize(symbol: String): BigDecimal = rules(symbol).step

    /** Free USDT in the spot wallet (needs a key with read permission). */
    fun usdtFree(): Double {
        val bal = JSONObject(signed("GET", "$base/api/v3/account", "omitZeroBalances=true")).getJSONArray("balances")
        for (i in 0 until bal.length()) {
            val b = bal.getJSONObject(i)
            if (b.getString("asset") == "USDT") return b.getString("free").toDouble()
        }
        return 0.0
    }

    /** Market buy spending [quote] USDT. qty is net of fees taken in the bought coin. */
    fun buyFill(symbol: String, quote: Double): Fill {
        val q = "symbol=$symbol&side=BUY&type=MARKET&quoteOrderQty=${BigDecimal(quote).setScale(2, RoundingMode.DOWN).toPlainString()}"
        val res = JSONObject(signed("POST", "$base/api/v3/order", q))
        val exec = BigDecimal(res.getString("executedQty"))
        val spent = res.getString("cummulativeQuoteQty").toDouble()
        val baseAsset = symbol.removeSuffix("USDT")
        var fee = BigDecimal.ZERO
        res.optJSONArray("fills")?.let { f ->
            for (i in 0 until f.length()) {
                val x = f.getJSONObject(i)
                if (x.getString("commissionAsset") == baseAsset) fee += BigDecimal(x.getString("commission"))
            }
        }
        val avg = if (exec.signum() > 0) spent / exec.toDouble() else 0.0
        return Fill(exec - fee, spent, avg)
    }

    /** Market sell of [qty] base coin. quote = USDT received after fees taken in USDT. */
    fun sellFill(symbol: String, qty: BigDecimal): Fill {
        val q = "symbol=$symbol&side=SELL&type=MARKET&quantity=${qty.toPlainString()}"
        val res = JSONObject(signed("POST", "$base/api/v3/order", q))
        val exec = BigDecimal(res.getString("executedQty"))
        var got = res.getString("cummulativeQuoteQty").toDouble()
        res.optJSONArray("fills")?.let { f ->
            for (i in 0 until f.length()) {
                val x = f.getJSONObject(i)
                if (x.getString("commissionAsset") == "USDT") got -= x.getString("commission").toDouble()
            }
        }
        val avg = if (exec.signum() > 0) got / exec.toDouble() else 0.0
        return Fill(exec, got, avg)
    }

    /** Market buy spending [quote] of the quote asset. Returns coin quantity received. */
    fun marketBuy(symbol: String, quote: Double): BigDecimal = buyFill(symbol, quote).qty

    fun marketSell(symbol: String, qty: BigDecimal): BigDecimal = BigDecimal(sellFill(symbol, qty).quote)

    private fun get(url: String, auth: Boolean): String {
        val b = Request.Builder().url(url)
        if (auth) b.header("X-MBX-APIKEY", apiKey)
        http.newCall(b.build()).execute().use { r ->
            val s = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code}: $s")
            return s
        }
    }

    private fun signed(method: String, url: String, query: String): String {
        val full = "$query&recvWindow=5000&timestamp=${System.currentTimeMillis()}"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        val sig = mac.doFinal(full.toByteArray()).joinToString("") { "%02x".format(it) }
        val b = Request.Builder().url("$url?$full&signature=$sig").header("X-MBX-APIKEY", apiKey)
        if (method == "POST") b.post(okhttp3.RequestBody.create(null, ByteArray(0))) else b.get()
        http.newCall(b.build()).execute().use { r ->
            val s = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code}: $s")
            return s
        }
    }
}
