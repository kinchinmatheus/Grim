package ac.grim.grimac.checks.impl.combat;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.impl.aim.RotationSpeedCap;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.LogUtil;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemType;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfo;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

// Only players that RotationSpeedCap or AttackCadence keep flagging are tested. While they fight and look at their
// target, a fake armored player is shown to them alone, behind or beside them on the side away from the target, and
// closer than it. An aura picks the closest target every tick: on the next tick its sent look starts turning to the
// bait at its capped speed, away from the real target. A player cannot react to what they cannot see that fast.
// Reacting to 3 of the last 5 baits flags.
@CheckData(name = "AuraBot", stableKey = "grim.combat.aura_bait", description = "Turned toward a fake player out of view")
public class AuraBait extends Check implements PacketReceiveListener {
    private static final Verbose V = Verbose.of("reactions={uint}, baits={uint}");
    private static final String BAIT_LOG_PREFIX = "[AuraBot] ";
    private static final int COMBAT_TICKS = 20;

    private static final long SUSPICION_WINDOW_MS = TimeUnit.MINUTES.toMillis(3);
    private static final long SUSPICION_DURATION_MS = TimeUnit.MINUTES.toMillis(5);
    private static final int MIN_CADENCE_FLAGS = 2;
    // One pile of capped turns stays in RotationSpeedCap's buffer for up to 6 evaluations
    private static final int MIN_SPEED_CAP_FLAGS = 7;
    private static final long MIN_TIME_BETWEEN_BAITS_MS = TimeUnit.SECONDS.toMillis(8);
    private static final long MAX_TIME_BETWEEN_BAITS_MS = TimeUnit.SECONDS.toMillis(20);
    private static final long BLOCKED_BAIT_RETRY_MS = TimeUnit.SECONDS.toMillis(1);

    // A turn to the bait only moves away from the target while the look starts on the target
    private static final double MAX_TARGET_ANGLE = 30;
    // Degrees from the look, on the side away from the target. Past 165 a turn either way closes the gap, so it shows
    // no direction.
    private static final double MIN_BAIT_ANGLE = 90;
    private static final double MAX_BAIT_ANGLE = 165;
    private static final double MAX_BAIT_DISTANCE = 2.0;
    private static final double MIN_BAIT_DISTANCE = 1.0;
    private static final double CLOSER_THAN_TARGET = 0.5;
    private static final double BAIT_EYE_HEIGHT = 1.62;
    private static final double SIGHT_STEP = 0.1;
    private static final double FEET_CLEARANCE = 0.1;
    // Counted from the client's confirmation that it received the bait
    private static final int BAIT_LIFETIME_TICKS = 10;
    private static final int MAX_UNCONFIRMED_TICKS = 40;
    // Far above the ids the server hands out
    private static final int BAIT_ENTITY_IDS = 100_000;
    private static final int LOWEST_BAIT_ENTITY_ID = Integer.MAX_VALUE - BAIT_ENTITY_IDS + 1;
    private static final String NAME_CHARACTERS = "abcdefghijklmnopqrstuvwxyz0123456789_";
    private static final int NAME_LENGTH = 10;
    private static final int BAIT_PING = 50;
    private static final float BAIT_HEALTH = 20;
    private static final byte ALL_SKIN_PARTS = 0x7F;

    // The look is compared between the client tick that received the bait and the third one after it
    private static final int REACTION_TICK = 4;
    // A player already turning that way before the bait arrived proves nothing
    private static final double MAX_TURN_BEFORE_BAIT = 5;
    private static final double MIN_TURN_TO_BAIT = 20;
    private static final double MIN_TURN_FROM_TARGET = 15;
    private static final int RESULTS_KEPT = 5;
    private static final int MIN_REACTIONS = 3;
    private static final int NO_BAIT = -1;
    private static final int NOT_CONFIRMED = -1;

    private final RecentFlags speedCapFlags = new RecentFlags();
    private final RecentFlags cadenceFlags = new RecentFlags();
    private final ArrayDeque<Boolean> results = new ArrayDeque<>(RESULTS_KEPT + 1);
    private long suspicionEndTime;
    private long nextBaitTime;
    private int tick;
    private int lastAttackTick = -COMBAT_TICKS - 1;
    private int targetId = -1;

