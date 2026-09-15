package com.lucy.storybuilder.job

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DirectorBudgetTest {
    @Test
    fun `budget stays between the minimum and the configured timeout`() {
        assertEquals(12_000, clampDirectorBudget(12_000, 30_000))
        assertEquals(4_000, clampDirectorBudget(-5_000, 30_000), "never below the minimum")
        assertEquals(30_000, clampDirectorBudget(90_000, 30_000), "never above the timeout")
    }

    @Test
    fun `a configured timeout below the minimum wins instead of throwing`() {
        assertEquals(2_000, clampDirectorBudget(12_000, 2_000))
        assertEquals(2_000, clampDirectorBudget(-5_000, 2_000))
        assertEquals(0, clampDirectorBudget(12_000, 0))
    }
}
