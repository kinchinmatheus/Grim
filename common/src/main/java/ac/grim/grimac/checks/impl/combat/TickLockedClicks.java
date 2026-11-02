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
@CheckData(name = "AutoClicker", stableKey = "grim.combat.tick_locked_clicks", description = "Clicked exactly once per tick for too many ticks in a row while attacking")
public class TickLockedClicks extends Check implements PacketReceiveListener {
    private static final Verbose V = Verbose.of("cps=20, ticks={uint}, attacks={uint}");
    // 2 seconds. Simulated hand clicking at 18-22 CPS with 20% interval variation reaches it in 5% of 10 second
    // bursts, and 60 ticks in 0.3%, so a ban has to wait for repeated flags.
    private static final int MIN_FLAGGED_TICKS = 40;

    private int lockedTicks;
    private int attacks;

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
        if (tick.getClicks() == 1 && !tick.isBlockTick()) {
            lockedTicks++;
            attacks += tick.getAttacks();
            return;
        }

        // Prod logs show runs with no attack from many players at once, of unknown cause, so only runs that hit count
        if (lockedTicks >= MIN_FLAGGED_TICKS && attacks > 0) {
            flag(V.write(verbose()).uint(lockedTicks).uint(attacks));
        }
        lockedTicks = 0;
        attacks = 0;
    }
}