    private int baitEntityId = NO_BAIT;
    private int baitCount;
    private UserProfile baitProfile;
    private double baitAngle;
    private double baitOffsetX;
    private double baitOffsetZ;
    private int sentTick;
    private int confirmedTick;
    private int baitHits;
    // NaN until the look is measured on the client tick that received the bait
    private double startBaitAngle;
    private double startTargetAngle;
    // Null while the bait proves nothing: not measured, or the target was lost
    private Boolean reacted;

    public AuraBait(GrimPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {
        // Only these clients were measured, and they send a movement packet every tick
        return player.getClientVersion() == ClientVersion.V_1_8;
    }

    @Override
    public void onPacketReceive(final PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            onInteract(event, new WrapperPlayClientInteractEntity(event));
            return;
        }

        if (!WrapperPlayClientPlayerFlying.isFlying(event.getPacketType()) || player.packetStateData.lastPacketWasTeleport) {
            return;
        }

        tick++;
        long now = System.currentTimeMillis();
        updateSuspicion(now);
        if (baitEntityId != NO_BAIT) {
            onBaitTick(now);
        } else if (now < suspicionEndTime && now >= nextBaitTime && tick - lastAttackTick <= COMBAT_TICKS) {
            trySpawnBait(now);
        }
    }

    private void onInteract(PacketReceiveEvent event, WrapperPlayClientInteractEntity packet) {
        boolean attack = packet.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK;
        if (packet.getEntityId() >= LOWEST_BAIT_ENTITY_ID) {
            // Only this client knows the bait
            event.setCancelled(true);
            if (attack) baitHits++;
            return;
        }

        if (attack) {
            lastAttackTick = tick;
            targetId = packet.getEntityId();
        }
    }

    private void updateSuspicion(long now) {
        boolean speedCapFlagged = speedCapFlags.update(player.checkManager.get(RotationSpeedCap.class), now);
        boolean cadenceFlagged = cadenceFlags.update(player.checkManager.get(AttackCadence.class), now);
        if (!speedCapFlagged && !cadenceFlagged) return;

        if (speedCapFlags.count() >= MIN_SPEED_CAP_FLAGS || cadenceFlags.count() >= MIN_CADENCE_FLAGS
                || (speedCapFlags.count() > 0 && cadenceFlags.count() > 0)) {
            if (now >= suspicionEndTime) {
                nextBaitTime = now + randomTimeBetweenBaits();
            }
            suspicionEndTime = now + SUSPICION_DURATION_MS;
        }
    }

    private void trySpawnBait(long now) {
        PacketEntity target = player.compensatedEntities.entityMap.get(targetId);
        if (target == null || target.getType() != EntityTypes.PLAYER) return;

        double targetAngle = angleToTarget(target);
        if (Math.abs(targetAngle) > MAX_TARGET_ANGLE) return;

        double angle = ThreadLocalRandom.current().nextDouble(MIN_BAIT_ANGLE, MAX_BAIT_ANGLE) * (targetAngle > 0 ? -1 : 1);
        double distance = Math.max(MIN_BAIT_DISTANCE, Math.min(MAX_BAIT_DISTANCE, horizontalDistanceTo(boxCenter(target)) - CLOSER_THAN_TARGET));
        double direction = Math.toRadians(player.yaw + angle);
        baitOffsetX = -Math.sin(direction) * distance;
        baitOffsetZ = Math.cos(direction) * distance;

        // Auras can skip targets behind walls
        if (!canSeeBait()) {
            nextBaitTime = now + BLOCKED_BAIT_RETRY_MS;
            return;
        }
        spawnBait(angle);
    }

    // Like the vanilla line of sight test, from the eyes to the bait's eyes, with any solid block blocking it
    private boolean canSeeBait() {
        double startY = player.y + player.getEyeHeight();
        double dy = player.y + BAIT_EYE_HEIGHT - startY;
        int steps = (int) Math.ceil(Math.sqrt(baitOffsetX * baitOffsetX + dy * dy + baitOffsetZ * baitOffsetZ) / SIGHT_STEP);
        for (int i = 0; i <= steps; i++) {
            double fraction = (double) i / steps;
            if (isSolid(player.x + baitOffsetX * fraction, startY + dy * fraction, player.z + baitOffsetZ * fraction)) return false;
        }
        return !isSolid(player.x + baitOffsetX, player.y + FEET_CLEARANCE, player.z + baitOffsetZ);
    }

