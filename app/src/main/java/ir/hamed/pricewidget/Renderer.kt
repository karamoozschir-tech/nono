package ir.hamed.pricewidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** فونت اختیاری: اگر فایل وزیرمتن داخل assets باشد استفاده می‌شود */
private object Fonts {
    private val cache = HashMap<String, Typeface>()

    @Synchronized
    fun get(ctx: Context, file: String, fallback: Typeface): Typeface =
        cache.getOrPut(file) {
            try {
                Typeface.createFromAsset(ctx.assets, file)
            } catch (_: Exception) {
                fallback
            }
        }
}

/** کل ظاهر ویجت روی یک بوم (Canvas) کشیده می‌شود */
class Renderer(private val W: Float, private val H: Float, private val d: Data) {

    class Wx(val temp: Int?, val code: Int, val isDay: Boolean?)

    class Data(ctx: Context) {
        val cal: Calendar = Calendar.getInstance()
        val prices: JSONObject
        val weather: List<Wx>
        val bold: Typeface =
            Fonts.get(ctx, "Vazirmatn-Bold.ttf", Typeface.create("sans-serif", Typeface.BOLD))
        val normal: Typeface =
            Fonts.get(ctx, "Vazirmatn-Regular.ttf", Typeface.create("sans-serif", Typeface.NORMAL))

        init {
            val p = ctx.getSharedPreferences("data", Context.MODE_PRIVATE)
            prices = runCatching { JSONObject(p.getString("prices", "") ?: "") }
                .getOrDefault(JSONObject())
            val arr = runCatching { JSONArray(p.getString("weather", "") ?: "") }.getOrNull()
            weather = Config.CITIES.indices.map { i ->
                val cur = arr?.optJSONObject(i)?.optJSONObject("current")
                if (cur == null) Wx(null, -1, null) else Wx(
                    if (cur.has("temperature_2m")) cur.optDouble("temperature_2m").roundToInt() else null,
                    cur.optInt("weather_code", -1),
                    if (cur.has("is_day")) cur.optInt("is_day") == 1 else null
                )
            }
        }

        /** روز یا شب بودن بر اساس شاهدشهر (اگر داده نبود، از ساعت گوشی) */
        val isDay: Boolean
            get() = weather.firstOrNull()?.isDay ?: (cal.get(Calendar.HOUR_OF_DAY) in 6..17)
    }

    companion object {
        fun render(ctx: Context, wDp: Int, hDp: Int): Bitmap {
            val dm = ctx.resources.displayMetrics
            var w = wDp * dm.density
            var h = hDp * dm.density
            val maxPix = 1_300_000f
            if (w * h > maxPix) {
                val s = sqrt(maxPix / (w * h))
                w *= s; h *= s
            }
            val bmp = Bitmap.createBitmap(
                w.toInt().coerceAtLeast(240), h.toInt().coerceAtLeast(240), Bitmap.Config.ARGB_8888
            )
            Renderer(bmp.width.toFloat(), bmp.height.toFloat(), Data(ctx)).draw(Canvas(bmp))
            return bmp
        }
    }

    // ---------------------------------------------------------------- رنگ‌ها
    private val GOLD = 0xFFE8C766.toInt()
    private val TURQ = 0xFF3CCFC0.toInt()
    private val WHITE = 0xFFFFFFFF.toInt()
    private val SOFT = 0xFFB8C7F0.toInt()
    private val GREEN = 0xFF3DDC84.toInt()
    private val RED = 0xFFFF5A5F.toInt()
    private val ROSE = 0xFFEF5B7B.toInt()
    private val CARD = 0xB00A1744.toInt()
    private val INK = 0xFF0A1740.toInt()

