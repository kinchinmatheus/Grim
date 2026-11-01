package ac.grim.grimac.checks.impl.inventory;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClientStatus;
import org.jetbrains.annotations.NotNull;

// Refill and inventory cheats click in the inventory where a 1.8 client never does.
// The client runs its inventory clicks at the start of a tick, before the movement packet that ends it, and answers
// transactions only between ticks. A click after the movement packet and before a transaction came from outside the
// tick: from a render or tick end handler of a mod (Mouse Tweaks) or a cheat module.
// The client also tells the server every time it opens its inventory, so a click there while it is closed comes from a
// refill that never opened it.
// A click outside the tick is only known once the transaction arrives, after it went through, so the clicks that
// follow a flag are cancelled instead.
@CheckData(name = "AutoRefill", stableKey = "grim.inventory.auto_refill", description = "Clicked in the inventory outside the client tick or while it was closed")
public class AutoRefill extends Check implements PacketReceiveListener {
    // Shape index == kind constant value
    private static final Verbose V = Verbose
            .of("outside tick, window={sint}, slot={sint}")
            .or("inventory closed, slot={sint}");
    private static final int KIND_OUTSIDE_TICK = 0;
    private static final int KIND_INVENTORY_CLOSED = 1;
    private static final int PLAYER_INVENTORY_WINDOW = 0;

    private long cancelClicksMillis;
    private long cancelClicksUntil;
    private boolean inventoryOpen;
    private boolean sentFlying;
    // The first click after the movement packet, flagged when a transaction arrives before the next movement packet
    private boolean clickedAfterFlying;
    private int windowClickedAfterFlying;
    private int slotClickedAfterFlying;

    public AutoRefill(GrimPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {
        // The click order and the inventory open packet were read from the 1.8.9 client
        return player.getClientVersion() == ClientVersion.V_1_8;
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        super.onReload(config);
        this.cancelClicksMillis = config.getIntElse("AutoRefill.cancel-seconds", 5) * 1000L;
    }

    // Dying or changing worlds replaces the inventory screen without the client closing it
    public void onRespawn() {
        inventoryOpen = false;
    }

    @Override
    public void onPacketReceive(final PacketReceiveEvent event) {
        final PacketTypeCommon packetType = event.getPacketType();
        if (isTickPacket(packetType)) {
            sentFlying = true;
            clickedAfterFlying = false;
            return;
        }

        if (isTransaction(packetType) && player.packetStateData.lastTransactionPacketWasValid) {
            if (sentFlying && clickedAfterFlying
                    && flag(V.write(verbose(), KIND_OUTSIDE_TICK).sint(windowClickedAfterFlying).sint(slotClickedAfterFlying))) {
                cancelClicksUntil = System.currentTimeMillis() + cancelClicksMillis;
            }
            sentFlying = false;
            clickedAfterFlying = false;
            return;
        }

        if (packetType == PacketType.Play.Client.CLIENT_STATUS) {
            if (new WrapperPlayClientClientStatus(event).getAction() == WrapperPlayClientClientStatus.Action.OPEN_INVENTORY_ACHIEVEMENT) {
                inventoryOpen = true;
            }
            return;
        }

        if (packetType == PacketType.Play.Client.CLOSE_WINDOW) {
            inventoryOpen = false;
            return;
        }

        if (packetType != PacketType.Play.Client.CLICK_WINDOW) {
            return;
        }

        final WrapperPlayClientClickWindow click = new WrapperPlayClientClickWindow(event);
        if (sentFlying && !clickedAfterFlying) {
            clickedAfterFlying = true;
            windowClickedAfterFlying = click.getWindowId();
            slotClickedAfterFlying = click.getSlot();
        }

        if (click.getWindowId() == PLAYER_INVENTORY_WINDOW && !inventoryOpen
                && flag(V.write(verbose(), KIND_INVENTORY_CLOSED).sint(click.getSlot()))) {
            cancelClicksUntil = System.currentTimeMillis() + cancelClicksMillis;
        }

        if (System.currentTimeMillis() >= cancelClicksUntil || !shouldModifyPackets()) {
            return;
        }

        event.setCancelled(true);
        player.onPacketCancel();
        // The client already moved the items on its screen
        if (player.platformPlayer != null) {
            GrimAPI.INSTANCE.getScheduler().getEntityScheduler().execute(player.platformPlayer, GrimAPI.INSTANCE.getGrimPlugin(),
                    () -> player.platformPlayer.updateInventory(), null, 0);
        }
    }
}
