package dev.tins.worldguardextraflagsplus;

import it.coralmc.tongue.lib.api.Key;
import it.coralmc.tongue.lib.api.Placeholder;
import it.coralmc.tongue.lib.api.component.TongueComponents;
import it.coralmc.tongue.lib.bukkit.BukkitTongue;
import it.coralmc.tongue.lib.bukkit.BukkitTongueService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LocalizedMessages {
    private static final Pattern REFERENCES = Pattern.compile("%tongue_([^%|]+)%");
    private static final Pattern LEGACY = Pattern.compile("[&§]([0-9a-fk-or])", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEX = Pattern.compile("[&§]x((?:[&§][0-9a-f]){6})|[&§]#([0-9a-f]{6})", Pattern.CASE_INSENSITIVE);
    private static final String[] COLORS = {"black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"};
    private LocalizedMessages() { }

    static String render(BukkitTongue tongue, Player viewer, String configured, String... replacements) {
        return render(tongue, viewer, viewer, configured, replacements);
    }

    static String render(BukkitTongue tongue, Player viewer, Player placeholderPlayer, String configured, String... replacements) {
        List<Placeholder> arguments = new ArrayList<>(replacements.length / 2);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            String name = replacements[i];
            Component value = Component.text(replacements[i + 1]);
            if (name.equals("item") || name.equals("workbench")) {
                Material material = Material.matchMaterial(replacements[i + 1]);
                BukkitTongue core = BukkitTongueService.REGISTERED.get("coral-core");
                if (material != null && core != null) value = core.component(viewer,
                        Key.of("coral-core", "minecraft." + (material.isItem() ? material.getItemTranslationKey() : material.getTranslationKey())));
            }
            arguments.add(Placeholder.component(name, value));
        }
        Placeholder[] placeholders = arguments.toArray(Placeholder[]::new);
        TagResolver.Builder tags = TagResolver.builder();
        Matcher references = REFERENCES.matcher(configured);
        StringBuilder expanded = new StringBuilder(configured.length());
        int index = 0;
        while (references.find()) {
            String identifier = references.group(1);
            int split = identifier.indexOf(':');
            if (split < 0) split = identifier.indexOf('_');
            String namespace = split < 0 ? "worldguard-extraflags-plus" : identifier.substring(0, split);
            String key = split < 0 ? identifier : identifier.substring(split + 1);
            BukkitTongue owner = BukkitTongueService.REGISTERED.get(namespace);
            if (owner == null) throw new IllegalArgumentException("Unregistered Tongue namespace: " + namespace);
            String tag = "wgefp_translation_" + index++;
            tags.tag(tag, Tag.selfClosingInserting(owner.translate(tongue.getLocale(viewer), Key.of(namespace, key), placeholders)));
            references.appendReplacement(expanded, Matcher.quoteReplacement("<" + tag + ">"));
        }
        String message = references.appendTail(expanded).toString();
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) message = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(placeholderPlayer, message);
        for (int i = 0; i < placeholders.length; i++) {
            Placeholder argument = placeholders[i];
            String tag = "wgefp_argument_" + i;
            tags.tag(tag, Tag.selfClosingInserting((Component) argument.value()));
            message = message.replace("{" + argument.name() + "}", "<" + tag + ">")
                    .replace("<" + argument.name() + ">", "<" + tag + ">");
        }
        Matcher hex = HEX.matcher(message);
        StringBuilder hexMessage = new StringBuilder(message.length());
        while (hex.find()) {
            String color = hex.group(2) == null ? hex.group(1).replace("&", "").replace("§", "") : hex.group(2);
            hex.appendReplacement(hexMessage, "<reset><#" + color + ">");
        }
        message = hex.appendTail(hexMessage).toString();
        Matcher legacy = LEGACY.matcher(message);
        StringBuilder normalized = new StringBuilder(message.length());
        while (legacy.find()) {
            char code = Character.toLowerCase(legacy.group(1).charAt(0));
            int color = Character.digit(code, 16);
            String tag = color >= 0 ? "<reset><" + COLORS[color] + ">" : switch (code) {
                case 'k' -> "<obfuscated>";
                case 'l' -> "<bold>";
                case 'm' -> "<strikethrough>";
                case 'n' -> "<underlined>";
                case 'o' -> "<italic>";
                default -> "<reset>";
            };
            legacy.appendReplacement(normalized, Matcher.quoteReplacement(tag));
        }
        return LegacyComponentSerializer.legacySection().serialize(TongueComponents.component(legacy.appendTail(normalized).toString(), tags.build()));
    }
}
