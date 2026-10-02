package net.fabcelhaft.hackathonorganiser.a11y;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import java.util.ArrayList;
import java.util.List;

/**
 * Feature 013 (FR-013, SC-001): the shared check that a Topic row never stacks one control above
 * another.
 *
 * <p>The defect this feature exists to fix is not "a button looks wrong" but "the action cell is
 * narrower than its contents, so the flex row wraps and the row grows to three or four lines". That
 * is only observable in a real layout engine — a rendered-HTML assertion cannot see it at all,
 * which is why this lives in the Playwright suite rather than in a {@code WebTestClient} IT.
 *
 * <p>Two controls count as stacked when their vertical centres differ by more than half the height
 * of the first. A plain y-coordinate comparison is too strict: controls of different heights sitting
 * on one line legitimately have different tops, and sub-pixel layout rounding moves them around.
 * Comparing centres with a height-relative tolerance tracks what a reader would actually call
 * "on the same line".
 */
final class RowLayoutAssertions {

    private RowLayoutAssertions() {}

    /**
     * Fails when any row-action cell on the page renders a control below another control in the
     * same cell. {@code label} identifies the screen in the failure message.
     */
    static void assertNoStackedRowControls(Page page, String label) {
        List<String> offenders = new ArrayList<>();

        // The flex row lives in a div inside the cell, not on the <td> — a flex <td> stops
        // stretching to the row height. Both spellings are matched so this keeps working if a
        // cell is ever restructured again.
        for (ElementHandle cell : page.querySelectorAll("td .actions, td.actions")) {
            List<ElementHandle> controls =
                    cell.querySelectorAll("button, a[role=\"button\"], [data-vote-button]");
            if (controls.size() < 2) {
                continue;
            }
            ElementHandle first = controls.get(0);
            var firstBox = first.boundingBox();
            if (firstBox == null) {
                continue; // not rendered (hidden cell) — nothing to stack
            }
            double firstCentre = firstBox.y + (firstBox.height / 2);
            double tolerance = Math.max(firstBox.height / 2, 1);

            for (int i = 1; i < controls.size(); i++) {
                var box = controls.get(i).boundingBox();
                if (box == null) {
                    continue;
                }
                double centre = box.y + (box.height / 2);
                if (Math.abs(centre - firstCentre) > tolerance) {
                    offenders.add(String.format(
                            "control %d in a row-action cell sits %.1fpx below the first (tolerance %.1fpx)",
                            i, centre - firstCentre, tolerance));
                }
            }
        }

        assertThat(offenders)
                .withFailMessage(() -> label + " stacks row controls instead of keeping them on one line (FR-013): "
                        + String.join("; ", offenders))
                .isEmpty();
    }
}
