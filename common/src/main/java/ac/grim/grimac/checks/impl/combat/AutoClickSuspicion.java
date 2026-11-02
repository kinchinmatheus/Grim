package ac.grim.grimac.checks.impl.combat;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.checks.GrimProcessor;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.MessageUtil;

import java.util.concurrent.TimeUnit;

// Staff alert of the autoclick checks. Clicks alone don't prove autoclick, so it only says "may be" and punishes nothing.
// Several checks can fire on the same clicking, so a player alerts once per cooldown.
public final class AutoClickSuspicion extends GrimProcessor {
    private static final String DEFAULT_MESSAGE = "%prefix% &f%player% &7may be using an autoclicker &8(&f%cps% CPS&8)";
    private static final long COOLDOWN_MILLIS = TimeUnit.MINUTES.toMillis(5);

    private long nextAlertMillis;

    public AutoClickSuspicion(GrimPlayer player) {
        super(player);
    }

    void alert(int cps) {
        final long now = System.currentTimeMillis();
        if (now < nextAlertMillis) return;
        nextAlertMillis = now + COOLDOWN_MILLIS;

        String message = GrimAPI.INSTANCE.getConfigManager().getConfig().getStringElse("autoclick-suspicion-format", DEFAULT_MESSAGE)
                .replace("%cps%", Integer.toString(cps));
        GrimAPI.INSTANCE.getAlertManager().sendAlert(MessageUtil.replacePlaceholders(player, MessageUtil.miniMessage(message)), null);
    }
}
