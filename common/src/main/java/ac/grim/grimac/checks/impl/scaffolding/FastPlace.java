package ac.grim.grimac.checks.impl.scaffolding;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.BlockPlaceCheck;
import ac.grim.grimac.checks.type.BlockPlaceListener;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.BlockPlace;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import org.jetbrains.annotations.NotNull;

// Holding the use button repeats a right click every 4 ticks; FastPlace removes that delay and right-clicks in every tick.
// A right click on a block or with an item sends a block placement or use item packet even when nothing gets placed,
// so FastPlace sends exactly one in every tick, while fast human clicking (butterfly, drag) leaves ticks with zero or several.
// Blocks are cancelled after a short run, which fast clickers only reach by chance and then lose a few blocks.
// Flags need a longer run, which clicking at 16 CPS or more still reaches now and then, so they should alert, not ban.
@CheckData(name = "FastPlace", stableKey = "grim.scaffolding.fast_place", description = "Right-clicked exactly once per tick for too many ticks in a row")
public class FastPlace extends BlockPlaceCheck implements PacketReceiveListener, BlockPlaceListener {
    private static final Verbose V = Verbose.of("ticks={uint}");
    // Flags at this many ticks and again after each further interval of the same run
    private static final int FLAG_INTERVAL_TICKS = 10;

    private int cancelAfterTicks;
    private int rightClicksThisTick;
    private int singleRightClickTicks;

    public FastPlace(GrimPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {
        // These clients send a movement packet every tick, so movement packets count their ticks
        return player.getClientVersion().isOlderThan(ClientVersion.V_1_9);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        super.onReload(config);
        this.cancelAfterTicks = config.getIntElse("FastPlace.cancel-after-ticks", 4);
    }

    @Override
    public void onBlockPlace(final BlockPlace place) {
        // Clicking a block with soup or an empty hand sends this packet too
        if (place.isBlock && singleRightClickTicks >= cancelAfterTicks && shouldModifyPackets() && shouldCancel()) {
            place.resync();
        }
    }

    @Override
    public void onPacketReceive(final PacketReceiveEvent event) {
        // Cancelled packets get here too, so cancelling a block doesn't break the run
        if (event.getPacketType() == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT || event.getPacketType() == PacketType.Play.Client.USE_ITEM) {
            rightClicksThisTick++;
            return;
        }

        if (!isTickPacket(event.getPacketType())) {
            return;
        }

        // A tick's right clicks arrive before its movement packet
        if (rightClicksThisTick == 1) {
            singleRightClickTicks++;
            if (singleRightClickTicks % FLAG_INTERVAL_TICKS == 0) {
                flag(V.write(verbose()).uint(singleRightClickTicks));
            }
        } else {
            singleRightClickTicks = 0;
        }
        rightClicksThisTick = 0;
    }

    @Override
    protected int getDefaultCancelVL() {
        return 0;
    }
}
