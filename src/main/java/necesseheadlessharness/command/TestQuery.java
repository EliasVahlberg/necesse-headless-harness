package necesseheadlessharness.command;

import necesseheadlessharness.Json;

/**
 * An expectation that can also report its value instead of a verdict.
 *
 * <p>The reason this exists rather than a driver computing values for itself: {@code expect capacity
 * 4 0 1 80} already knows the number, and prints {@code PASS} while throwing it away. A driver that
 * derived the same number independently would be a second definition of what "capacity" means, and
 * the two would drift. So the value is produced once and consumed twice -- compared in-game by
 * {@code expect}, returned verbatim by {@code query}.
 *
 * <p>{@code expect} is deliberately kept even though it is now redundant: a scenario line can be
 * pasted into a live server to watch a failure happen by hand, which a JSON reply cannot.
 *
 * <p>Implementations write their own fields, which keeps this interface free of a value model and
 * of any reflection. Arguments arrive exactly as {@code expect} receives them, minus the expected
 * values at the end -- so {@code query capacity 4 0} shares the coordinate positions of
 * {@code expect capacity 4 0 1 80}.
 */
public interface TestQuery {

   void query(TestContext context, Json.Writer out);
}
