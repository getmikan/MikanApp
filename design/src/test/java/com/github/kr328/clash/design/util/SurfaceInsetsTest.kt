package com.github.kr328.clash.design.util

import androidx.core.graphics.Insets as Bounds
import com.github.kr328.clash.design.ui.Insets
import kotlin.test.Test
import kotlin.test.assertEquals

class SurfaceInsetsTest {
    @Test fun portraitKeepsStatusAndGestureBars() {
        assertEquals(Insets(0, 24, 0, 32), safeSurfaceInsets(Bounds.of(0, 24, 0, 32), Bounds.NONE, false))
    }

    @Test fun landscapeCutoutProtectsControlsOnTheLeft() {
        assertEquals(Insets(80, 24, 0, 32), safeSurfaceInsets(Bounds.of(0, 24, 0, 32), Bounds.of(80, 0, 0, 0), false))
    }

    @Test fun rightCutoutAndSideNavigationAreBothPreserved() {
        assertEquals(Insets(48, 24, 80, 0), safeSurfaceInsets(Bounds.of(48, 24, 0, 0), Bounds.of(0, 0, 80, 0), false))
    }

    @Test fun rtlSwapsOnlyHorizontalEdges() {
        assertEquals(Insets(80, 24, 48, 32), safeSurfaceInsets(Bounds.of(48, 24, 0, 32), Bounds.of(0, 0, 80, 0), true))
    }

    @Test fun tallerCutoutWinsOverStatusBar() {
        assertEquals(Insets(0, 64, 0, 32), safeSurfaceInsets(Bounds.of(0, 24, 0, 32), Bounds.of(0, 64, 0, 0), false))
    }

    @Test fun freeformCaptionAndThreeButtonNavigationRemainProtected() {
        assertEquals(Insets(0, 40, 0, 48), safeSurfaceInsets(Bounds.of(0, 40, 0, 48), Bounds.NONE, false))
    }
}
