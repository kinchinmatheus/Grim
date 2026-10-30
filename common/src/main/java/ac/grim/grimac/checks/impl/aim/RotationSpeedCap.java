package ac.grim.grimac.checks.impl.aim;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.impl.aim.processor.AimProcessor;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.checks.type.RotationListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.LogUtil;
import ac.grim.grimac.utils.anticheat.update.RotationUpdate;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;

import java.util.Arrays;
import java.util.Locale;

// Shadow check while its false positive rate is measured: it records flags in Grim's history and in a console line,
// and is meant to stay out of punishments.yml and flamemc-core's anticheat.conf, so it never alerts, cancels or bans.
//
// Aura rotation managers move each axis by at most a near constant angle per tick. One measured silent aura moves
// 3.71 degrees times its aim speed setting, within a few percent, whatever the mouse sensitivity. While the target
// needs a faster turn, yaw and pitch speeds pile up just under that cap, with few samples right above it. The aura's
// release back to the camera adds larger jumps, so only part of the samples beyond the cap has to stay low.
// Human turn speeds spread out and taper off.
@CheckData(name = "AuraRotation", stableKey = "grim.aim.rotation_speed_cap", description = "Turned at a capped speed while fighting")
public class RotationSpeedCap extends Check implements RotationListener, PacketReceiveListener {
    private static final Verbose V = Verbose.of("speed={f64:%.2f}, window={uint}, beyond={uint}, below={uint}, above={uint}");
    private static final String SHADOW_LOG_PREFIX = "[AuraShadow] ";
    private static final int COMBAT_TICKS = 20;
    private static final double MIN_TURN_DEGREES = 1.5;
    // Pitch clamps at 90 degrees, which cuts a turn short
    private static final double PITCH_LIMIT_DEGREES = 89.9;
    // Large enough to gather the rare capped turns of fast aim speeds
    private static final int SAMPLE_COUNT = 300;
    private static final int SAMPLES_BETWEEN_EVALUATIONS = 50;
    // A cap window spans this fraction of its lowest speed, and must hold at least 1.5 sensitivity steps
    private static final double WINDOW_FRACTION = 0.08;
    private static final double MIN_WINDOW_SENSITIVITY_STEPS = 1.5;
    private static final int MIN_WINDOW_SAMPLES = 10;
    // Share of the samples at or above the window that fall inside it
    private static final double MIN_CEILING_SHARE = 0.6;
    // Samples in the window against samples in the bands of the same width right below and right above it
    private static final double MIN_BELOW_RATIO = 2.0;
    private static final double MIN_ABOVE_RATIO = 3.0;

    private final double[] turnSpeeds = new double[SAMPLE_COUNT];
    private int nextSample;
    private boolean samplesFull;
    private int samplesSinceEvaluation;
    private int tick;
    private int lastAttackTick = -COMBAT_TICKS - 1;

    public RotationSpeedCap(GrimPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {
        // These clients send a movement packet every tick, so movement packets count their ticks. Only they were measured.
        return player.getClientVersion().isOlderThan(ClientVersion.V_1_9);
    }

    @Override
    public void onPacketReceive(final PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY && new WrapperPlayClientInteractEntity(event).getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK
                || event.getPacketType() == PacketType.Play.Client.ATTACK) {
            lastAttackTick = tick;
            return;
        }

        if (WrapperPlayClientPlayerFlying.isFlying(event.getPacketType()) && !player.packetStateData.lastPacketWasTeleport) {
            tick++;
        }
    }

    @Override
    public void process(final RotationUpdate rotationUpdate) {
        if (tick - lastAttackTick > COMBAT_TICKS
                || player.packetStateData.lastPacketWasTeleport
                || player.compensatedEntities.self.getRiding() != null) {
            return;
        }

        // The cap applies to each axis on its own, so pitch turns pile up at the same speed as yaw turns
        addSample(rotationUpdate.deltaYawABS());
        if (Math.abs(rotationUpdate.newPitch()) < PITCH_LIMIT_DEGREES) {
            addSample(rotationUpdate.deltaPitchABS());
        }
    }

    private void addSample(double turnSpeed) {
        if (turnSpeed < MIN_TURN_DEGREES) {
            return;
        }

        turnSpeeds[nextSample] = turnSpeed;
        nextSample = (nextSample + 1) % SAMPLE_COUNT;
        samplesFull |= nextSample == 0;
        samplesSinceEvaluation++;

        if (samplesFull && samplesSinceEvaluation >= SAMPLES_BETWEEN_EVALUATIONS) {
            samplesSinceEvaluation = 0;
            evaluate();
        }
    }

    private void evaluate() {
        double sensitivityStep = player.checkManager.get(AimProcessor.class).modeYaw;
        // Without the sensitivity grid, a coarse grid's repeated step can look like a cap
        if (sensitivityStep <= 0) {
            return;
        }

        double[] sorted = turnSpeeds.clone();
        Arrays.sort(sorted);
        for (int i = 0; i < sorted.length; i++) {
            double windowStart = sorted[i];
            if (i > 0 && sorted[i - 1] == windowStart) continue;

            double width = windowStart * WINDOW_FRACTION;
            if (width < MIN_WINDOW_SENSITIVITY_STEPS * sensitivityStep || windowStart - width < MIN_TURN_DEGREES) continue;

            int inWindow = 0;
            int beyond = 0;
            int belowBand = 0;
            int aboveBand = 0;
            for (double speed : sorted) {
                if (speed > windowStart + width) {
                    beyond++;
                    if (speed <= windowStart + 2 * width) {
                        aboveBand++;
                    }
                } else if (speed >= windowStart) {
                    inWindow++;
                } else if (speed >= windowStart - width) {
                    belowBand++;
                }
            }

            if (inWindow >= MIN_WINDOW_SAMPLES
                    && inWindow >= MIN_CEILING_SHARE * (inWindow + beyond)
                    && inWindow >= MIN_BELOW_RATIO * Math.max(1, belowBand)
                    && inWindow >= MIN_ABOVE_RATIO * Math.max(1, aboveBand)) {
                flagCap(windowStart, inWindow, beyond, belowBand, aboveBand);
                return;
            }
        }
    }

    private void flagCap(double speed, int inWindow, int beyond, int belowBand, int aboveBand) {
        String details = String.format(Locale.ROOT, "speed=%.2f, window=%d, beyond=%d, below=%d, above=%d", speed, inWindow, beyond, belowBand, aboveBand);
        if (flag(V.write(verbose()).f64(speed).uint(inWindow).uint(beyond).uint(belowBand).uint(aboveBand), () -> details)) {
            LogUtil.info(SHADOW_LOG_PREFIX + player.getName() + " failed " + getCheckName() + " (x" + (int) violations + ") " + details);
        }
    }
}
