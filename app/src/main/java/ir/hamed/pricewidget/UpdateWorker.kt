package ir.hamed.pricewidget

import android.content.Context
import android.content.SharedPreferences
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import kotlin.math.abs

class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences("data", Context.MODE_PRIVATE)
        val weatherOk = updateWeather(prefs)
        val pricesOk = updatePrices(prefs)
        PriceWidgetProvider.updateAll(applicationContext)
        if (pricesOk || weatherOk) Result.success() else Result.retry()
    }

    // ------------------------------------------------------------------ قیمت‌ها
    private fun updatePrices(prefs: SharedPreferences): Boolean {
        val keys = listOf("usd", "usdt", "gold18")
        val value = HashMap<String, Long>()
        val approx = HashSet<String>()
        val pctTgju = HashMap<String, Double>()

        // ۱) tgju
        for (u in Config.TGJU_URLS) {
            try {
                val root = JSONObject(httpGet(u))
                for ((k, tk) in listOf(
                    "usd" to Config.KEY_USD,
                    "usdt" to Config.KEY_USDT,
                    "gold18" to Config.KEY_GOLD18,
                )) {
                    val item = findKey(root, tk)
                    val t = rialToToman(item)
                    if (t > 0) {
                        value[k] = t
                        tgjuPct(item)?.let { pctTgju[k] = it }
                    }
                }
                if (value.isNotEmpty()) break
            } catch (_: Exception) {
            }
        }

        // ۲) والکس، فقط برای چیزهایی که tgju نداد
        if (value.size < keys.size) {
            try {
                val root = JSONObject(httpGet(Config.WALLEX_URL))
                val usdt = wallexToman(root, Config.WALLEX_USDT)
                val xaut = wallexToman(root, Config.WALLEX_XAUT)
                if (usdt > 0) {
                    if (!value.containsKey("usdt")) value["usdt"] = usdt
                    if (!value.containsKey("usd")) { value["usd"] = usdt; approx.add("usd") }
                }
                if (xaut > 0 && !value.containsKey("gold18")) {
                    value["gold18"] = Math.round(xaut / 31.1035 * 0.75)
                    approx.add("gold18")
                }
            } catch (_: Exception) {
            }
        }

        if (value.isEmpty()) return false

        // ۳) مبنای تغییر قیمت: اولین قیمت هر روز
        val cal = Calendar.getInstance()
        val dayKey = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)}"
        val newDay = prefs.getString("base_day", "") != dayKey
        val ed = prefs.edit()
        if (newDay) ed.putString("base_day", dayKey)
        for (k in keys) {
            val v = value[k]
            if (v != null && (newDay || prefs.getLong("base_$k", 0L) == 0L)) ed.putLong("base_$k", v)
        }
        ed.apply()

        val old = runCatching { JSONObject(prefs.getString("prices", "") ?: "") }
            .getOrDefault(JSONObject())
        val oldApprox = old.optJSONArray("approx")
        val oldPct = old.optJSONObject("pct")

        val out = JSONObject()
        val outApprox = JSONArray()
        val outPct = JSONObject()
        for (k in keys) {
            val v = value[k]
            if (v != null) {
                out.put(k, v)
                if (approx.contains(k)) outApprox.put(k)
                val b = prefs.getLong("base_$k", 0L)
                val pc: Double? = pctTgju[k] ?: if (b > 0) (v - b) * 100.0 / b else null
                if (pc != null) outPct.put(k, pc)
            } else {
                out.put(k, old.optLong(k, 0L))
                if (oldApprox != null) {
                    for (i in 0 until oldApprox.length()) if (oldApprox.optString(i) == k) outApprox.put(k)
                }
                if (oldPct != null && oldPct.has(k)) outPct.put(k, oldPct.optDouble(k))
            }
        }
        out.put("approx", outApprox)
        out.put("pct", outPct)
        prefs.edit()
            .putString("prices", out.toString())
            .putLong("ts", System.currentTimeMillis())
            .apply()
        return true
    }

    // ------------------------------------------------------------------ آب‌وهوا
    private fun updateWeather(prefs: SharedPreferences): Boolean {
        return try {
            val lat = Config.CITIES.joinToString(",") { it.lat.toString() }
            val lon = Config.CITIES.joinToString(",") { it.lon.toString() }
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code,is_day&timezone=auto"
            val s = httpGet(url)
            JSONArray(s)
            prefs.edit().putString("weather", s).apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------ ابزارها
    private fun findKey(o: Any?, key: String): JSONObject? {
        when (o) {
            is JSONObject -> {
                o.optJSONObject(key)?.let { return it }
                val it = o.keys()
                while (it.hasNext()) {
                    findKey(o.opt(it.next()), key)?.let { r -> return r }
                }
            }
            is JSONArray -> for (i in 0 until o.length()) {
                findKey(o.opt(i), key)?.let { r -> return r }
            }
        }
        return null
    }

    private fun rialToToman(item: JSONObject?): Long {
        val p = item?.opt("p")?.toString()?.replace(",", "")?.trim()?.toDoubleOrNull() ?: return 0L
        return Math.round(p / 10.0)
    }

    /** درصد تغییر روزانه‌ی خود tgju (فیلد dp و جهت dt)، اگر موجود بود */
    private fun tgjuPct(item: JSONObject?): Double? {
        if (item == null) return null
        val dp = item.opt("dp")?.toString()?.replace(",", "")?.trim()?.toDoubleOrNull() ?: return null
        return when (item.optString("dt")) {
            "low" -> -abs(dp)
            "high" -> abs(dp)
            else -> null
        }
    }

    private fun wallexToman(root: JSONObject, symbol: String): Long {
        val p = findKey(root, symbol)?.optJSONObject("stats")?.opt("lastPrice")
            ?.toString()?.trim()?.toDoubleOrNull() ?: return 0L
        return Math.round(p)
    }

    private fun httpGet(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 12000
        c.readTimeout = 12000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) PriceWidget/1.0")
        try {
            if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
