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

// Hands pause. Clicking by hand on dev, nobody went past 16.6 s without a gap of 5 ticks, and some clickers reached 47 s.
// Clickers with strong randomization pause as often as hands, so this misses them. On prod, 10 of 1144 fighters passed
// 30 s in 47 h of recorded bursts.
@CheckData(name = "NonStopClicks", stableKey = "grim.combat.non_stop_clicks", description = "Clicked for too long without a pause while attacking")
public class NonStopClicks extends Check implements PacketReceiveListener {
    private static final Verbose V = Verbose.of("cps={uint}, seconds={uint}");
    private static final int TICKS_PER_SECOND = 20;
    private static final int MIN_CPS = 10;
    // A gap of 5 ticks between two clicks has 4 empty ticks
    private static final int MAX_EMPTY_TICKS = 3;

    private int minTicks;

    private int stretchTicks;
    private int stretchClicks;
    private int stretchAttacks;
    private int emptyTicks;

    public NonStopClicks(GrimPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {
        return player.getClientVersion().isOlderThan(ClientVersion.V_1_9);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        super.onReload(config);
        this.minTicks = Math.max(1, config.getIntElse("NonStopClicks.min-seconds", 30)) * TICKS_PER_SECOND;
    }

    @Override
    public void onPacketReceive(final PacketReceiveEvent event) {
        if (!isTickPacket(event.getPacketType())) return;

        final LeftClickProcessor tick = player.checkManager.get(LeftClickProcessor.class);
        if (tick.isBlockTick()) {
            endStretch();
            return;
        }

        if (tick.getClicks() > 0) {
            emptyTicks = 0;
            stretchClicks += tick.getClicks();
            stretchAttacks += tick.getAttacks();
        } else {
            if (stretchTicks == 0) return;
            if (++emptyTicks > MAX_EMPTY_TICKS) {
                endStretch();
                return;
            }
        }

        if (++stretchTicks % minTicks == 0) {
            flagStretch();
        }
    }

    private void flagStretch() {
        final int cps = stretchClicks * TICKS_PER_SECOND / stretchTicks;
        if (cps < MIN_CPS || stretchAttacks == 0) return;

        if (flag(V.write(verbose()).uint(cps).uint(stretchTicks / TICKS_PER_SECOND))) {
            player.checkManager.get(AutoClickSuspicion.class).alert(cps);
        }
    }

    private void endStretch() {
        stretchTicks = 0;
        stretchClicks = 0;
        stretchAttacks = 0;
        emptyTicks = 0;
    }
}
