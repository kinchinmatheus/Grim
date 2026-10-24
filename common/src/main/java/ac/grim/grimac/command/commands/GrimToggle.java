package ac.grim.grimac.command.commands;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.command.BuildableCommand;
import ac.grim.grimac.platform.api.command.PlayerSelector;
import ac.grim.grimac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.grim.grimac.platform.api.sender.Sender;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.MessageUtil;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.jetbrains.annotations.NotNull;

public class GrimToggle implements BuildableCommand {

    @Override
    public void register(CommandManager<Sender> commandManager, CloudPlatformCommandArguments arguments) {
        commandManager.command(
                commandManager.commandBuilder("grim", "grimac")
                        .literal("toggle")
                        .permission("grim.toggle")
                        .required("target", arguments.singlePlayerSelectorParser())
                        .handler(this::handleToggle)
        );
    }

    private void handleToggle(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        PlayerSelector targetSelector = context.getOrDefault("target", null);
        if (targetSelector == null) return;

        var targetPlayer = targetSelector.getSinglePlayer().getPlatformPlayer();
        if (targetPlayer == null || targetPlayer.isExternalPlayer()) {
            sender.sendMessage(MessageUtil.getParsedComponent(sender,"player-not-this-server", "%prefix% &cThis player isn't on this server!"));
            return;
        }

        GrimPlayer grimPlayer = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(targetPlayer.getUniqueId());
        if (grimPlayer == null) {
            sender.sendMessage(MessageUtil.getParsedComponent(sender, "player-not-found", "%prefix% &cPlayer is exempt or offline!"));
            return;
        }

        grimPlayer.disableGrim = !grimPlayer.disableGrim;

        if (grimPlayer.disableGrim) {
            sender.sendMessage(MessageUtil.getParsedComponent(sender, "toggle-grim-disabled", "%prefix% &fGrim &cdisabled &ffor &e%player%"));
        } else {
            sender.sendMessage(MessageUtil.getParsedComponent(sender, "toggle-grim-enabled", "%prefix% &fGrim &aenabled &ffor &e%player%"));
        }
    }
}
