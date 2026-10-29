package ac.grim.grimac.checks.impl.combat;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.LogUtil;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;

// Shadow check while its false positive rate is measured: it records flags in Grim's history and in a console line,
// and is meant to stay out of punishments.yml and flamemc-core's anticheat.conf, so it never alerts, cancels or bans.
//
// Auras attack on a schedule; at 20 attacks per second that is exactly one attack in every client tick.
// Fast human clicking lands zero or several clicks in some ticks.
@CheckData(name = "AttackCadence", stableKey = "grim.combat.attack_cadence", description = "Attacked exactly once per tick for too many ticks in a row")
public class AttackCadence extends Check implements PacketReceiveListener {
    private static final Verbose V = Verbose.of("ticks={uint}");
    private static final String SHADOW_LOG_PREFIX = "[AuraShadow] ";
    private static final int MIN_FLAGGED_TICKS = 5;

    private int attacksThisTick;
    private int singleAttackTicks;

    public AttackCadence(GrimPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {
        // These clients send a movement packet every tick, so movement packets count their ticks
        return player.getClientVersion().isOlderThan(ClientVersion.V_1_9);
    }

    @Override
    public void onPacketReceive(final PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY && new WrapperPlayClientInteractEntity(event).getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK
                || event.getPacketType() == PacketType.Play.Client.ATTACK) {
            attacksThisTick++;
            return;
        }

        if (!WrapperPlayClientPlayerFlying.isFlying(event.getPacketType()) || player.packetStateData.lastPacketWasTeleport) {
            return;
        }

        // A tick's attacks arrive before its movement packet
        if (attacksThisTick == 1) {
            singleAttackTicks++;
        } else {
            if (singleAttackTicks >= MIN_FLAGGED_TICKS) {
                flagRun(singleAttackTicks);
            }
            singleAttackTicks = 0;
        }
        attacksThisTick = 0;
    }

    private void flagRun(int ticks) {
        String details = "ticks=" + ticks;
        if (flag(V.write(verbose()).uint(ticks), () -> details)) {
            LogUtil.info(SHADOW_LOG_PREFIX + player.getName() + " failed " + getCheckName() + " (x" + (int) violations + ") " + details);
        }
    }
}
