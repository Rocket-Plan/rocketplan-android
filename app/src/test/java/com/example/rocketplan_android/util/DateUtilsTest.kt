package com.example.rocketplan_android.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Date

class DateUtilsTest {

    @Test
    fun `formatApiDate outputs explicit offset`() {
        val sampleDate = Date(1_702_000_000_000L) // Stable sample moment

        val formatted = DateUtils.formatApiDate(sampleDate)

        assertThat(formatted).endsWith("+00:00")
        assertThat(formatted).doesNotContain("Z")
        assertThat(DateUtils.parseApiDate(formatted)).isEqualTo(sampleDate)
    }

    @Test
    fun `parseApiDate handles microsecond timestamps with Z offset`() {
        val input = "2025-03-25T02:31:46.000000Z"
        val expected = Date.from(OffsetDateTime.parse("2025-03-25T02:31:46Z").toInstant())

        val parsed = DateUtils.parseApiDate(input)

        assertThat(parsed).isEqualTo(expected)
    }

    @Test
    fun `parseApiDate handles microsecond timestamps with explicit offset`() {
        val input = "2025-05-06T18:01:46.123456+00:00"
        val expectedInstant = OffsetDateTime.parse("2025-05-06T18:01:46.123456+00:00").toInstant()

        val parsed = DateUtils.parseApiDate(input)

        assertThat(parsed).isEqualTo(Date.from(expectedInstant))
    }

    @Test
    fun `parseApiDate still parses date only strings`() {
        val input = "2025-05-06"
        val expected = OffsetDateTime.of(2025, 5, 6, 0, 0, 0, 0, ZoneOffset.UTC).toInstant()

        val parsed = DateUtils.parseApiDate(input)

        assertThat(parsed).isEqualTo(Date.from(expected))
    }
}
