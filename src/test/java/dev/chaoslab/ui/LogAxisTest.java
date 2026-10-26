package dev.chaoslab.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LogAxisTest {

    @Test
    void theDefaultRangeCoversRoundingLevelAndOrderOne() {
        LogAxis a = LogAxis.covering(0.0, 0.0);
        assertEquals(-17, a.minExponent());
        assertEquals(0, a.maxExponent());
        assertEquals(17, a.decades());
    }

    @Test
    void itWidensToIncludeTheDataAndNeverNarrows() {
        LogAxis big = LogAxis.covering(1e-12, 3.79);          // the energy drift seen when a close encounter failed
        assertEquals(1, big.maxExponent());                     // ceil(log10(3.79)) = 1
        assertEquals(-17, big.minExponent());                   // not narrowed to -12
        LogAxis tiny = LogAxis.covering(3e-20, 1e-30);
        assertEquals(-20, tiny.minExponent());                  // floor(log10(3e-20)) = -20
        assertEquals(0, tiny.maxExponent());
    }

    @Test
    void nonFiniteAndNonPositiveInputsAreIgnored() {
        LogAxis a = LogAxis.covering(Double.NaN, Double.POSITIVE_INFINITY);
        assertEquals(LogAxis.DEFAULT_MIN_EXPONENT, a.minExponent());
        assertEquals(LogAxis.DEFAULT_MAX_EXPONENT, a.maxExponent());
        LogAxis b = LogAxis.covering(-1.0, -5.0);
        assertEquals(LogAxis.DEFAULT_MAX_EXPONENT, b.maxExponent());
    }

    @Test
    void fractionIsLogarithmicAndClamped() {
        LogAxis a = new LogAxis(-17, 0);
        assertEquals(1.0, a.fraction(1.0), 1e-12);
        assertEquals(14.0 / 17.0, a.fraction(1e-3), 1e-12);      // the warning threshold
        assertEquals(1.0 / 17.0, a.fraction(1e-16), 1e-12);
        assertEquals(0.0, a.fraction(1e-17), 1e-12);
        assertEquals(0.0, a.fraction(0.0));                      // exact zero sits on the floor
        assertEquals(0.0, a.fraction(1e-30));                    // below the floor is clamped
        assertEquals(1.0, a.fraction(50.0));                     // above the top is clamped
        assertEquals(0.0, a.fraction(Double.NaN));
        double prev = -1.0;
        for (double v = 1e-17; v <= 1.0; v *= 3.0) {
            assertTrue(a.fraction(v) >= prev, "monotonic");
            prev = a.fraction(v);
        }
    }

    @Test
    void ticksAreWholeDecadesFromTheTopAndRespectTheLimit() {
        LogAxis a = new LogAxis(-17, 0);
        int[] t = a.tickExponents(18);
        assertEquals(18, t.length);
        assertEquals(0, t[0]);
        assertEquals(-17, t[17]);
        int[] sparse = a.tickExponents(6);
        assertTrue(sparse.length <= 6);
        assertEquals(0, sparse[0]);
        assertArrayEquals(new int[] {0, -3, -6, -9, -12, -15}, sparse);   // step 3 is the smallest giving <= 6
        for (int i = 1; i < sparse.length; i++) {
            assertEquals(3, sparse[i - 1] - sparse[i]);
        }
    }

    @Test
    void labelsAndValidation() {
        assertEquals("1", LogAxis.label(0));
        assertEquals("1e-3", LogAxis.label(-3));
        assertEquals("1e1", LogAxis.label(1));
        assertThrows(IllegalArgumentException.class, () -> new LogAxis(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new LogAxis(2, 1));
        assertThrows(IllegalArgumentException.class, () -> new LogAxis(-5, 0).tickExponents(1));
    }
}
