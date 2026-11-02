package ac.grim.grimac.checks.impl.combat;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ThreadLocalRandom;

// Autoclickers with weak randomization keep one rate, so a second of clicking has at most one click more or less than
// the second before it. By hand the rate drifts: the most regular hand recorded on dev lasted 21 such seconds in a row.
// That margin is too small to ban on, so regular players only lose part of their hits for a while.
@CheckData(name = "ClickRegularity", stableKey = "grim.combat.click_regularity", description = "Kept the same click rate for too long while attacking")
public class ClickRegularity extends Check implements PacketReceiveListener {
    private static final Verbose V = Verbose.of("cps={uint}, seconds={uint}");
    private static final int WINDOW_TICKS = 20;
    // Hand clicking below this rate is regular too, and gives no edge worth limiting
    private static final int MIN_WINDOW_CLICKS = 10;
    private static final int MAX_WINDOW_DIFFERENCE = 1;
    // More empty ticks than this mean the player stopped clicking, which says nothing about their rate
    private static final int MAX_EMPTY_TICKS = 5;
    private static final int NO_WINDOW = -1;

    private int minRegularSeconds;
    private double cancelChance;
    private long cancelMillis;

    private int windowTicks;
    private int windowClicks;
    private int windowAttacks;
    private int emptyTicks;
    private int previousWindowClicks = NO_WINDOW;
    // Pauses don't reset it, so it adds up over the fights of a session
    private int regularSeconds;
    private long limitedUntil;

    public ClickRegularity(GrimPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {
        return player.getClientVersion().isOlderThan(ClientVersion.V_1_9);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        super.onReload(config);
        this.minRegularSeconds = Math.max(1, config.getIntElse("ClickRegularity.min-seconds", 45));
        this.cancelChance = config.getDoubleElse("ClickRegularity.cancel-chance", 0.5);
        this.cancelMillis = config.getIntElse("ClickRegularity.cancel-seconds", 120) * 1000L;
    }

    @Override
    public void onPacketReceive(final PacketReceiveEvent event) {
        if (LeftClickProcessor.isAttack(event)) {
            if (System.currentTimeMillis() < limitedUntil && shouldModifyPackets() && ThreadLocalRandom.current().nextDouble() < cancelChance) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
            return;
        }

        if (!isTickPacket(event.getPacketType())) return;

        final LeftClickProcessor tick = player.checkManager.get(LeftClickProcessor.class);
        if (tick.isBlockTick()) {
            dropWindow();
        } else {
            addTick(tick.getClicks(), tick.getAttacks());
        }
    }

    private void addTick(int clicks, int attacks) {
        // Windows start on a click, so the first and last seconds of a fight don't count as a change of rate
        if (windowTicks == 0 && clicks == 0) return;

        emptyTicks = clicks > 0 ? 0 : emptyTicks + 1;
        if (emptyTicks > MAX_EMPTY_TICKS) {
            dropWindow();
            return;
        }

        windowTicks++;
        windowClicks += clicks;
        windowAttacks += attacks;
        if (windowTicks == WINDOW_TICKS) {
            endWindow();
        }
    }

    private void dropWindow() {
        windowTicks = 0;
        windowClicks = 0;
        windowAttacks = 0;
        emptyTicks = 0;
        previousWindowClicks = NO_WINDOW;
    }

    private void endWindow() {
        final int clicks = windowClicks;
        final int attacks = windowAttacks;
        windowTicks = 0;
        windowClicks = 0;
        windowAttacks = 0;

        // Prod logs show runs with no attack from many players at once, of unknown cause, so only windows that hit count
        if (clicks < MIN_WINDOW_CLICKS || attacks == 0) {
            previousWindowClicks = NO_WINDOW;
            return;
        }

        if (previousWindowClicks != NO_WINDOW) {
            regularSeconds = Math.abs(clicks - previousWindowClicks) <= MAX_WINDOW_DIFFERENCE ? regularSeconds + 1 : 0;
            if (regularSeconds >= minRegularSeconds) {
                limitedUntil = System.currentTimeMillis() + cancelMillis;
                if (regularSeconds % minRegularSeconds == 0 && flag(V.write(verbose()).uint(clicks).uint(regularSeconds))) {
                    player.checkManager.get(AutoClickSuspicion.class).alert(clicks);
                }
            }
        }
        previousWindowClicks = clicks;
    }
}
