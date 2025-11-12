package com.example.rocketplan_android.util

import com.google.common.truth.Truth.assertThat
import java.util.Date
import org.junit.Test

class DateUtilsTest {

    @Test
    fun `formatApiDate outputs explicit offset`() {
        val sampleDate = Date(1_702_000_000_000L) // Stable sample moment

        val formatted = DateUtils.formatApiDate(sampleDate)

        assertThat(formatted).endsWith("+00:00")
        assertThat(formatted).doesNotContain("Z")
        assertThat(DateUtils.parseApiDate(formatted)).isEqualTo(sampleDate)
    }
}
