package ac.grim.grimac.checks.impl.combat;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.checks.GrimProcessor;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.item.type.ItemType;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import org.jetbrains.annotations.Nullable;

// Shadow data to calibrate autoclicker detection on real players: summarizes each burst of left clicks into
// ClickStatsLog. It never flags or cancels anything.
public class ClickStatsCollector extends GrimProcessor implements PacketReceiveListener {
    private static final int HOTBAR_SIZE = 9;
    // A wheel moves to the next slot, and the next change comes soon after. Number keys in sequence look the same
    private static final int MAX_SCROLL_STEP_TICKS = 4;

    private @Nullable ClickBurst burst;
    private int slotChanges;
    private int scrollSteps;
    private int rightClicks;
    private int nonWeaponClicks;
    private int playerAttacks;
    private int lastSlot;
    private int ticksSinceSlotChange;

    public ClickStatsCollector(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(final PacketReceiveEvent event) {
        if (!GrimAPI.INSTANCE.getClickStatsLog().isEnabled() || player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9)) {
            burst = null;
            return;
        }

        final PacketTypeCommon packetType = event.getPacketType();
        if (!isTickPacket(packetType)) {
            countAction(event, packetType);
            return;
        }

        addTick(player.checkManager.get(LeftClickProcessor.class));
        slotChanges = 0;
        scrollSteps = 0;
        rightClicks = 0;
        nonWeaponClicks = 0;
        playerAttacks = 0;
        ticksSinceSlotChange++;
    }

    private void countAction(PacketReceiveEvent event, PacketTypeCommon packetType) {
        if (isAnimation(packetType)) {
            if (!isWeapon(player.inventory.getHeldItem().getType())) nonWeaponClicks++;
        } else if (LeftClickProcessor.isAttack(event)) {
            final PacketEntity target = player.compensatedEntities.getEntity(new WrapperPlayClientInteractEntity(event).getEntityId());
            if (target != null && target.getType() == EntityTypes.PLAYER) playerAttacks++;
        } else if (packetType == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            countSlotChange(new WrapperPlayClientHeldItemChange(event).getSlot());
        } else if (packetType == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT || packetType == PacketType.Play.Client.USE_ITEM) {
            rightClicks++;
        }
    }

    private static boolean isWeapon(ItemType type) {
        return type.hasAttribute(ItemTypes.ItemAttribute.SWORD) || type.hasAttribute(ItemTypes.ItemAttribute.AXE);
    }

    private void countSlotChange(int slot) {
        slotChanges++;
        final int steps = Math.abs(slot - lastSlot);
        if ((steps == 1 || steps == HOTBAR_SIZE - 1) && ticksSinceSlotChange <= MAX_SCROLL_STEP_TICKS) {
            scrollSteps++;
        }
        lastSlot = slot;
        ticksSinceSlotChange = 0;
    }

    private void addTick(LeftClickProcessor tick) {
        // Clicks on blocks end the burst instead of joining it
        if (tick.isBlockTick()) {
            finishBurst();
            return;
        }

        if (burst == null) {
            if (tick.getClicks() == 0) return;
            burst = new ClickBurst(System.currentTimeMillis());
        }

        burst.addActions(slotChanges, scrollSteps, rightClicks, nonWeaponClicks, playerAttacks);
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
