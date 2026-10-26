package dev.chaoslab.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chaoslab.ui.GapHistory;
import dev.chaoslab.ui.TelemetryHistory;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class CsvExportTest {

    @Test
    void driftHasHeaderAndOneRowPerEntry() {
        TelemetryHistory h = new TelemetryHistory(8);
        h.offer(1, 0.5, 1e-9, 2e-12, 3e-10);
        h.offer(2, 1.0, 4e-9, 5e-12, 6e-10);
        String csv = CsvExport.drift(h, "time");
        assertEquals("time,abs_dE_over_E0,dP_over_Pscale,dL_over_L0\n"
                + "0.5,1.0E-9,2.0E-12,3.0E-10\n"
                + "1.0,4.0E-9,5.0E-12,6.0E-10\n", csv);
    }

    @Test
    void errorWritesAnUnknownUncertaintyAsAnEmptyField() {
        GapHistory h = new GapHistory(8);
        h.offer(1.0, 1e-6, Double.NaN);
        h.offer(2.0, 2e-6, 1e-12);
        assertEquals("time,relative_gap,relative_reference_uncertainty\n"
                + "1.0,1.0E-6,\n"
                + "2.0,2.0E-6,1.0E-12\n", CsvExport.error(h, "time"));
    }

    @Test
    void twinHasItsOwnColumn() {
        GapHistory h = new GapHistory(8);
        h.offer(3.0, 0.25, Double.NaN);
        assertEquals("t,relative_separation\n3.0,0.25\n", CsvExport.twin(h, "t"));
    }

    @Test
    void anEmptyHistoryGivesOnlyTheHeader() {
        assertEquals("time,abs_dE_over_E0,dP_over_Pscale,dL_over_L0\n",
                CsvExport.drift(new TelemetryHistory(4), "time"));
        assertEquals("time,relative_gap,relative_reference_uncertainty\n",
                CsvExport.error(new GapHistory(4), "time"));
    }

    @Test
    void numbersReadBackExactly() {
        GapHistory h = new GapHistory(8);
        double gap = 1.0 / 3.0 * 1e-7;
        h.offer(Math.PI, gap, 2.0 / 7.0 * 1e-13);
        String[] cells = CsvExport.error(h, "time").split("\n")[1].split(",");
        assertEquals(Math.PI, Double.parseDouble(cells[0]), 0.0);
        assertEquals(gap, Double.parseDouble(cells[1]), 0.0);
        assertEquals(2.0 / 7.0 * 1e-13, Double.parseDouble(cells[2]), 0.0);
    }

    @Test
    void outputDoesNotDependOnTheComputersLocale() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            GapHistory h = new GapHistory(4);
            h.offer(1.5, 0.25, 0.125);
            String csv = CsvExport.error(h, "time");
            assertTrue(csv.contains("1.5,0.25,0.125"), csv);
            assertTrue(!csv.contains("\r"), "lines end in a single newline");
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    void aMergedEntryIsTheWorstValueAtTheLatestTime() {
        TelemetryHistory h = new TelemetryHistory(4);
        for (int i = 1; i <= 5; i++) {
            h.offer(i, i, i == 2 ? 9e-3 : 1e-9, 1e-12, 1e-10);
        }
        assertEquals(2, h.stride());
        String[] rows = CsvExport.drift(h, "time").split("\n");
        assertEquals(h.size() + 1, rows.length);
        assertTrue(rows[1].startsWith("2.0,0.009,"), rows[1]);
    }

    @Test
    void timeColumnNameMustBeAPlainIdentifier() {
        TelemetryHistory h = new TelemetryHistory(4);
        assertThrows(IllegalArgumentException.class, () -> CsvExport.drift(h, "t,x"));
        assertThrows(IllegalArgumentException.class, () -> CsvExport.drift(h, ""));
        assertThrows(IllegalArgumentException.class, () -> CsvExport.drift(h, null));
        assertThrows(IllegalArgumentException.class, () -> CsvExport.error(new GapHistory(4), "1t"));
    }
}
