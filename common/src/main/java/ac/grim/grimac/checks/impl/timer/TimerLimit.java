package ac.grim.grimac.checks.impl.timer;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import org.jetbrains.annotations.NotNull;

// This works around 1.3 timer, to prevent too high abuse - maybe there's a better solution?
@CheckData(name = "TimerLimit", stableKey = "grim.timer.limit", description = "The player has sent too many packets after high latency", setback = 10)
public class TimerLimit extends Timer {

    // At what ping should we start to limit the balance advantage? (nanos)
    private long limitAbuseOverPing;

    public TimerLimit(GrimPlayer player) {
        super(player);
    }

    @Override
    public void doCheck(final PacketReceiveEvent event) {
        // 1:1 with Timer minus cancelling the packet
        if (timerBalanceRealTime > System.nanoTime()) {
            // If timer check already flagged, don't flag.
            // AFK players send bursts of still ticks after lag spikes; the only action here is a position setback,
            // which can't take anything from a tick that didn't move, so still ticks only spend the balance
            if (!event.isCancelled() && !isStillTick(event)) {
                if (flag() && shouldSetback()) {
                    player.getSetbackTeleportUtil().executeNonSimulatingSetback();
                }
            }

            // Reset the violation by 1 movement
            timerBalanceRealTime -= 50e6;
        }

        limitFallBehind();
    }

    // Runs before prediction, so the last claimed position and the rotation still belong to the previous tick
    private boolean isStillTick(final PacketReceiveEvent event) {
        // A 1.21.2+ tick end without a movement packet
        if (!WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) return true;

        WrapperPlayClientPlayerFlying flying = new WrapperPlayClientPlayerFlying(event);
        Location location = flying.getLocation();
        if (flying.hasPositionChanged() && !location.getPosition().equals(player.packetStateData.lastClaimedPosition)) {
            return false;
        }

        return !flying.hasRotationChanged() || location.getYaw() == player.yaw && location.getPitch() == player.pitch;
    }

    @Override
    protected void limitFallBehind() {
        // Limit using transaction ping if over 1000ms (default)
        long playerClock = lastMovementPlayerClock;
        if (limitAbuseOverPing != -1 && System.nanoTime() - playerClock > limitAbuseOverPing) {
            playerClock = System.nanoTime() - limitAbuseOverPing;
        }
        timerBalanceRealTime = Math.max(timerBalanceRealTime, playerClock - clockDrift);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        super.onReload(config);
        limitAbuseOverPing = config.getLongElse(getConfigName() + ".ping-abuse-limit-threshold", 1000L);
        if (limitAbuseOverPing != -1) {
            limitAbuseOverPing *= (long) 1e6;
        }
    }
}
