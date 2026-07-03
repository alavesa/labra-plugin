package fi.alavesa.labra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;

public final class LabraPlugin extends JavaPlugin {

    private LabRegistry registry;

    @Override
    public void onEnable() {
        registry = new LabRegistry(this);
        registry.load();
        getServer().getScheduler().runTaskTimer(this, new HazardTask(this, registry), 40L, 20L);
        getServer().getScheduler().runTaskTimer(this, new GeigerTask(this, registry), 40L, 5L);
        getLogger().info("Labra enabled - zones: " + registry.zones().keySet());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) return usage(sender);
        try {
            switch (args[0].toLowerCase()) {
                case "give" -> {
                    if (args.length < 2) return usage(sender);
                    Player target = args.length >= 3 ? Bukkit.getPlayerExact(args[2])
                        : (sender instanceof Player p ? p : null);
                    if (target == null) return error(sender, "Player not found.");
                    switch (args[1].toLowerCase()) {
                        case "hazmat" -> {
                            for (ItemStack piece : registry.buildHazmatSuit()) {
                                target.getInventory().addItem(piece).values()
                                    .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
                            }
                            sender.sendMessage(Component.text("Gave a hazmat suit (4 pieces) to "
                                + target.getName(), NamedTextColor.YELLOW));
                        }
                        case "geiger" -> {
                            target.getInventory().addItem(registry.buildGeiger());
                            sender.sendMessage(Component.text("Gave a geiger counter to "
                                + target.getName(), NamedTextColor.GREEN));
                        }
                        default -> { return error(sender, "Unknown item. Items: hazmat, geiger"); }
                    }
                    return true;
                }
                case "zone" -> {
                    if (!sender.hasPermission("lab.admin")) return error(sender, "No permission.");
                    if (args.length < 2) return usage(sender);
                    switch (args[1].toLowerCase()) {
                        case "list" -> {
                            if (registry.zones().isEmpty()) {
                                sender.sendMessage(Component.text("No zones yet. /lab zone add <name> <type> <radius>",
                                    NamedTextColor.GRAY));
                            } else {
                                registry.zones().values().forEach(z -> sender.sendMessage(Component.text(
                                    z.name() + " - " + z.type() + ", radius " + (int) z.radius() + ", at "
                                    + (int) z.x() + " " + (int) z.y() + " " + (int) z.z() + " (" + z.world() + ")",
                                    NamedTextColor.AQUA)));
                            }
                            return true;
                        }
                        case "add" -> {
                            if (!(sender instanceof Player player)) return error(sender, "Players only (the zone is placed where you stand).");
                            if (args.length < 5) return usage(sender);
                            String type = args[3].toLowerCase();
                            if (!LabRegistry.ZONE_TYPES.contains(type)) {
                                return error(sender, "Unknown type. Types: " + String.join(", ", LabRegistry.ZONE_TYPES));
                            }
                            double radius;
                            try { radius = Double.parseDouble(args[4]); } catch (NumberFormatException e) {
                                return error(sender, "Radius must be a number.");
                            }
                            if (!registry.addZone(args[2], type, radius, player.getLocation())) {
                                return error(sender, "Zone '" + args[2] + "' already exists.");
                            }
                            sender.sendMessage(Component.text("Zone '" + args[2].toLowerCase() + "' (" + type
                                + ", radius " + (int) Math.min(64, Math.max(1, radius))
                                + ") created where you stand.", NamedTextColor.AQUA));
                            return true;
                        }
                        case "remove" -> {
                            if (args.length < 3) return usage(sender);
                            if (!registry.removeZone(args[2])) return error(sender, "No zone named '" + args[2] + "'.");
                            sender.sendMessage(Component.text("Zone '" + args[2].toLowerCase() + "' removed.",
                                NamedTextColor.AQUA));
                            return true;
                        }
                        default -> { return usage(sender); }
                    }
                }
                case "reload" -> {
                    if (!sender.hasPermission("lab.admin")) return error(sender, "No permission.");
                    registry.load();
                    sender.sendMessage(Component.text("lab.yml reloaded - " + registry.zones().size()
                        + " zone(s).", NamedTextColor.AQUA));
                    return true;
                }
                default -> { return usage(sender); }
            }
        } catch (IOException e) {
            getLogger().severe("Could not save lab.yml: " + e.getMessage());
            return error(sender, "Saving lab.yml failed - see console.");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return switch (args.length) {
            case 1 -> filter(Stream.of("give", "zone", "reload"), args[0]);
            case 2 -> switch (args[0].toLowerCase()) {
                case "give" -> filter(Stream.of("hazmat", "geiger"), args[1]);
                case "zone" -> filter(Stream.of("add", "remove", "list"), args[1]);
                default -> List.of();
            };
            case 3 -> args[0].equalsIgnoreCase("zone") && args[1].equalsIgnoreCase("remove")
                ? filter(registry.zones().keySet().stream(), args[2]) : List.of();
            case 4 -> args[0].equalsIgnoreCase("zone") && args[1].equalsIgnoreCase("add")
                ? filter(LabRegistry.ZONE_TYPES.stream(), args[3]) : List.of();
            default -> List.of();
        };
    }

    private List<String> filter(Stream<String> options, String prefix) {
        return options.filter(o -> o.startsWith(prefix.toLowerCase())).sorted().toList();
    }

    private boolean usage(CommandSender sender) {
        sender.sendMessage(Component.text(
            "/lab give hazmat|geiger [player] | zone add <name> <radiation|toxic|cryo> <radius> | zone remove <name> | zone list | reload",
            NamedTextColor.AQUA));
        return true;
    }

    private boolean error(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.RED));
        return true;
    }
}
