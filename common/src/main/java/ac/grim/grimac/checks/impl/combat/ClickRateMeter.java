package ac.grim.grimac.checks.impl.combat;

import ac.grim.grimac.checks.GrimProcessor;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;

// Left clicks of the last seconds, for /grim cps. Only clients older than 1.9 have them: see LeftClickProcessor.
// Seconds are counted in client ticks, so network jitter can't move clicks from one second to another. The packet thread
// writes and the command thread reads: staff read these numbers by eye, so one a moment old is fine.
public final class ClickRateMeter extends GrimProcessor implements PacketReceiveListener {
    private static final int TICKS_PER_SECOND = 20;
    private static final int HISTORY_SECONDS = 10;

    private final int[] clicksPerSecond = new int[HISTORY_SECONDS];
    private int nextSecond;
    private int ticksInSecond;
    private int clicksInSecond;

    public ClickRateMeter(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(final PacketReceiveEvent event) {
        if (!isTickPacket(event.getPacketType())) return;

        final LeftClickProcessor tick = player.checkManager.get(LeftClickProcessor.class);
        if (!tick.isBlockTick()) {
            clicksInSecond += tick.getClicks();
        }

        if (++ticksInSecond < TICKS_PER_SECOND) return;

        clicksPerSecond[nextSecond] = clicksInSecond;
        nextSecond = (nextSecond + 1) % HISTORY_SECONDS;
        ticksInSecond = 0;
        clicksInSecond = 0;
    }

    public int getLastSecondClicks() {
        return clicksPerSecond[(nextSecond + HISTORY_SECONDS - 1) % HISTORY_SECONDS];
    }

    public int getPeakClicks() {
        int peak = 0;
        for (int clicks : clicksPerSecond) {
            peak = Math.max(peak, clicks);
        }
        return peak;
    }

    // Seconds without clicks say nothing about the rate, and would drag it down right after a short fight
    public double getAverageClicks() {
        int clickedSeconds = 0;
        int total = 0;
        for (int clicks : clicksPerSecond) {
            if (clicks > 0) {
                clickedSeconds++;
                total += clicks;
            }
        }
        return clickedSeconds == 0 ? 0 : (double) total / clickedSeconds;
    }
}
