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

// Holding the use button repeats a right click every 4 ticks. FastPlace shortens that delay, so while held it right-clicks
// at one fixed interval: every tick, every 2 ticks or every 3 ticks.
// A right click on a block or with an item sends a block placement or use item packet even when nothing gets placed,
// so a held button shows up as single right clicks at that exact interval. Human clicking varies the interval and lands
// several clicks in some ticks (butterfly, drag).
// Every 3 ticks isn't tracked: 6.7 clicks per second is no faster than normal clicking, and a human clicking about 7 CPS
// lands on that exact interval naturally, so limiting it only cost legit blocks.
// Blocks are cancelled after a short run, which human clicking only reaches by chance and then loses a few blocks.
// Flags need a longer run: every tick, clicking at 16 CPS or more still reaches it now and then, so they should alert,
// not ban. Every 2 ticks is as fast as clicking at 10 CPS, and on dev a human repeated that interval up to 9 times in a
// row, so those runs cancel and flag much later.
@CheckData(name = "FastPlace", stableKey = "grim.scaffolding.fast_place", description = "Right-clicked at the same fast interval for too many clicks in a row")
public class FastPlace extends BlockPlaceCheck implements PacketReceiveListener, BlockPlaceListener {
    private static final Verbose V = Verbose.of("interval={uint}, clicks={uint}");
    private static final int MAX_FAST_INTERVAL_TICKS = 2;
    // Runs flag every this many clicks, at an interval of 1 tick or of 2 ticks
    private static final int FLAG_EVERY_TICK_CLICKS = 10;
    private static final int FLAG_SLOW_CLICKS = 20;

    private int cancelAfterTicks;
    private int slowCancelAfterClicks;
    private int rightClicksThisTick;
    private int ticksSinceRightClick;
    // Ticks between the right clicks of the current run (0 while it has one click), and how many clicks it has
    private int runInterval;
    private int runClicks;

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
        this.slowCancelAfterClicks = config.getIntElse("FastPlace.slow-cancel-after-clicks", 10);
    }

    @Override
    public void onBlockPlace(final BlockPlace place) {
        // Clicking a block with soup or an empty hand sends this packet too
        if (!place.isBlock || ticksSinceRightClick + 1 != runInterval) {
            return;
        }

        final int cancelAfterClicks = runInterval == 1 ? cancelAfterTicks : slowCancelAfterClicks;
        if (runClicks >= cancelAfterClicks && shouldModifyPackets() && shouldCancel()) {
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
        if (rightClicksThisTick == 0) {
            ticksSinceRightClick++;
        } else if (rightClicksThisTick == 1) {
            countRightClick(ticksSinceRightClick + 1);
            ticksSinceRightClick = 0;
        } else {
            runInterval = 0;
            runClicks = 0;
            ticksSinceRightClick = 0;
        }
        rightClicksThisTick = 0;
    }

    private void countRightClick(final int interval) {
        if (runClicks == 0 || interval > MAX_FAST_INTERVAL_TICKS) {
            runInterval = 0;
            runClicks = 1;
            return;
        }

        if (runClicks >= 2 && interval == runInterval) {
            runClicks++;
        } else {
            // A new run, starting at the previous click
            runInterval = interval;
            runClicks = 2;
        }

        final int flagEveryClicks = runInterval == 1 ? FLAG_EVERY_TICK_CLICKS : FLAG_SLOW_CLICKS;
        if (runClicks % flagEveryClicks == 0) {
            flag(V.write(verbose()).uint(runInterval).uint(runClicks));
        }
    }

    @Override
    protected int getDefaultCancelVL() {
        return 0;
    }
}
