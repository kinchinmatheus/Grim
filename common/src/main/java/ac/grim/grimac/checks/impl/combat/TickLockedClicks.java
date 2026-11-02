package ac.grim.grimac.checks.impl.combat;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;

// Clickers that run inside the client tick, and macros at exactly 20 CPS, click once in every tick. By hand the rate
// drifts and the timing varies, so a tick with no click or with two comes after a few seconds at most.
// Faster macros click at least twice in every tick. A drag can do that, but has to stop to move the finger back.
@CheckData(name = "AutoClicker", stableKey = "grim.combat.tick_locked_clicks", description = "Clicked in every tick for too long while attacking")
public class TickLockedClicks extends Check implements PacketReceiveListener {
    private static final Verbose V = Verbose.of("cps={uint}, ticks={uint}, attacks={uint}");
    private static final int TICKS_PER_SECOND = 20;
    // 2 seconds. Simulated hand clicking at 18-22 CPS with 20% interval variation reaches it in 5% of 10 second
    // bursts, and 60 ticks in 0.3%, so a ban has to wait for repeated flags.
    private static final int MIN_FLAGGED_TICKS = 40;
    // Players who click at 20 CPS fight for hours, so even a rare run happens to them. Staff only hear about runs far past it
    private static final int MIN_ALERTED_TICKS = 100;
    // In 47 h of prod bursts only one player certainly held this for 10 s: 63 CPS for 18 s
    private static final int MIN_MULTI_CLICK_TICKS = 200;

    private int lockedTicks;
    private int lockedAttacks;
    private int multiClickTicks;
    private int multiClicks;
    private int multiClickAttacks;

    public TickLockedClicks(GrimPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {
        return player.getClientVersion().isOlderThan(ClientVersion.V_1_9);
    }

    @Override
    public void onPacketReceive(final PacketReceiveEvent event) {
        if (!isTickPacket(event.getPacketType())) return;

        final LeftClickProcessor tick = player.checkManager.get(LeftClickProcessor.class);
        final int clicks = tick.isBlockTick() ? 0 : tick.getClicks();

        if (clicks == 1) {
            lockedTicks++;
            lockedAttacks += tick.getAttacks();
        } else {
            endLockedRun();
        }

        if (clicks > 1) {
            multiClickTicks++;
            multiClicks += clicks;
            multiClickAttacks += tick.getAttacks();
        } else {
            endMultiClickRun();
        }
    }

    private void endLockedRun() {
        // Prod logs show runs with no attack from many players at once, of unknown cause, so only runs that hit count
        if (lockedTicks >= MIN_FLAGGED_TICKS && lockedAttacks > 0
                && flag(V.write(verbose()).uint(TICKS_PER_SECOND).uint(lockedTicks).uint(lockedAttacks))
                && lockedTicks >= MIN_ALERTED_TICKS) {
            player.checkManager.get(AutoClickSuspicion.class).alert(TICKS_PER_SECOND);
        }
        lockedTicks = 0;
        lockedAttacks = 0;
    }

    private void endMultiClickRun() {
        if (multiClickTicks >= MIN_MULTI_CLICK_TICKS && multiClickAttacks > 0) {
            final int cps = multiClicks * TICKS_PER_SECOND / multiClickTicks;
            if (flag(V.write(verbose()).uint(cps).uint(multiClickTicks).uint(multiClickAttacks))) {
                player.checkManager.get(AutoClickSuspicion.class).alert(cps);
            }
        }
        multiClickTicks = 0;
        multiClicks = 0;
        multiClickAttacks = 0;
    }
}
