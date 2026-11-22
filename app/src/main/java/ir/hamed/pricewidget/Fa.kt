package ir.hamed.pricewidget

import java.util.Calendar
import java.util.Locale

object Fa {
    private val digits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')

    fun d(s: String): String = buildString {
        for (c in s) append(if (c in '0'..'9') digits[c - '0'] else c)
    }

    fun money(n: Long): String =
        d(String.format(Locale.US, "%,d", n).replace(',', '٬'))

    val MONTHS = arrayOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
    )

    val GMONTHS = arrayOf(
        "ژانویه", "فوریه", "مارس", "آوریل", "مه", "ژوئن",
        "ژوئیه", "اوت", "سپتامبر", "اکتبر", "نوامبر", "دسامبر"
    )

    fun weekday(calDow: Int): String = when (calDow) {
        Calendar.SATURDAY -> "شنبه"
        Calendar.SUNDAY -> "یکشنبه"
        Calendar.MONDAY -> "دوشنبه"
        Calendar.TUESDAY -> "سه‌شنبه"
        Calendar.WEDNESDAY -> "چهارشنبه"
        Calendar.THURSDAY -> "پنجشنبه"
        else -> "جمعه"
    }

    /** تبدیل میلادی به شمسی؛ خروجی: سال، ماه، روز */
    fun toJalali(gy: Int, gm: Int, gd: Int): Triple<Int, Int, Int> {
        val gdm = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val gy2 = if (gm > 2) gy + 1 else gy
        var days = 355666 + (365 * gy) + ((gy2 + 3) / 4) - ((gy2 + 99) / 100) +
                ((gy2 + 399) / 400) + gd + gdm[gm - 1]
        var jy = -1595 + (33 * (days / 12053))
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }
        val jm: Int
        val jd: Int
        if (days < 186) {
            jm = 1 + days / 31
            jd = 1 + days % 31
        } else {
            jm = 7 + (days - 186) / 30
            jd = 1 + (days - 186) % 30
        }
        return Triple(jy, jm, jd)
    }

    fun weatherEmoji(code: Int, day: Boolean = true): String = when (code) {
        0 -> if (day) "☀️" else "🌙"
        1 -> if (day) "🌤️" else "🌙"
        2 -> if (day) "⛅" else "☁️"
        3 -> "☁️"
        45, 48 -> "🌫️"
        in 51..57 -> "🌦️"
        in 61..67, in 80..82 -> "🌧️"
        in 71..77, 85, 86 -> "❄️"
        in 95..99 -> "⛈️"
        else -> "🌡️"
    }

    fun weatherText(code: Int): String = when (code) {
        0 -> "صاف"
        1 -> "عمدتاً صاف"
        2 -> "کمی ابری"
        3 -> "ابری"
        45, 48 -> "مه‌آلود"
        in 51..57 -> "نم‌نم باران"
        in 61..65 -> "بارانی"
        66, 67 -> "باران یخ‌زده"
        in 71..77 -> "برفی"
        in 80..82 -> "رگبار"
        85, 86 -> "برف شدید"
        in 95..99 -> "رعدوبرق"
        else -> "—"
    }
}
