package dev.tins.worldguardextraflagsplus.wg;

import it.coralmc.tongue.lib.api.Key;
import it.coralmc.tongue.lib.api.Placeholder;
import it.coralmc.tongue.lib.bukkit.BukkitTongueService;
import org.bukkit.entity.Player;

/** Preserves the existing direct notification path when the Paper reflection bridge fails. */
public final class Messages {
    private Messages() { }
    public static void send(Player player, String key, String... replacements) {
        if (!player.isOnline()) return;
        Placeholder[] arguments = new Placeholder[replacements.length / 2];
        for (int i = 0; i < arguments.length; i++) arguments[i] = Placeholder.text(replacements[i * 2], replacements[i * 2 + 1]);
        BukkitTongueService.REGISTERED.get("worldguard-extraflags-plus").send(player,
                Key.of("worldguard-extraflags-plus", "message." + key), arguments);
    }
}
