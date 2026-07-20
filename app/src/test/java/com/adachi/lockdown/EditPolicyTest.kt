package com.adachi.lockdown

import com.adachi.lockdown.data.ALL_DAYS_MASK
import com.adachi.lockdown.data.RuleType
import com.adachi.lockdown.rules.EditPolicy
import com.adachi.lockdown.rules.EditPolicy.Shape
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditPolicyTest {

    private fun shape(
        type: RuleType = RuleType.BLOCK,
        enabled: Boolean = true,
        daysMask: Int = ALL_DAYS_MASK,
        startMin: Int = 0,
        endMin: Int = 0,
        quotaMin: Int = 0,
        pattern: String = "reddit.com",
    ) = Shape(type, enabled, daysMask, startMin, endMin, quotaMin, pattern)

    // Create
    @Test fun `creating a block rule is not relaxing`() =
        assertFalse(EditPolicy.isRelaxing(null, shape(RuleType.BLOCK)))

    @Test fun `creating an allow rule is relaxing`() =
        assertTrue(EditPolicy.isRelaxing(null, shape(RuleType.ALLOW)))

    @Test fun `creating a window or quota rule is not relaxing`() {
        assertFalse(EditPolicy.isRelaxing(null, shape(RuleType.WINDOW, startMin = 60, endMin = 120)))
        assertFalse(EditPolicy.isRelaxing(null, shape(RuleType.QUOTA, quotaMin = 30)))
    }

    // Delete
    @Test fun `deleting a block rule is relaxing`() =
        assertTrue(EditPolicy.isRelaxing(shape(RuleType.BLOCK), null))

    @Test fun `deleting an allow rule is not relaxing`() =
        assertFalse(EditPolicy.isRelaxing(shape(RuleType.ALLOW), null))

    // Enable / disable
    @Test fun `disabling a block rule is relaxing`() =
        assertTrue(EditPolicy.isRelaxing(shape(RuleType.BLOCK), shape(RuleType.BLOCK, enabled = false)))

    @Test fun `enabling a disabled block rule is not relaxing`() =
        assertFalse(EditPolicy.isRelaxing(shape(RuleType.BLOCK, enabled = false), shape(RuleType.BLOCK)))

    // Type changes
    @Test fun `block to window is relaxing`() =
        assertTrue(EditPolicy.isRelaxing(shape(RuleType.BLOCK), shape(RuleType.WINDOW, startMin = 60, endMin = 120)))

    @Test fun `window to block is not relaxing`() =
        assertFalse(EditPolicy.isRelaxing(shape(RuleType.WINDOW, startMin = 60, endMin = 120), shape(RuleType.BLOCK)))

    @Test fun `anything to allow is relaxing`() =
        assertTrue(EditPolicy.isRelaxing(shape(RuleType.QUOTA, quotaMin = 10), shape(RuleType.ALLOW)))

    // Pattern breadth
    @Test fun `narrowing a blocked pattern is relaxing`() =
        assertTrue(EditPolicy.isRelaxing(shape(pattern = "reddit.com"), shape(pattern = "old.reddit.com")))

    @Test fun `broadening a blocked pattern is not relaxing`() =
        assertFalse(EditPolicy.isRelaxing(shape(pattern = "old.reddit.com"), shape(pattern = "reddit.com")))

    @Test fun `broadening an allowed pattern is relaxing`() =
        assertTrue(EditPolicy.isRelaxing(shape(RuleType.ALLOW, pattern = "old.reddit.com"), shape(RuleType.ALLOW, pattern = "reddit.com")))

    @Test fun `unrelated pattern change is relaxing`() =
        assertTrue(EditPolicy.isRelaxing(shape(pattern = "reddit.com"), shape(pattern = "twitter.com")))

    // Window tightening
    @Test fun `shrinking a window is not relaxing`() =
        assertFalse(
            EditPolicy.isRelaxing(
                shape(RuleType.WINDOW, startMin = 9 * 60, endMin = 17 * 60),
                shape(RuleType.WINDOW, startMin = 10 * 60, endMin = 16 * 60),
            ),
        )

    @Test fun `widening a window is relaxing`() =
        assertTrue(
            EditPolicy.isRelaxing(
                shape(RuleType.WINDOW, startMin = 10 * 60, endMin = 16 * 60),
                shape(RuleType.WINDOW, startMin = 9 * 60, endMin = 17 * 60),
            ),
        )

    @Test fun `shrinking a wrapping window is not relaxing`() =
        assertFalse(
            EditPolicy.isRelaxing(
                shape(RuleType.WINDOW, startMin = 20 * 60, endMin = 2 * 60),
                shape(RuleType.WINDOW, startMin = 21 * 60, endMin = 1 * 60),
            ),
        )

    @Test fun `removing a day from a window is not relaxing`() =
        assertFalse(
            EditPolicy.isRelaxing(
                shape(RuleType.WINDOW, daysMask = ALL_DAYS_MASK, startMin = 60, endMin = 120),
                shape(RuleType.WINDOW, daysMask = 0b0000011, startMin = 60, endMin = 120),
            ),
        )

    // Quota
    @Test fun `lowering a quota is not relaxing`() =
        assertFalse(EditPolicy.isRelaxing(shape(RuleType.QUOTA, quotaMin = 60), shape(RuleType.QUOTA, quotaMin = 15)))

    @Test fun `raising a quota is relaxing`() =
        assertTrue(EditPolicy.isRelaxing(shape(RuleType.QUOTA, quotaMin = 15), shape(RuleType.QUOTA, quotaMin = 60)))
}
