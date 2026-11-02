package ac.grim.grimac.command.commands;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.checks.impl.combat.ClickRateMeter;
import ac.grim.grimac.command.BuildableCommand;
import ac.grim.grimac.platform.api.command.PlayerSelector;
import ac.grim.grimac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.grim.grimac.platform.api.player.PlatformPlayer;
import ac.grim.grimac.platform.api.sender.Sender;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.MessageUtil;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Objects;

public class GrimCps implements BuildableCommand {
    private static final String DEFAULT_RESULT_MESSAGE = "%prefix% &f%player% &7CPS: now &f%now% &8| &7average &f%average% &8| &7peak &f%peak% &8(last 10 s)";

    @Override
    public void register(CommandManager<Sender> commandManager, CloudPlatformCommandArguments arguments) {
        commandManager.command(
                commandManager.commandBuilder("grim", "grimac")
                        .literal("cps")
                        .permission("grim.cps")
                        .required("target", arguments.singlePlayerSelectorParser())
                        .handler(this::handleCps)
        );
    }

    private void handleCps(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        PlayerSelector target = context.get("target");

        PlatformPlayer targetPlatformPlayer = target.getSinglePlayer().getPlatformPlayer();
        if (Objects.requireNonNull(targetPlatformPlayer, "targetPlatformPlayer").isExternalPlayer()) {
            sender.sendMessage(MessageUtil.getParsedComponent(sender, "player-not-this-server", "%prefix% &cThis player isn't on this server!"));
            return;
        }

        GrimPlayer grimPlayer = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(targetPlatformPlayer.getUniqueId());
        if (grimPlayer == null) {
            sender.sendMessage(MessageUtil.getParsedComponent(sender, "player-not-found", "%prefix% &cPlayer is exempt or offline!"));
            return;
        }

        if (grimPlayer.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9)) {
            sender.sendMessage(MessageUtil.getParsedComponent(sender, "cps-unsupported-client", "%prefix% &cCPS can only be measured on 1.8 clients!"));
            return;
        }

        ClickRateMeter meter = grimPlayer.checkManager.get(ClickRateMeter.class);
        String message = GrimAPI.INSTANCE.getConfigManager().getConfig().getStringElse("cps-result", DEFAULT_RESULT_MESSAGE)
                .replace("%now%", Integer.toString(meter.getLastSecondClicks()))
                .replace("%average%", String.format(Locale.ROOT, "%.1f", meter.getAverageClicks()))
                .replace("%peak%", Integer.toString(meter.getPeakClicks()));
        sender.sendMessage(MessageUtil.replacePlaceholders(grimPlayer, MessageUtil.miniMessage(message)));
    }
}
