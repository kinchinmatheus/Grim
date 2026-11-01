package ac.grim.grimac.checks.impl.inventory;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import org.jetbrains.annotations.NotNull;

// Autosoup cheats switch to a soup, eat it and switch back to the sword inside one client tick.
// A 1.8 client changes its held slot (scroll wheel, number keys) before it runs the attack and use clicks of the tick,
// and every click sends the new slot right before itself, so no slot change can follow a soup eaten in the same tick.
// The soup is already eaten when the switch back shows the cheat, so the soups that follow a flag are cancelled instead.
@CheckData(name = "AutoSoup", stableKey = "grim.inventory.auto_soup", description = "Switched the held item after eating soup in the same tick")
public class AutoSoup extends Check implements PacketReceiveListener {
    private static final Verbose V = Verbose.of("slot={sint}");

    private long cancelSoupsMillis;
    private long cancelSoupsUntil;
    private boolean ateSoupThisTick;

    public AutoSoup(GrimPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {
        // The order above was read from the 1.8.9 client
        return player.getClientVersion() == ClientVersion.V_1_8;
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        super.onReload(config);
        this.cancelSoupsMillis = config.getIntElse("AutoSoup.cancel-seconds", 5) * 1000L;
    }

    @Override
    public void onPacketReceive(final PacketReceiveEvent event) {
        final PacketTypeCommon packetType = event.getPacketType();
        if (isTickPacket(packetType)) {
            ateSoupThisTick = false;
            return;
        }

        if (packetType == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT || packetType == PacketType.Play.Client.USE_ITEM) {
            if (player.inventory.getHeldItem().getType() != ItemTypes.MUSHROOM_STEW) {
                return;
            }

            ateSoupThisTick = true;
            if (System.currentTimeMillis() < cancelSoupsUntil && shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
            return;
        }

        if (packetType == PacketType.Play.Client.HELD_ITEM_CHANGE && ateSoupThisTick
                && flag(V.write(verbose()).sint(new WrapperPlayClientHeldItemChange(event).getSlot()))) {
            cancelSoupsUntil = System.currentTimeMillis() + cancelSoupsMillis;
        }
    }
}
