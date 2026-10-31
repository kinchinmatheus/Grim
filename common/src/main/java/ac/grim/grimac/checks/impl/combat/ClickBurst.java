package ac.grim.grimac.checks.impl.combat;

// One burst of left clicks, tick by tick. Only its summary leaves memory: interval counts, single attack runs and
// click count dispersion, never the click sequence itself.
final class ClickBurst {
    // Clicks further apart than this end the burst, and so does reaching MAX_TICKS
    private static final int MAX_INTERVAL_TICKS = 20;
    private static final int MAX_TICKS = 600;
    private static final int MIN_RECORDED_CLICKS = 20;
    // Intervals of 1, 2, 3 and 4+ ticks
    private static final int TRANSITION_BUCKETS = 4;
    // Longer runs of ticks with exactly one attack count as this long
    private static final int MAX_RUN_LENGTH = 12;
    private static final int SHORT_WINDOW_TICKS = 20;
    private static final int LONG_WINDOW_TICKS = 60;

    private final long startMillis;
    private final byte[] clicksPerTick = new byte[MAX_TICKS];
    private final byte[] attacksPerTick = new byte[MAX_TICKS];
    private int ticks;
    private int lastClickTick = -1;
    private int clicks;

    ClickBurst(long startMillis) {
        this.startMillis = startMillis;
    }

    void addTick(int clicks, int attacks) {
        clicksPerTick[ticks] = (byte) Math.min(clicks, Byte.MAX_VALUE);
        attacksPerTick[ticks] = (byte) Math.min(attacks, Byte.MAX_VALUE);
        if (clicks > 0) {
            lastClickTick = ticks;
            this.clicks += clicks;
        }
        ticks++;
    }

    boolean isOver() {
        // The next tick could only click further than MAX_INTERVAL_TICKS from the last click
        return ticks == MAX_TICKS || ticks - lastClickTick > MAX_INTERVAL_TICKS;
    }

    boolean isWorthRecording() {
        return clicks >= MIN_RECORDED_CLICKS;
    }

    String toRecord(String playerName, String clientVersion, int ping) {
        // The pause that ended the burst is not part of it
        final int span = lastClickTick + 1;
        int attacks = 0;
        int multiClickTicks = 0;
        final int[] intervals = new int[MAX_INTERVAL_TICKS + 1];
        final int[] transitions = new int[TRANSITION_BUCKETS * TRANSITION_BUCKETS];
        final int[] singleAttackRuns = new int[MAX_RUN_LENGTH + 1];
        int previousClickTick = -1;
        int previousInterval = 0;
        int singleAttackRun = 0;

        for (int tick = 0; tick < span; tick++) {
            attacks += attacksPerTick[tick];
            if (attacksPerTick[tick] == 1) {
                singleAttackRun++;
            } else {
                countRun(singleAttackRuns, singleAttackRun);
                singleAttackRun = 0;
            }

            if (clicksPerTick[tick] == 0) continue;
            if (clicksPerTick[tick] > 1) multiClickTicks++;
            if (previousClickTick >= 0) {
                final int interval = tick - previousClickTick;
                intervals[interval]++;
                if (previousInterval > 0) {
                    transitions[bucket(previousInterval) * TRANSITION_BUCKETS + bucket(interval)]++;
                }
                previousInterval = interval;
            }
            previousClickTick = tick;
        }
        countRun(singleAttackRuns, singleAttackRun);

        return new StringBuilder(512)
                .append("{\"t\":").append(startMillis)
                // Offline mode servers accept names with quotes and backslashes
                .append(",\"p\":\"").append(playerName.replace("\\", "\\\\").replace("\"", "\\\""))
                .append("\",\"v\":\"").append(clientVersion)
                .append("\",\"ping\":").append(ping)
                .append(",\"ticks\":").append(span)
                .append(",\"clicks\":").append(clicks)
                .append(",\"attacks\":").append(attacks)
                .append(",\"multi\":").append(multiClickTicks)
                // Index 0 of these two is unused: no interval and no run is 0 ticks long
                .append(",\"iv\":").append(toJsonArray(intervals))
                .append(",\"runs\":").append(toJsonArray(singleAttackRuns))
                .append(",\"tr\":").append(toJsonArray(transitions))
                .append(",\"w20\":").append(windowDispersion(span, SHORT_WINDOW_TICKS))
                .append(",\"w60\":").append(windowDispersion(span, LONG_WINDOW_TICKS))
                .append('}')
                .toString();
    }

    private static void countRun(int[] runs, int length) {
        if (length > 0) runs[Math.min(length, MAX_RUN_LENGTH)]++;
    }

    private static int bucket(int interval) {
        return Math.min(interval, TRANSITION_BUCKETS) - 1;
    }

    // [windows, sum, sum of squares] of the clicks in each full window, enough to get their variance offline
    private String windowDispersion(int span, int windowTicks) {
        int windows = 0;
        long sum = 0;
        long sumOfSquares = 0;
        for (int start = 0; start + windowTicks <= span; start += windowTicks) {
            int windowClicks = 0;
            for (int tick = start; tick < start + windowTicks; tick++) {
                windowClicks += clicksPerTick[tick];
            }
            windows++;
            sum += windowClicks;
            sumOfSquares += (long) windowClicks * windowClicks;
        }
        return "[" + windows + "," + sum + "," + sumOfSquares + "]";
    }

    private static String toJsonArray(int[] values) {
        final StringBuilder array = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) array.append(',');
            array.append(values[i]);
        }
        return array.append(']').toString();
    }
}
