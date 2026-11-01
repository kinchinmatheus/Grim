package ac.grim.grimac.checks.impl.combat;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.checks.GrimProcessor;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import org.jetbrains.annotations.Nullable;

// Shadow data to calibrate autoclicker detection on real players: summarizes each burst of left clicks into
// ClickStatsLog. It never flags or cancels anything.
public class ClickStatsCollector extends GrimProcessor implements PacketReceiveListener {
    private @Nullable ClickBurst burst;

    public ClickStatsCollector(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(final PacketReceiveEvent event) {
        if (!GrimAPI.INSTANCE.getClickStatsLog().isEnabled() || player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9)) {
            burst = null;
            return;
        }

        if (!isTickPacket(event.getPacketType())) return;

        final LeftClickProcessor tick = player.checkManager.get(LeftClickProcessor.class);
        // Clicks on blocks end the burst instead of joining it
        if (tick.isBlockTick()) {
            finishBurst();
            return;
        }

        if (burst == null) {
            if (tick.getClicks() == 0) return;
            burst = new ClickBurst(System.currentTimeMillis());
        }

        burst.addTick(tick.getClicks(), tick.getAttacks());
        if (burst.isOver()) {
            finishBurst();
        }
    }

    private void finishBurst() {
        if (burst != null && burst.isWorthRecording()) {
            GrimAPI.INSTANCE.getClickStatsLog().record(burst.toRecord(player.getName(), player.getClientVersion().getReleaseName(), player.getTransactionPing()));
        }
        burst = null;
    }
}
