package ac.grim.grimac.checks.impl.combat;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.checks.GrimProcessor;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import org.jetbrains.annotations.Nullable;

// Shadow data to calibrate autoclicker detection on real players: summarizes each burst of left clicks into
// ClickStatsLog. It never flags or cancels anything.
public class ClickStatsCollector extends GrimProcessor implements PacketReceiveListener {
    private int swingsThisTick;
    private int attacksThisTick;
    private boolean digActionThisTick;
    // Holding the button on a block swings every tick, and those swings are not clicks
    private boolean holdingDig;
    private @Nullable ClickBurst burst;

    public ClickStatsCollector(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(final PacketReceiveEvent event) {
        // These clients send a movement packet every tick, so movement packets count their ticks
        if (!GrimAPI.INSTANCE.getClickStatsLog().isEnabled() || player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9)) {
            burst = null;
            return;
        }

        final PacketTypeCommon packetType = event.getPacketType();
        if (isAnimation(packetType)) {
            swingsThisTick++;
            return;
        }

        if (packetType == PacketType.Play.Client.INTERACT_ENTITY && new WrapperPlayClientInteractEntity(event).getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK
                || packetType == PacketType.Play.Client.ATTACK) {
            attacksThisTick++;
            return;
        }

        if (packetType == PacketType.Play.Client.PLAYER_DIGGING) {
            switch (new WrapperPlayClientPlayerDigging(event).getAction()) {
                case START_DIGGING -> {
                    digActionThisTick = true;
                    holdingDig = true;
                }
                case CANCELLED_DIGGING, FINISHED_DIGGING -> {
                    digActionThisTick = true;
                    holdingDig = false;
                }
                default -> {}
            }
            return;
        }

        if (isTickPacket(packetType)) {
            endTick();
        }
    }

    private void endTick() {
        final int swings = swingsThisTick;
        final int attacks = attacksThisTick;
        final boolean digAction = digActionThisTick;
        swingsThisTick = 0;
        attacksThisTick = 0;
        digActionThisTick = false;

        // A tick without swings means the button was released, even after a block broke at once and no cancel came
        if (!digAction && swings == 0) {
            holdingDig = false;
        }

        // Clicks on blocks are presses mixed with held swings, so they end the burst instead of joining it
        if (digAction || holdingDig) {
            finishBurst();
            return;
        }

        if (burst == null) {
            if (swings == 0) return;
            burst = new ClickBurst(System.currentTimeMillis());
        }

        burst.addTick(swings, attacks);
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
