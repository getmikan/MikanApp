package com.github.kr328.clash.design.view

import kotlin.test.Test
import kotlin.test.assertEquals

class SegmentFlowLinesTest {
    @Test
    fun noSegmentsTakeNoLines() {
        assertEquals(0, flowLineCount(emptyList(), inner = 300, spacing = 4))
    }

    @Test
    fun segmentsThatFitStayOnOneLine() {
        assertEquals(1, flowLineCount(listOf(100, 100, 92), inner = 300, spacing = 4))
    }

    @Test
    fun segmentsWrapWhenTheNextOneDoesNotFit() {
        // 100 + 4 + 100 + 4 = 208; a third 100 would end at 308 > 300.
        assertEquals(2, flowLineCount(listOf(100, 100, 100), inner = 300, spacing = 4))
        assertEquals(3, flowLineCount(listOf(200, 200, 200), inner = 300, spacing = 4))
    }

    @Test
    fun aSegmentWiderThanTheLineGetsALineOfItsOwn() {
        assertEquals(2, flowLineCount(listOf(400, 50), inner = 300, spacing = 4))
    }
}