    private val u = W / 100f
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)

    private enum class Sky { CLEAR, PARTLY, CLOUDY, FOG, RAIN, SNOW, STORM }

    private fun skyOf(code: Int): Sky = when (code) {
        0, 1 -> Sky.CLEAR
        2 -> Sky.PARTLY
        3 -> Sky.CLOUDY
        45, 48 -> Sky.FOG
        in 51..67, in 80..82 -> Sky.RAIN
        in 71..77, 85, 86 -> Sky.SNOW
        in 95..99 -> Sky.STORM
        else -> Sky.CLEAR
    }

    private fun skyColors(s: Sky, day: Boolean): Pair<Int, Int> = when (s) {
        Sky.CLEAR, Sky.PARTLY ->
            if (day) Pair(0xFF3E9BE0.toInt(), 0xFFBFE4F8.toInt()) else Pair(0xFF050A24.toInt(), 0xFF1B2B66.toInt())
        Sky.CLOUDY ->
            if (day) Pair(0xFF7B93AB.toInt(), 0xFFC7D2DC.toInt()) else Pair(0xFF121A33.toInt(), 0xFF2D3757.toInt())
        Sky.FOG ->
            if (day) Pair(0xFF9FB1BF.toInt(), 0xFFDCE4EA.toInt()) else Pair(0xFF222B40.toInt(), 0xFF4A546C.toInt())
        Sky.RAIN ->
            if (day) Pair(0xFF506479.toInt(), 0xFF9CB0C0.toInt()) else Pair(0xFF0C1226.toInt(), 0xFF283250.toInt())
        Sky.STORM ->
            if (day) Pair(0xFF3B4A5C.toInt(), 0xFF7D8DA0.toInt()) else Pair(0xFF070B1A.toInt(), 0xFF1D2540.toInt())
        Sky.SNOW ->
            if (day) Pair(0xFFA7B8C9.toInt(), 0xFFEAF0F5.toInt()) else Pair(0xFF1A2442.toInt(), 0xFF4B5A7D.toInt())
    }

    // ---------------------------------------------------------------- ابزارها
    private fun face(bold: Boolean, emoji: Boolean): Typeface =
        if (emoji) Typeface.DEFAULT else if (bold) d.bold else d.normal

    private fun txt(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
        bold: Boolean = false, align: Paint.Align = Paint.Align.CENTER,
        maxW: Float = 0f, shadow: Boolean = false, emoji: Boolean = false
    ) {
        tp.typeface = face(bold, emoji)
        tp.color = color
        tp.textAlign = align
        tp.textSize = size
        if (maxW > 0f) {
            while (tp.measureText(s) > maxW && tp.textSize > 6f) tp.textSize -= 0.5f
        }
        if (shadow) tp.setShadowLayer(.6f * u, 0f, .25f * u, 0xAA000000.toInt())
        c.drawText(s, x, y, tp)
        if (shadow) tp.clearShadowLayer()
    }

    private fun mw(s: String, size: Float, bold: Boolean = false): Float {
        tp.typeface = face(bold, false)
        tp.textSize = size
        return tp.measureText(s)
    }

    private fun star8(c: Canvas, cx: Float, cy: Float, r: Float, p: Paint) {
        val path = Path()
        for (k in 0 until 16) {
            val a = Math.PI * k / 8.0 - Math.PI / 2
            val rr = if (k % 2 == 0) r else r * 0.62f
            val x = cx + (rr * cos(a)).toFloat()
            val y = cy + (rr * sin(a)).toFloat()
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        c.drawPath(path, p)
    }

    private fun diamond(c: Canvas, x: Float, y: Float, s: Float, color: Int) {
        val p = Path()
        p.moveTo(x, y - s); p.lineTo(x + s * .7f, y); p.lineTo(x, y + s); p.lineTo(x - s * .7f, y)
        p.close()
        fill.color = color
        c.drawPath(p, fill)
    }

    private fun heart(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        val p = Path()
        p.moveTo(cx, cy + s * .9f)
        p.cubicTo(cx - s * 1.3f, cy, cx - s * .9f, cy - s * .9f, cx, cy - s * .35f)
        p.cubicTo(cx + s * .9f, cy - s * .9f, cx + s * 1.3f, cy, cx, cy + s * .9f)
        p.close()
        fill.color = color
        c.drawPath(p, fill)
    }

    private fun card(c: Canvas, r: RectF) {
        val rad = 2.2f * u
        fill.color = CARD
        c.drawRoundRect(r, rad, rad, fill)
        line.color = GOLD; line.strokeWidth = .32f * u
        c.drawRoundRect(r, rad, rad, line)
        val s = .85f * u
        diamond(c, r.left + .6f * u, r.top + .6f * u, s, GOLD)
        diamond(c, r.right - .6f * u, r.top + .6f * u, s, GOLD)
        diamond(c, r.left + .6f * u, r.bottom - .6f * u, s, GOLD)
        diamond(c, r.right - .6f * u, r.bottom - .6f * u, s, GOLD)
    }

    private fun fmtTemp(t: Int?): String {
        if (t == null) return "—"
        return (if (t < 0) "\u200E−" else "") + Fa.d(abs(t).toString()) + "°"
    }

    private fun greeting(): String {
        val h = d.cal.get(Calendar.HOUR_OF_DAY)
        return when {
            h in 4..10 -> "صبح بخیر"
            h in 11..14 -> "ظهر بخیر"
            h in 15..17 -> "عصر بخیر"
            else -> "شب بخیر"
        }
    }

    // ---------------------------------------------------------------- ترکیب کلی
    fun draw(c: Canvas) {
        background(c)
        ribbon(c)
        val pad = 3.4f * u
        dates(c, RectF(pad, .192f * H, W - pad, .282f * H))
        scene(c, RectF(pad, .292f * H, W - pad, .470f * H))
        prices(c, RectF(pad, .480f * H, W - pad, .665f * H))
        weather(c, RectF(pad, .675f * H, W - pad, .835f * H))
        poem(c, RectF(pad, .845f * H, W - pad, .968f * H))
    }

    // ---------------------------------------------------------------- پس‌زمینه و قاب
    private fun background(c: Canvas) {
        val rr = RectF(0f, 0f, W, H)
        fill.shader = LinearGradient(
            0f, 0f, W * .6f, H,
            intArrayOf(0xFF081433.toInt(), 0xFF132C72.toInt(), 0xFF081433.toInt()),
            floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRoundRect(rr, 7 * u, 7 * u, fill)
        fill.shader = null

        c.save()
        c.clipPath(Path().apply { addRoundRect(rr, 7 * u, 7 * u, Path.Direction.CW) })
        fill.shader = RadialGradient(
            W / 2, .10f * H, W * .55f, 0x38E8C766, 0x00E8C766, Shader.TileMode.CLAMP
        )
        c.drawCircle(W / 2, .10f * H, W * .55f, fill)
        fill.shader = null
        line.color = 0x2AE8C766; line.strokeWidth = .22f * u
        val s = 13f * u
        var row = 0
        var y = s / 2
        while (y < H + s) {
            var x = if (row % 2 == 0) s / 2 else s
            while (x < W + s) {
                star8(c, x, y, s * .38f, line)
                x += s
            }
            y += s * .9f
            row++
        }
        c.restore()

        // قاب طلایی دوگانه
        line.color = GOLD; line.strokeWidth = .7f * u
        c.drawRoundRect(RectF(.9f * u, .9f * u, W - .9f * u, H - .9f * u), 6.2f * u, 6.2f * u, line)
        line.color = 0x66E8C766; line.strokeWidth = .25f * u
        c.drawRoundRect(RectF(2.1f * u, 2.1f * u, W - 2.1f * u, H - 2.1f * u), 5.2f * u, 5.2f * u, line)
        fill.color = GOLD
        star8(c, 4f * u, 4f * u, 1.7f * u, fill)
        star8(c, W - 4f * u, 4f * u, 1.7f * u, fill)
        star8(c, 4f * u, H - 4f * u, 1.7f * u, fill)
        star8(c, W - 4f * u, H - 4f * u, 1.7f * u, fill)

        // تزئین دو طرف ساعت
        val y0 = .10f * H
        line.color = 0x99E8C766.toInt(); line.strokeWidth = .3f * u
        c.drawLine(8f * u, y0, 17f * u, y0, line)
        c.drawLine(W - 17f * u, y0, W - 8f * u, y0, line)
        fill.color = GOLD
        star8(c, 18.8f * u, y0, 1.6f * u, fill)
        star8(c, W - 18.8f * u, y0, 1.6f * u, fill)

        // خط پیشرفت روز: هرچه روز جلوتر می‌رود، طلا تا جلوتر می‌آید
        val ys = .182f * H
        val x0 = 8f * u
        val x1 = W - 8f * u
        val prog = ((d.cal.get(Calendar.HOUR_OF_DAY) + d.cal.get(Calendar.MINUTE) / 60f) / 24f).coerceIn(0f, 1f)
        val xm = x0 + (x1 - x0) * prog
        line.color = 0x55E8C766; line.strokeWidth = .3f * u
        c.drawLine(x0, ys, x1, ys, line)
        line.color = GOLD; line.strokeWidth = .5f * u
        c.drawLine(x0, ys, xm, ys, line)
        fill.shader = RadialGradient(xm, ys, 4f * u, 0x88E8C766.toInt(), 0x00E8C766, Shader.TileMode.CLAMP)
        c.drawCircle(xm, ys, 4f * u, fill)
        fill.shader = null
        fill.color = GOLD
        star8(c, xm, ys, 1.8f * u, fill)
    }

    // ---------------------------------------------------------------- نوار «جاوید شاه»
    private fun ribbon(c: Canvas) {
        val text = "جاوید شاه"
        val ph = .034f * H
        val fs = ph * .62f
        val tw = mw(text, fs, true)
        val pw = tw + 9f * u
        val cx = W / 2
        val top = .022f * H
        val rc = RectF(cx - pw / 2, top, cx + pw / 2, top + ph)
        fill.shader = LinearGradient(
            rc.left, rc.top, rc.left, rc.bottom,
            intArrayOf(0xFFF6E3A1.toInt(), 0xFFE8C766.toInt(), 0xFFB8932F.toInt()), null, Shader.TileMode.CLAMP
        )
        c.drawRoundRect(rc, ph / 2, ph / 2, fill)
        fill.shader = null
        line.color = 0xFFFFF3C4.toInt(); line.strokeWidth = .2f * u
        c.drawRoundRect(rc, ph / 2, ph / 2, line)
        txt(c, text, cx, top + ph * .71f, fs, INK, true)
        val cy = top + ph / 2
        line.color = 0x99E8C766.toInt(); line.strokeWidth = .3f * u
        c.drawLine(rc.left - 12f * u, cy, rc.left - 4.6f * u, cy, line)
        c.drawLine(rc.right + 4.6f * u, cy, rc.right + 12f * u, cy, line)
        fill.color = GOLD
        star8(c, rc.left - 3f * u, cy, 1.5f * u, fill)
        star8(c, rc.right + 3f * u, cy, 1.5f * u, fill)
    }

    // ---------------------------------------------------------------- سه تاریخ
    private fun dates(c: Canvas, r: RectF) {
        val cal = d.cal
        val gy = cal.get(Calendar.YEAR)
        val gm = cal.get(Calendar.MONTH)
        val gd = cal.get(Calendar.DAY_OF_MONTH)
        val (jy, jm, jd) = Fa.toJalali(gy, gm + 1, gd)
        val items = listOf(
            Triple("شمسی", "$jd ${Fa.MONTHS[jm - 1]}", "$jy"),
            Triple("شاهنشاهی", "$jd ${Fa.MONTHS[jm - 1]}", "${jy + 1180}"),
            Triple("میلادی", "$gd ${Fa.GMONTHS[gm]}", "$gy"),
        )
        val gap = 1.6f * u
        val cw = (r.width() - 2 * gap) / 3
        items.forEachIndexed { i, (title, l1, l2) ->
            val right = r.right - i * (cw + gap)
            val rc = RectF(right - cw, r.top, right, r.bottom)
            card(c, rc)
            val h = rc.height()
            val cx = rc.centerX()
            txt(c, title, cx, rc.top + .27f * h, .20f * h, TURQ, true)
            txt(c, Fa.d(l1), cx, rc.top + .59f * h, .31f * h, WHITE, true, maxW = cw * .94f)
            txt(c, Fa.d(l2), cx, rc.top + .88f * h, .25f * h, GOLD, true)
        }
    }

    // ---------------------------------------------------------------- تصویر وسط (هوای شاهدشهر + روز و شب)
    private fun cloud(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        fill.color = color
        c.drawCircle(cx - s * .45f, cy + s * .05f, s * .28f, fill)
        c.drawCircle(cx - s * .10f, cy - s * .12f, s * .36f, fill)
        c.drawCircle(cx + s * .28f, cy, s * .30f, fill)
        c.drawCircle(cx + s * .55f, cy + s * .10f, s * .20f, fill)
        c.drawRoundRect(RectF(cx - s * .7f, cy + s * .05f, cx + s * .75f, cy + s * .30f), s * .12f, s * .12f, fill)
    }

    private fun sun(c: Canvas, cx: Float, cy: Float, rad: Float) {
        fill.shader = RadialGradient(cx, cy, rad * 2.6f, 0x88FFE08A.toInt(), 0x00FFE08A, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rad * 2.6f, fill)
        fill.shader = null
        line.color = 0xCCFFD54A.toInt(); line.strokeWidth = .45f * u
        for (k in 0 until 12) {
            val a = Math.PI * 2 * k / 12
            c.drawLine(
                cx + (rad * 1.25f * cos(a)).toFloat(), cy + (rad * 1.25f * sin(a)).toFloat(),
                cx + (rad * 1.65f * cos(a)).toFloat(), cy + (rad * 1.65f * sin(a)).toFloat(), line
            )
        }
        fill.color = 0xFFFFD54A.toInt()
        c.drawCircle(cx, cy, rad, fill)
    }

    /** ماه با فاز واقعی امشب */
    private fun moon(c: Canvas, cx: Float, cy: Float, rad: Float) {
        fill.shader = RadialGradient(cx, cy, rad * 2.4f, 0x55F5F0D0, 0x00F5F0D0, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rad * 2.4f, fill)
        fill.shader = null
        val synodic = 29.530588853
        val age = (((d.cal.timeInMillis - 947182440000L) / 86400000.0) % synodic + synodic) % synodic
        val k = cos(2 * Math.PI * age / synodic).toFloat()
        val waxing = age < synodic / 2
        fill.color = 0x40FFFFFF
        c.drawCircle(cx, cy, rad, fill)
        val lit = Path().apply { addCircle(cx, cy, rad, Path.Direction.CW) }
        val half = Path().apply {
            if (waxing) addRect(cx, cy - rad - 1f, cx + rad + 1f, cy + rad + 1f, Path.Direction.CW)
            else addRect(cx - rad - 1f, cy - rad - 1f, cx, cy + rad + 1f, Path.Direction.CW)
        }
        lit.op(half, Path.Op.INTERSECT)
        val xr = rad * abs(k)
        val ell = Path().apply { addOval(RectF(cx - xr, cy - rad, cx + xr, cy + rad), Path.Direction.CW) }
        if (k > 0) lit.op(ell, Path.Op.DIFFERENCE) else lit.op(ell, Path.Op.UNION)
        fill.color = 0xFFF8F1C9.toInt()
        c.drawPath(lit, fill)
    }

    private fun cypress(c: Canvas, cx: Float, baseY: Float, hgt: Float, color: Int) {
        val wid = hgt * .17f
        val p = Path()
        p.moveTo(cx, baseY - hgt)
        p.quadTo(cx + wid * 1.6f, baseY - hgt * .42f, cx, baseY)
        p.quadTo(cx - wid * 1.6f, baseY - hgt * .42f, cx, baseY - hgt)
        p.close()
        fill.color = color
        c.drawPath(p, fill)
    }

    private fun bird(c: Canvas, x: Float, y: Float, s: Float) {
        val p = Path()
        p.moveTo(x - s, y)
        p.quadTo(x - s / 2, y - s * .7f, x, y)
        p.quadTo(x + s / 2, y - s * .7f, x + s, y)
        line.color = 0xAA24344A.toInt(); line.strokeWidth = .32f * u
        c.drawPath(p, line)
    }

    private fun pill(c: Canvas, text: String, x: Float, yTop: Float, fs: Float, alignRight: Boolean) {
        val pw = mw(text, fs, true) + 5f * u
        val ph = fs * 1.9f
        val left = if (alignRight) x - pw else x - pw / 2
        fill.color = 0x9906122E.toInt()
        c.drawRoundRect(RectF(left, yTop, left + pw, yTop + ph), ph / 2, ph / 2, fill)
        txt(c, text, left + pw / 2, yTop + ph * .68f, fs, WHITE, true)
    }

    private fun scene(c: Canvas, r: RectF) {
        val wx0 = d.weather[0]
        val sky = skyOf(wx0.code)
        val day = d.isDay
        val w = r.width()
        val h = r.height()
        val rnd = Random(11)

        c.save()
        c.clipPath(Path().apply { addRoundRect(r, 2.6f * u, 2.6f * u, Path.Direction.CW) })

        val (top, bot) = skyColors(sky, day)
        fill.shader = LinearGradient(r.left, r.top, r.left, r.bottom, top, bot, Shader.TileMode.CLAMP)
        c.drawRect(r, fill)
        fill.shader = null

        val clearish = sky == Sky.CLEAR || sky == Sky.PARTLY
        if (!day && clearish) {
            for (i in 0 until 30) {
                val sx = r.left + rnd.nextFloat() * w
                val sy = r.top + rnd.nextFloat() * h * .6f
                fill.color = ((0x55 + rnd.nextInt(0xAA)) shl 24) or 0xFFFFFF
                c.drawCircle(sx, sy, (.25f + rnd.nextFloat() * .4f) * u, fill)
            }
        }
        if (clearish) {
            val cx = r.left + w * .22f
            val cy = r.top + h * .36f
            if (day) sun(c, cx, cy, h * .14f) else moon(c, cx, cy, h * .13f)
        }
        val cloudColor = when (sky) {
            Sky.RAIN, Sky.STORM -> if (day) 0xFF8796A5.toInt() else 0xFF39425A.toInt()
            Sky.SNOW -> if (day) 0xFFDDE5EC.toInt() else 0xFF6A7690.toInt()
            Sky.FOG -> 0xCCDDDDDD.toInt()
            else -> if (day) 0xEEFFFFFF.toInt() else 0xFF8A93B5.toInt()
        }
        when (sky) {
            Sky.PARTLY -> {
                cloud(c, r.left + w * .58f, r.top + h * .36f, h * .30f, cloudColor)
                cloud(c, r.left + w * .84f, r.top + h * .24f, h * .20f, cloudColor)
            }
            Sky.CLOUDY, Sky.RAIN, Sky.SNOW, Sky.STORM, Sky.FOG -> {
                cloud(c, r.left + w * .22f, r.top + h * .30f, h * .30f, cloudColor)
                cloud(c, r.left + w * .55f, r.top + h * .22f, h * .36f, cloudColor)
                cloud(c, r.left + w * .86f, r.top + h * .32f, h * .28f, cloudColor)
            }
            else -> {}
        }
        if (sky == Sky.CLEAR && day) {
            bird(c, r.left + w * .60f, r.top + h * .22f, 1.5f * u)
            bird(c, r.left + w * .67f, r.top + h * .30f, 1.1f * u)
            bird(c, r.left + w * .54f, r.top + h * .32f, 1.2f * u)
        }
        if (sky == Sky.RAIN || sky == Sky.STORM) {
            line.color = 0xAACDE6FF.toInt(); line.strokeWidth = .28f * u
            for (i in 0 until 46) {
                val x = r.left + rnd.nextFloat() * w
                val y = r.top + h * .25f + rnd.nextFloat() * h * .6f
                c.drawLine(x, y, x - .9f * u, y + 3.2f * u, line)
            }
        }
        if (sky == Sky.STORM) {
            val bx = r.left + w * .55f
            val by = r.top + h * .42f
            val p = Path()
            p.moveTo(bx, by); p.lineTo(bx - 2f * u, by + 5f * u); p.lineTo(bx + .5f * u, by + 5f * u)
            p.lineTo(bx - 1.5f * u, by + 10.5f * u); p.lineTo(bx + 3f * u, by + 3.5f * u)
            p.lineTo(bx + .5f * u, by + 3.5f * u); p.lineTo(bx + 2.2f * u, by)
            p.close()
            fill.color = 0xFFFFE066.toInt()
            c.drawPath(p, fill)
        }
        if (sky == Sky.SNOW) {
            fill.color = 0xE6FFFFFF.toInt()
            for (i in 0 until 40) {
                val x = r.left + rnd.nextFloat() * w
                val y = r.top + h * .2f + rnd.nextFloat() * h * .7f
                c.drawCircle(x, y, (.35f + rnd.nextFloat() * .45f) * u, fill)
            }
        }
        if (sky == Sky.FOG) {
            fill.color = 0x55FFFFFF
            for (i in 0 until 3) {
                val y = r.top + h * (.45f + i * .14f)
                c.drawRoundRect(RectF(r.left - 2 * u, y, r.right + 2 * u, y + h * .07f), 3f * u, 3f * u, fill)
            }
        }
        // تپه‌ها و سروها
        val snow = sky == Sky.SNOW
        val hill1 = if (snow) 0xFFC9D6E2.toInt() else if (day) 0xFF3A8F5C.toInt() else 0xFF123B3C.toInt()
        val hill2 = if (snow) 0xFFEAF1F7.toInt() else if (day) 0xFF2D7A4C.toInt() else 0xFF0B2A2D.toInt()
        val tree = if (snow) 0xFF2E5B4A.toInt() else if (day) 0xFF1F5E3A.toInt() else 0xFF07201F.toInt()
        val p1 = Path()
        p1.moveTo(r.left, r.bottom); p1.lineTo(r.left, r.top + h * .70f)
        p1.cubicTo(r.left + w * .30f, r.top + h * .55f, r.left + w * .55f, r.top + h * .80f, r.right, r.top + h * .62f)
        p1.lineTo(r.right, r.bottom); p1.close()
        fill.color = hill1; c.drawPath(p1, fill)
        val p2 = Path()
        p2.moveTo(r.left, r.bottom); p2.lineTo(r.left, r.top + h * .84f)
        p2.cubicTo(r.left + w * .35f, r.top + h * .74f, r.left + w * .70f, r.top + h * .92f, r.right, r.top + h * .80f)
        p2.lineTo(r.right, r.bottom); p2.close()
        fill.color = hill2; c.drawPath(p2, fill)
        val base = r.top + h * .90f
        cypress(c, r.left + w * .91f, base, h * .46f, tree)
        cypress(c, r.left + w * .84f, base, h * .33f, tree)
        cypress(c, r.left + w * .07f, base, h * .36f, tree)
        cypress(c, r.left + w * .14f, base, h * .25f, tree)

        // کرم‌های شب‌تاب در شب صاف
        if (!day && clearish) {
            for (i in 0 until 8) {
                val fx = r.left + w * (.2f + rnd.nextFloat() * .6f)
                val fy = r.top + h * (.55f + rnd.nextFloat() * .3f)
                fill.shader = RadialGradient(fx, fy, 1.8f * u, 0x99FFF59D.toInt(), 0x00FFF59D, Shader.TileMode.CLAMP)
                c.drawCircle(fx, fy, 1.8f * u, fill)
                fill.shader = null
                fill.color = 0xFFFFF59D.toInt()
                c.drawCircle(fx, fy, .3f * u, fill)
            }
        }
        c.restore()

        line.color = GOLD; line.strokeWidth = .45f * u
        c.drawRoundRect(r, 2.6f * u, 2.6f * u, line)
        diamond(c, r.left, r.top, 1.1f * u, GOLD)
        diamond(c, r.right, r.top, 1.1f * u, GOLD)
        diamond(c, r.left, r.bottom, 1.1f * u, GOLD)
        diamond(c, r.right, r.bottom, 1.1f * u, GOLD)

        // متن‌ها داخل قاب نیمه‌شفاف تا همیشه خوانا باشند
        val dow = Fa.weekday(d.cal.get(Calendar.DAY_OF_WEEK))
        pill(c, "$dow · ${greeting()}", r.right - 2.6f * u, r.top + .08f * h, .10f * h, true)
        val info = Config.CITIES[0].name + " · " + fmtTemp(wx0.temp) + " · " +
                (if (wx0.code >= 0) Fa.weatherText(wx0.code) else "—")
        val fs = .10f * h
        pill(c, info, r.centerX(), r.bottom - .07f * h - fs * 1.9f, fs, false)
    }

    // ---------------------------------------------------------------- قیمت‌ها (سه ردیف تمام‌عرض)
    private fun arrow(c: Canvas, cx: Float, cy: Float, s: Float, up: Boolean, color: Int) {
        val p = Path()
        if (up) {
            p.moveTo(cx, cy - s / 2); p.lineTo(cx + s / 2, cy + s / 2); p.lineTo(cx - s / 2, cy + s / 2)
        } else {
            p.moveTo(cx, cy + s / 2); p.lineTo(cx + s / 2, cy - s / 2); p.lineTo(cx - s / 2, cy - s / 2)
        }
        p.close()
        fill.color = color
        c.drawPath(p, fill)
    }

    private fun prices(c: Canvas, r: RectF) {
        val defs = listOf("usd" to "دلار", "usdt" to "تتر", "gold18" to "طلای ۱۸")
        val gv = .008f * H
        val rh = (r.height() - 2 * gv) / 3
        val approxArr = d.prices.optJSONArray("approx")
        val pctObj = d.prices.optJSONObject("pct")
        defs.forEachIndexed { i, (k, label) ->
            val top = r.top + i * (rh + gv)
            val rc = RectF(r.left, top, r.right, top + rh)
            card(c, rc)
            val h = rc.height()
            val rw = rc.width()
            val v = d.prices.optLong(k, 0L)
            val approx = approxArr != null && (0 until approxArr.length()).any { approxArr.optString(it) == k }

            // برچسب (راست)
            txt(c, label, rc.right - 3.2f * u, rc.top + .68f * h, .44f * h, GOLD, true,
                Paint.Align.RIGHT, maxW = rw * .27f)

            // عدد + «تومان» (وسط)
            val value = if (v > 0) (if (approx) "~" else "") + Fa.money(v) else "—"
            val zl = rc.left + rw * .27f
            val zr = rc.right - rw * .29f
            val maxTotal = zr - zl
            var vs = .52f * h
            val us = .27f * h
            val gap = 1.2f * u
            val uw = mw("تومان", us, false)
            var tw = mw(value, vs, true)
            if (tw + uw + gap > maxTotal) {
                vs *= maxTotal / (tw + uw + gap)
                tw = mw(value, vs, true)
            }
            val total = tw + uw + gap
            val vr = (zl + zr) / 2 + total / 2
            txt(c, value, vr, rc.top + .72f * h, vs, WHITE, true, Paint.Align.RIGHT)
            txt(c, "تومان", vr - tw - gap, rc.top + .72f * h, us, SOFT, false, Paint.Align.RIGHT)

            // تغییر قیمت (چپ)
            if (pctObj != null && pctObj.has(k)) {
                val pct = pctObj.optDouble(k)
                val flat = abs(pct) < 0.005
                val col = if (flat) SOFT else if (pct > 0) GREEN else RED
                fill.color = col
                c.drawRoundRect(
                    RectF(rc.left + .5f * u, rc.top + .2f * h, rc.left + 1.1f * u, rc.bottom - .2f * h),
                    .3f * u, .3f * u, fill
                )
                val ps = .36f * h
                val t = Fa.d(String.format(Locale.US, "%.2f", abs(pct)).replace('.', '٫')) + "٪"
                val a = .30f * h
                var x = rc.left + 3f * u
                if (!flat) {
                    arrow(c, x + a / 2, rc.top + .5f * h, a, pct > 0, col)
                    x += a + 1f * u
                }
                txt(c, t, x, rc.top + .66f * h, ps, col, true, Paint.Align.LEFT, maxW = rw * .22f)
            }
        }
    }

    // ---------------------------------------------------------------- آب‌وهوای چهار شهر
    private fun weather(c: Canvas, r: RectF) {
        val gap = 1.6f * u
        val gv = .008f * H
        val cw = (r.width() - gap) / 2
        val ch = (r.height() - gv) / 2
        for (i in 0 until 4) {
            val col = i % 2
            val row = i / 2
            val right = r.right - col * (cw + gap)
            val top = r.top + row * (ch + gv)
            val rc = RectF(right - cw, top, right, top + ch)
            card(c, rc)
            val wx = d.weather[i]
            val h = ch
            txt(c, Config.CITIES[i].name, rc.right - 2.6f * u, rc.top + .47f * h, .36f * h, WHITE,
                true, Paint.Align.RIGHT, maxW = cw * .50f)
            txt(c, if (wx.code >= 0) Fa.weatherText(wx.code) else "—", rc.right - 2.6f * u, rc.top + .85f * h,
                .25f * h, SOFT, false, Paint.Align.RIGHT, maxW = cw * .50f)
            txt(c, Fa.weatherEmoji(wx.code, wx.isDay ?: d.isDay), rc.left + 2.6f * u, rc.top + .73f * h,
                .56f * h, WHITE, false, Paint.Align.LEFT, emoji = true)
            txt(c, fmtTemp(wx.temp), rc.left + 2.6f * u + .78f * h, rc.top + .72f * h, .46f * h, GOLD,
                true, Paint.Align.LEFT, maxW = cw * .28f)
        }
    }

    // ---------------------------------------------------------------- بیت روز
    private fun poem(c: Canvas, r: RectF) {
        card(c, r)
        val p = Config.POEMS[d.cal.get(Calendar.DAY_OF_YEAR) % Config.POEMS.size]
        val h = r.height()
        val cx = r.centerX()
        heart(c, cx, r.top + .13f * h, .07f * h, ROSE)
        txt(c, p.l1, cx, r.top + .47f * h, .21f * h, WHITE, true, maxW = r.width() * .93f)
        txt(c, p.l2, cx, r.top + .73f * h, .21f * h, WHITE, true, maxW = r.width() * .93f)
        txt(c, "— ${p.poet}", cx, r.top + .94f * h, .13f * h, GOLD, true)
    }
}
