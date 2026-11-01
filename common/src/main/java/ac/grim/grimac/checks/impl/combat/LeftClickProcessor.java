package ac.grim.grimac.checks.impl.combat;

import ac.grim.grimac.checks.GrimProcessor;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import lombok.Getter;

// Left clicks of each client tick, for clients older than 1.9: they handle clicks inside their tick and send a movement
// packet every tick. Checks registered after this processor read the tick that just ended on the same tick packet.
public final class LeftClickProcessor extends GrimProcessor implements PacketReceiveListener {
    private int swingsThisTick;
    private int attacksThisTick;
    private boolean digActionThisTick;
    // Holding the button on a block swings every tick, and those swings are not clicks
    private boolean holdingDig;

    @Getter
    private int clicks;
    @Getter
    private int attacks;
    // Clicks on blocks are presses mixed with held swings, so a tick with any of them can't be read as clicks
    @Getter
    private boolean blockTick;

    public LeftClickProcessor(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(final PacketReceiveEvent event) {
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9)) return;

        final PacketTypeCommon packetType = event.getPacketType();
        if (isAnimation(packetType)) {
            swingsThisTick++;
            return;
        }

        if (isAttack(event)) {
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

    static boolean isAttack(final PacketReceiveEvent event) {
        final PacketTypeCommon packetType = event.getPacketType();
        return packetType == PacketType.Play.Client.INTERACT_ENTITY && new WrapperPlayClientInteractEntity(event).getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK
                || packetType == PacketType.Play.Client.ATTACK;
    }

    private void endTick() {
        // A tick without swings means the button was released, even after a block broke at once and no cancel came
        if (!digActionThisTick && swingsThisTick == 0) {
            holdingDig = false;
        }

        clicks = swingsThisTick;
        attacks = attacksThisTick;
        blockTick = digActionThisTick || holdingDig;
        swingsThisTick = 0;
        attacksThisTick = 0;
        digActionThisTick = false;
    }
}
