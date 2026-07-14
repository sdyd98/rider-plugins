package com.example.xlsx

import org.apache.poi.ss.usermodel.DataFormatter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The fast-path formatter must be BYTE-IDENTICAL to POI's [DataFormatter] for every value it
 * shortcuts (and it delegates everything else, so identity there is trivial — still spot-checked).
 */
class FastGeneralFormatterTest {

    private val poi = DataFormatter()
    private val fast = FastGeneralFormatter()

    private fun check(value: Double, formatIndex: Int, formatString: String) = assertEquals(
        poi.formatRawCellContents(value, formatIndex, formatString),
        fast.formatRawCellContents(value, formatIndex, formatString),
        "value=$value fmt=$formatString($formatIndex)",
    )

    @Test
    fun `general format matches POI across the fast-path range and its edges`() {
        val values = listOf(
            0.0, 1.0, -1.0, 5.0, 42.0, 999.0, -999.0,
            99_999_999_999.0, -99_999_999_999.0, // 11-digit fast-path edge
            100_000_000_000.0, -100_000_000_000.0, // 12 digits — must delegate (scientific)
            1e15, 1e-8, // far outside — delegate
            0.5, 123.45, -0.25, 1.0 / 3.0, 0.1 + 0.2, // fractional — delegate
            -0.0, // bit-exactness guard — delegate
            1234567.0, 86_400.0,
        )
        for (v in values) {
            check(v, 0, "General")
            check(v, 0, "@")
        }
    }

    @Test
    fun `mass equivalence sweep — 200k mixed values match POI exactly`() {
        var v = 0
        for (i in 0 until 200_000) {
            val value = when (i % 5) {
                0 -> i.toDouble() * 37 // integral
                1 -> i * 0.01 // 2-decimal money/rate style
                2 -> i * 31.415926535 // long fractions (over the digit budget → delegate)
                3 -> -i * 0.25 // negative fractions
                else -> i * 1e-7 // tiny values (exponent repr → delegate)
            }
            check(value, 0, "General")
            v++
        }
        assertEquals(200_000, v)
    }

    @Test
    fun `explicit formats always delegate to POI`() {
        check(123.456, 2, "0.00")
        check(45_000.5, 14, "m/d/yy") // date serial → date rendering stays POI's
        check(0.75, 9, "0%")
        check(1234.5, 3, "#,##0")
    }
}