    private boolean isSolid(double x, double y, double z) {
        return player.compensatedWorld.getBlock((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)).getType().isSolid();
    }

    // Sent through the normal pipeline, so Grim tracks the bait like any entity and handles hits on it
    private void spawnBait(double angle) {
        baitCount++;
        baitEntityId = Integer.MAX_VALUE - baitCount % BAIT_ENTITY_IDS;
        baitProfile = new UserProfile(UUID.randomUUID(), randomName());
        baitAngle = angle;
        sentTick = tick;
        confirmedTick = NOT_CONFIRMED;
        baitHits = 0;
        startBaitAngle = Double.NaN;
        reacted = null;

        player.user.writePacket(new WrapperPlayServerPlayerInfo(WrapperPlayServerPlayerInfo.Action.ADD_PLAYER,
                new WrapperPlayServerPlayerInfo.PlayerData(null, baitProfile, GameMode.SURVIVAL, BAIT_PING)));
        player.user.writePacket(new WrapperPlayServerSpawnPlayer(baitEntityId, baitProfile.getUUID(), baitPosition(), baitYaw(), 0,
                Arrays.<EntityData<?>>asList(
                        new EntityData<>(0, EntityDataTypes.BYTE, (byte) 0),
                        new EntityData<>(6, EntityDataTypes.FLOAT, BAIT_HEALTH),
                        new EntityData<>(10, EntityDataTypes.BYTE, ALL_SKIN_PARTS))));
        // Auras can skip players without armor
        equip(EquipmentSlot.MAIN_HAND, ItemTypes.IRON_SWORD);
        equip(EquipmentSlot.HELMET, ItemTypes.IRON_HELMET);
        equip(EquipmentSlot.CHEST_PLATE, ItemTypes.IRON_CHESTPLATE);
        equip(EquipmentSlot.LEGGINGS, ItemTypes.IRON_LEGGINGS);
        equip(EquipmentSlot.BOOTS, ItemTypes.IRON_BOOTS);

        // The client answers this transaction right after it processed the bait
        player.sendTransaction();
        int bait = baitCount;
        player.latencyUtils.addRealTimeTask(player.lastTransactionSent.get(), () -> {
            if (bait == baitCount) confirmedTick = tick;
        });
        player.user.flushPackets();
    }

    private void equip(EquipmentSlot slot, ItemType item) {
        // Before 1.16 the packet carries one slot
        player.user.writePacket(new WrapperPlayServerEntityEquipment(baitEntityId,
                Collections.singletonList(new Equipment(slot, ItemStack.builder().type(item).amount(1).build()))));
    }

    private void onBaitTick(long now) {
        // A still bait falls behind a player chasing their target, and the aura goes back to the closer target
        player.user.writePacket(new WrapperPlayServerEntityTeleport(baitEntityId, baitPosition(), baitYaw(), 0, true));
        player.sendTransaction();
        player.user.flushPackets();

        if (confirmedTick == NOT_CONFIRMED) {
            if (tick - sentTick >= MAX_UNCONFIRMED_TICKS) endBait(now);
            return;
        }

        int ticksSinceConfirmed = tick - confirmedTick;
        PacketEntity target = player.compensatedEntities.entityMap.get(targetId);
        if (ticksSinceConfirmed == 1 && target != null) {
            startBaitAngle = angleFromLook(baitOffsetX, baitOffsetZ);
            startTargetAngle = angleToTarget(target);
        } else if (ticksSinceConfirmed == REACTION_TICK && !Double.isNaN(startBaitAngle) && target != null) {
            reacted = hasReacted(target);
        }

        if (ticksSinceConfirmed >= BAIT_LIFETIME_TICKS) endBait(now);
    }

    private boolean hasReacted(PacketEntity target) {
        double turnBeforeBait = Math.abs(baitAngle) - Math.abs(startBaitAngle);
        double turnToBait = Math.abs(startBaitAngle) - Math.abs(angleFromLook(baitOffsetX, baitOffsetZ));
        double turnFromTarget = Math.abs(angleToTarget(target)) - Math.abs(startTargetAngle);
        logBait(String.format(Locale.ROOT, "angle=%.0f, target=%.1f, before=%.1f, toBait=%.1f, fromTarget=%.1f",
                baitAngle, startTargetAngle, turnBeforeBait, turnToBait, turnFromTarget));
        return turnBeforeBait < MAX_TURN_BEFORE_BAIT && turnToBait >= MIN_TURN_TO_BAIT && turnFromTarget >= MIN_TURN_FROM_TARGET;
    }

