package dev.chaoslab.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Camera}. Failure interpretation: these check the arithmetic that decides what is drawn where.
 * A failure means the picture would be mis-scaled, mirrored, or that zooming would drift away from the cursor.
 */
class CameraTest {

    private static Camera camera() {
        Camera c = new Camera();
        c.setViewport(800, 600);
        return c;
    }

    @Test
    void theOriginIsAtTheViewportCentreByDefault() {
        Camera c = camera();
        assertEquals(400.0, c.worldToScreenX(0.0), 0.0);
        assertEquals(300.0, c.worldToScreenY(0.0), 0.0);
    }

    @Test
    void worldYPointsUpOnScreen() {
        Camera c = camera();
        assertEquals(400.0 + Camera.DEFAULT_SCALE, c.worldToScreenX(1.0), 1e-12);   // +x is to the right
        assertEquals(300.0 - Camera.DEFAULT_SCALE, c.worldToScreenY(1.0), 1e-12);   // +y is up (smaller pixel y)
    }

    @Test
    void screenAndWorldTransformsAreInverses() {
        Random rng = new Random(11);
        for (int i = 0; i < 200; i++) {
            Camera c = new Camera();
            c.setViewport(100 + rng.nextInt(2000), 100 + rng.nextInt(1500));
            c.zoomAt(rng.nextDouble() * 500, rng.nextDouble() * 500, Math.exp(rng.nextGaussian()));
            c.panByPixels(rng.nextGaussian() * 300, rng.nextGaussian() * 300);
            double wx = rng.nextGaussian() * 5;
            double wy = rng.nextGaussian() * 5;
            assertEquals(wx, c.screenToWorldX(c.worldToScreenX(wx)), 1e-9 * (1 + Math.abs(wx)));
            assertEquals(wy, c.screenToWorldY(c.worldToScreenY(wy)), 1e-9 * (1 + Math.abs(wy)));
        }
    }

    @Test
    void zoomingKeepsTheWorldPointUnderTheCursorFixed() {
        Camera c = camera();
        c.panByPixels(37, -12);
        double sx = 623, sy = 111;
        double wx = c.screenToWorldX(sx);
        double wy = c.screenToWorldY(sy);
        double before = c.scale();
        c.zoomAt(sx, sy, 2.5);
        assertEquals(before * 2.5, c.scale(), 1e-9);
        assertEquals(wx, c.screenToWorldX(sx), 1e-12);
        assertEquals(wy, c.screenToWorldY(sy), 1e-12);
        c.zoomAt(sx, sy, 1 / 2.5);
        assertEquals(before, c.scale(), 1e-9);
    }

    @Test
    void zoomIsClampedAndTheCursorStaysAnchoredEvenWhenClamped() {
        Camera c = camera();
        double wx = c.screenToWorldX(10);
        double wy = c.screenToWorldY(20);
        c.zoomAt(10, 20, 1e30);
        assertEquals(Camera.MAX_SCALE, c.scale(), 0.0);
        assertEquals(wx, c.screenToWorldX(10), 1e-9);
        assertEquals(wy, c.screenToWorldY(20), 1e-9);
        c.zoomAt(10, 20, 1e-30);
        assertEquals(Camera.MIN_SCALE, c.scale(), 0.0);
    }

    @Test
    void panningDragsTheContentWithTheMouse() {
        Camera c = camera();
        c.panByPixels(50, -20);
        assertEquals(450.0, c.worldToScreenX(0.0), 1e-12); // content moved right by 50 px
        assertEquals(280.0, c.worldToScreenY(0.0), 1e-12); // and up by 20 px
    }

    @Test
    void fitCentresTheBoundsAndHonoursTheMargin() {
        Camera c = camera();
        c.fit(-2, 6, 1, 3, 0.1);                 // 8 wide, 2 tall; usable area 640 x 480
        assertEquals(2.0, c.centerX(), 1e-15);
        assertEquals(2.0, c.centerY(), 1e-15);
        assertEquals(80.0, c.scale(), 1e-12);    // min(640/8, 480/2) = 80: width decides
        assertEquals(80.0, c.worldToScreenX(-2), 1e-9);   // exactly the 10 % margin
        assertEquals(720.0, c.worldToScreenX(6), 1e-9);
    }

    @Test
    void fitIsLimitedByTheTighterDirection() {
        Camera c = camera();
        c.fit(-1, 1, -5, 5, 0.1);                // 2 wide, 10 tall: min(640/2, 480/10) = 48
        assertEquals(48.0, c.scale(), 1e-12);
        assertEquals(60.0, c.worldToScreenY(5), 1e-9);    // top margin = 10 % of 600
        assertEquals(540.0, c.worldToScreenY(-5), 1e-9);
    }

    @Test
    void fitWithAllPointsOnALineLetsTheOtherDirectionDecide() {
        Camera c = camera();
        c.fit(0, 4, 7, 7, 0.1);                  // zero height
        assertEquals(160.0, c.scale(), 1e-12);   // 640 / 4
        assertEquals(2.0, c.centerX(), 1e-15);
        assertEquals(7.0, c.centerY(), 1e-15);
    }

    @Test
    void fitWithASinglePointOnlyMovesTheCentre() {
        Camera c = camera();
        double before = c.scale();
        c.fit(3, 3, -4, -4, 0.1);
        assertEquals(before, c.scale(), 0.0);
        assertEquals(3.0, c.centerX(), 0.0);
        assertEquals(-4.0, c.centerY(), 0.0);
    }

    @Test
    void fitClampsAbsurdlySmallExtents() {
        Camera c = camera();
        c.fit(0, 1e-300, 0, 1e-300, 0.1);
        assertEquals(Camera.MAX_SCALE, c.scale(), 0.0);
    }

    @Test
    void resetReturnsToTheOriginAtDefaultScale() {
        Camera c = camera();
        c.fit(10, 20, 10, 20, 0.1);
        c.reset();
        assertEquals(0.0, c.centerX(), 0.0);
        assertEquals(0.0, c.centerY(), 0.0);
        assertEquals(Camera.DEFAULT_SCALE, c.scale(), 0.0);
    }

    @Test
    void invalidArgumentsAreRejected() {
        Camera c = camera();
        assertThrows(IllegalArgumentException.class, () -> c.setViewport(0, 100));
        assertThrows(IllegalArgumentException.class, () -> c.setViewport(100, -1));
        assertThrows(IllegalArgumentException.class, () -> c.setViewport(Double.NaN, 100));
        assertThrows(IllegalArgumentException.class, () -> c.zoomAt(0, 0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> c.zoomAt(0, 0, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> c.fit(1, 0, 0, 1, 0.1));          // max < min
        assertThrows(IllegalArgumentException.class, () -> c.fit(0, Double.NaN, 0, 1, 0.1));
        assertThrows(IllegalArgumentException.class, () -> c.fit(0, 1, 0, 1, 0.5));          // margin too large
        assertThrows(IllegalArgumentException.class, () -> c.fit(0, 1, 0, 1, -0.1));
        assertTrue(c.width() == 800 && c.height() == 600, "a rejected call must not change the viewport");
    }
}
