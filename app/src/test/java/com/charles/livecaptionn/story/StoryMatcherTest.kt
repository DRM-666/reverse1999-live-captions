package com.charles.livecaptionn.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoryMatcherTest {
    @Test fun `a close match returns the supplied line with punctuation`() {
        val matcher = StoryMatcher("The rain has stopped at last.\nWe should return before the storm.")
        assertEquals("The rain has stopped at last.", matcher.match("the rain has stoped at last"))
    }

    @Test fun `unrelated and short speech remains ordinary recognition`() {
        val matcher = StoryMatcher("The rain has stopped at last.")
        assertNull(matcher.match("Good morning to everyone here"))
        assertNull(matcher.match("at last"))
    }

    @Test fun `ambiguous similar lines are not forced to a reference`() {
        val matcher = StoryMatcher("The rain has stopped at last.\nThe rain has started at last.")
        assertNull(matcher.match("the rain has stpped at last"))
    }

    @Test fun `duplicate identical lines are not treated as competing meanings`() {
        val matcher = StoryMatcher("The rain has stopped at last.\nThe rain has stopped at last.")
        assertEquals("The rain has stopped at last.", matcher.match("the rain has stopped at last"))
    }

    @Test fun `a huge set of nearly identical lines fails closed within the work budget`() {
        val stem = "this is a very long sentence with many repeating english words for testing only"
        val script = (1000..1300).joinToString("\n") { "$stem $it" }
        val matcher = StoryMatcher(script)
        assertNull(matcher.match("$stem 1000"))
    }
}