    private void endBait(long now) {
        player.user.writePacket(new WrapperPlayServerDestroyEntities(baitEntityId));
        player.user.writePacket(new WrapperPlayServerPlayerInfo(WrapperPlayServerPlayerInfo.Action.REMOVE_PLAYER,
                new WrapperPlayServerPlayerInfo.PlayerData(null, baitProfile, null, 0)));
        player.sendTransaction();
        player.user.flushPackets();

        // Without the client's confirmation, or without the target to compare with, the bait proves nothing
        if (reacted != null) {
            logBait("hits=" + baitHits + ", reacted=" + reacted);
            recordResult(reacted);
        }
        baitEntityId = NO_BAIT;
        nextBaitTime = now + randomTimeBetweenBaits();
    }

    private void recordResult(boolean reaction) {
        results.addLast(reaction);
        if (results.size() > RESULTS_KEPT) {
            results.removeFirst();
        }

        int reactions = 0;
        for (boolean result : results) {
            if (result) reactions++;
        }
        if (reactions >= MIN_REACTIONS) {
            flag(V.write(verbose()).uint(reactions).uint(results.size()));
            results.clear();
        }
    }

    // Every bait's measurements, to tune the reaction thresholds with real players
    private void logBait(String details) {
        LogUtil.info(BAIT_LOG_PREFIX + player.getName() + " bait " + baitCount + ": " + details);
    }

    private Vector3d baitPosition() {
        return new Vector3d(player.x + baitOffsetX, player.y, player.z + baitOffsetZ);
    }

    private float baitYaw() {
        // Facing the player
        return (float) Math.toDegrees(Math.atan2(baitOffsetX, -baitOffsetZ));
    }

    // Signed degrees from the look to a horizontal direction; Minecraft yaw 0 faces +Z and 90 faces -X
    private double angleFromLook(double dx, double dz) {
        double degrees = (Math.toDegrees(Math.atan2(-dx, dz)) - player.yaw) % 360;
        if (degrees >= 180) degrees -= 360;
        if (degrees < -180) degrees += 360;
        return degrees;
    }

    private double angleToTarget(PacketEntity target) {
        Vector3d center = boxCenter(target);
        return angleFromLook(center.getX() - player.x, center.getZ() - player.z);
    }

    private Vector3d boxCenter(PacketEntity entity) {
        SimpleCollisionBox box = entity.getPossibleCollisionBoxes();
        return new Vector3d((box.minX + box.maxX) / 2, (box.minY + box.maxY) / 2, (box.minZ + box.maxZ) / 2);
    }

    private double horizontalDistanceTo(Vector3d position) {
        return Math.hypot(position.getX() - player.x, position.getZ() - player.z);
    }

    private static long randomTimeBetweenBaits() {
        return ThreadLocalRandom.current().nextLong(MIN_TIME_BETWEEN_BAITS_MS, MAX_TIME_BETWEEN_BAITS_MS + 1);
    }

    private static String randomName() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder name = new StringBuilder(NAME_LENGTH);
        for (int i = 0; i < NAME_LENGTH; i++) {
            name.append(NAME_CHARACTERS.charAt(random.nextInt(NAME_CHARACTERS.length())));
        }
        return name.toString();
    }

    // Times of a check's flags within the suspicion window, read from its violation count
    private static final class RecentFlags {
        private final ArrayDeque<Long> times = new ArrayDeque<>();
        private double seenViolations;

        boolean update(Check check, long now) {
            int newFlags = (int) (check.violations - seenViolations);
            seenViolations = check.violations;
            for (int i = 0; i < newFlags; i++) {
                times.addLast(now);
            }
            while (!times.isEmpty() && now - times.peekFirst() > SUSPICION_WINDOW_MS) {
                times.removeFirst();
            }
            return newFlags > 0;
        }

        int count() {
            return times.size();
        }
    }
}
