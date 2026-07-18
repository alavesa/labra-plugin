package fi.alavesa.labra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Objective;

import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;

public final class LabraPlugin extends JavaPlugin {

    /** Minimum lab-datapack version (the #datapack marker in lab.var that its
     *  load function writes). Commands that dispatch into the datapack check
     *  this first, so a missing/stale datapack fails LOUDLY instead of
     *  dispatching functions that silently don't exist. */
    private static final int DATAPACK_VERSION = 28;

    private LabRegistry registry;
    private MachineGuiListener machineGuis;
    private LabMenu labMenu;
    private HudTask hud;
    private Scp268Listener scp268;
    private Scp018Listener scp018;

    @Override
    public void onEnable() {
        ActionBars.start(this);
        registry = new LabRegistry(this);
        registry.load();
        machineGuis = new MachineGuiListener(this);
        getServer().getPluginManager().registerEvents(machineGuis, this);
        getServer().getPluginManager().registerEvents(new Scp008Listener(this, registry), this);
        scp268 = new Scp268Listener(this);
        Scp1499Listener scp1499 = new Scp1499Listener(this, registry);
        Scp714Listener scp714 = new Scp714Listener(this);
        Scp427Listener scp427 = new Scp427Listener(this);
        Scp1033Listener scp1033 = new Scp1033Listener(this);
        getServer().getPluginManager().registerEvents(scp268, this);
        getServer().getPluginManager().registerEvents(scp1499, this);
        getServer().getPluginManager().registerEvents(scp714, this);
        getServer().getPluginManager().registerEvents(scp427, this);
        getServer().getPluginManager().registerEvents(scp1033, this);
        getServer().getPluginManager().registerEvents(new Scp005Listener(), this);
        scp018 = new Scp018Listener(this);
        getServer().getPluginManager().registerEvents(scp018, this);
        getServer().getScheduler().runTaskTimer(this, scp018::restoreTick, 100L, 100L);
        getServer().getScheduler().runTaskTimer(this, scp018::ballTick, 1L, 1L);
        Scp038Listener scp038 = new Scp038Listener(this);
        getServer().getPluginManager().registerEvents(scp038, this);
        getServer().getScheduler().runTaskTimer(this, scp038, 40L, 20L);
        getServer().getPluginManager().registerEvents(new Trinkets(), this);
        NvgListener nvg = new NvgListener(this);
        getServer().getPluginManager().registerEvents(nvg, this);
        getServer().getScheduler().runTaskTimer(this, nvg, 40L, 20L);
        RestraintsListener restraints = new RestraintsListener(this);
        getServer().getPluginManager().registerEvents(restraints, this);
        getServer().getScheduler().runTaskTimer(this, restraints, 40L, 20L);
        labMenu = new LabMenu(this);
        getServer().getPluginManager().registerEvents(labMenu, this);
        hud = new HudTask(this);
        getServer().getScheduler().runTaskTimer(this, hud, 40L, 20L);
        DownedListener dying = new DownedListener(this);
        getServer().getPluginManager().registerEvents(dying, this);
        getServer().getScheduler().runTaskTimer(this, dying, 40L, 20L);
        getServer().getScheduler().runTaskTimer(this, dying::crawlTick, 40L, 3L);
        getServer().getScheduler().runTaskTimer(this, new HazardTask(this, registry), 40L, 20L);
        getServer().getScheduler().runTaskTimer(this, new GeigerTask(this, registry), 40L, 5L);
        getServer().getScheduler().runTaskTimer(this, scp268, 40L, 20L);
        getServer().getScheduler().runTaskTimer(this, scp1499, 40L, 20L);
        getServer().getScheduler().runTaskTimer(this, scp714, 40L, 20L);
        getServer().getScheduler().runTaskTimer(this, scp427, 40L, 20L);
        getServer().getScheduler().runTask(this, scp427::sweepOrphans);
        getServer().getScheduler().runTaskTimer(this, scp1033, 40L, 20L);
        getLogger().info("Labra enabled - zones: " + registry.zones().keySet());
        getServer().getScheduler().runTaskLater(this, () -> {
            if (datapackVersion() < DATAPACK_VERSION) {
                getLogger().warning("lab-datapack v0.27+ NOT detected in this world - /lab give/place");
                getLogger().warning("will refuse to run. Install Lab.zip from");
                getLogger().warning("github.com/alavesa/lab-datapack/releases into world/datapacks/.");
            }
        }, 100L);
    }

    @Override
    public void onDisable() {
        if (machineGuis != null) machineGuis.shutdown();
        if (hud != null) hud.shutdown();
        if (scp268 != null) scp268.shutdown();
        if (scp018 != null) scp018.shutdown();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) return usage(sender);
        try {
            switch (args[0].toLowerCase()) {
                case "give" -> {
                    if (args.length < 2) return usage(sender);
                    // datapack-backed items are checked before anything else, so a
                    // missing/stale datapack is reported instead of failing silently
                    if (DATAPACK_ITEMS.contains(args[1].toLowerCase()) && !datapackReady(sender)) return true;
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
                        case "sample" -> {
                            target.getInventory().addItem(registry.buildSample());
                            sender.sendMessage(Component.text("Gave a radioactive sample to "
                                + target.getName() + " - careful with it!", NamedTextColor.GREEN));
                        }
                        // lab-datapack items: the plugin is the interface, the
                        // datapack functions stay the engine
                        case "kit", "rod", "pipette", "manual", "table",
                             "scp009", "scp999", "scp207", "scp148", "scp500", "scp008", "quarter",
                             "scp268", "scp1499", "scp714", "scp018", "scp427", "scp1033",
                             "nvg", "ziptie", "handcuffs", "battery", "medkit", "scp005" -> {
                            if (!sender.hasPermission("lab.give")) return error(sender, "No permission.");
                            runAs(target, "lab:give/" + args[1].toLowerCase());
                            sender.sendMessage(Component.text("Gave lab " + args[1].toLowerCase()
                                + " to " + target.getName(), NamedTextColor.AQUA));
                        }
                        case "element" -> {
                            if (!sender.hasPermission("lab.give")) return error(sender, "No permission.");
                            if (args.length < 4) return error(sender, "/lab give element <player> <symbol> [count]");
                            String symbol = args[3].substring(0, 1).toUpperCase()
                                + args[3].substring(1).toLowerCase();
                            int count = 1;
                            if (args.length >= 5) {
                                try { count = Math.min(27, Math.max(1, Integer.parseInt(args[4]))); }
                                catch (NumberFormatException e) { return error(sender, "Count must be a number."); }
                            }
                            for (int i = 0; i < count; i++) {
                                runAs(target, "lab:e {s:\"" + symbol + "\"}");
                            }
                            sender.sendMessage(Component.text("Gave " + count + " x " + symbol
                                + " to " + target.getName(), NamedTextColor.AQUA));
                        }
                        default -> { return error(sender, "Unknown item. Items: hazmat, geiger, sample, kit, rod, pipette, manual, table, element"); }
                    }
                    return true;
                }
                case "place" -> {
                    if (!sender.hasPermission("lab.admin")) return error(sender, "No permission.");
                    if (!(sender instanceof Player player)) return error(sender, "Players only (aim at the floor).");
                    if (args.length < 2 || !MACHINES.contains(args[1].toLowerCase())) {
                        return error(sender, "/lab place <" + String.join("|", MACHINES) + ">");
                    }
                    if (!datapackReady(sender)) return true;
                    runAs(player, "lab:place/" + args[1].toLowerCase());
                    return true;
                }
                case "removemachines", "dismantle" -> {
                    if (!sender.hasPermission("lab.admin")) return error(sender, "No permission.");
                    if (!(sender instanceof Player player)) return error(sender, "Players only.");
                    runAs(player, "lab:remove");
                    return true;
                }
                case "admin" -> {
                    if (!sender.hasPermission("lab.admin")) return error(sender, "No permission.");
                    if (!(sender instanceof Player player)) return error(sender, "Players only.");
                    runAs(player, "lab:admin");
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
                        case "alarm" -> {
                            if (args.length < 4) return usage(sender);
                            boolean on = args[3].equalsIgnoreCase("on");
                            if (!on && !args[3].equalsIgnoreCase("off")) return error(sender, "Use on or off.");
                            if (!registry.setAlarm(args[2], on)) return error(sender, "No zone named '" + args[2] + "'.");
                            sender.sendMessage(Component.text("Zone '" + args[2].toLowerCase() + "' siren "
                                + (on ? "ON - it blares while an unprotected player is inside." : "off."),
                                NamedTextColor.AQUA));
                            return true;
                        }
                        default -> { return usage(sender); }
                    }
                }
                case "scp1499" -> {
                    if (!sender.hasPermission("lab.admin")) return error(sender, "No permission.");
                    if (args.length < 2) return error(sender, "/lab scp1499 sethere|info");
                    switch (args[1].toLowerCase()) {
                        case "sethere" -> {
                            if (!(sender instanceof Player player)) return error(sender, "Players only (the anchor is set where you stand).");
                            registry.setScp1499Anchor(player.getLocation());
                            sender.sendMessage(Component.text("SCP-1499 anchor set where you stand. The mask now leads here.",
                                NamedTextColor.AQUA));
                        }
                        case "info" -> {
                            var anchor = registry.scp1499Anchor();
                            sender.sendMessage(anchor == null
                                ? Component.text("SCP-1499 anchor: not set (/lab scp1499 sethere). The mask does nothing.",
                                    NamedTextColor.GRAY)
                                : Component.text("SCP-1499 anchor: " + (int) anchor.getX() + " " + (int) anchor.getY()
                                    + " " + (int) anchor.getZ() + " (" + anchor.getWorld().getName() + ")",
                                    NamedTextColor.AQUA));
                        }
                        default -> { return error(sender, "/lab scp1499 sethere|info"); }
                    }
                    return true;
                }
                case "hud" -> {
                    if (!(sender instanceof Player player)) return error(sender, "Players only.");
                    hud.toggle(player);
                    sender.sendMessage(Component.text("Vitals HUD toggled.", NamedTextColor.AQUA));
                    return true;
                }
                case "menu" -> {
                    if (!sender.hasPermission("lab.admin")) return error(sender, "No permission.");
                    if (!(sender instanceof Player player)) return error(sender, "Players only.");
                    labMenu.open(player, 0);
                    return true;
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
            case 1 -> filter(Stream.of("give", "zone", "place", "removemachines", "admin", "scp1499", "reload"), args[0]);
            case 2 -> switch (args[0].toLowerCase()) {
                case "give" -> filter(Stream.of("hazmat", "geiger", "sample", "kit", "rod",
                    "pipette", "manual", "table", "element",
                    "scp009", "scp999", "scp207", "scp148", "scp500", "scp008", "quarter",
                    "scp268", "scp1499", "scp714", "scp018", "scp427", "scp1033",
                    "nvg", "ziptie", "handcuffs", "battery", "medkit", "scp005"), args[1]);
                case "zone" -> filter(Stream.of("add", "remove", "list", "alarm"), args[1]);
                case "scp1499" -> filter(Stream.of("sethere", "info"), args[1]);
                case "place" -> filter(MACHINES.stream(), args[1]);
                default -> List.of();
            };
            case 3 -> args[0].equalsIgnoreCase("zone")
                && (args[1].equalsIgnoreCase("remove") || args[1].equalsIgnoreCase("alarm"))
                ? filter(registry.zones().keySet().stream(), args[2]) : List.of();
            case 4 -> switch (args[0].equalsIgnoreCase("zone") ? args[1].toLowerCase() : "") {
                case "add" -> filter(LabRegistry.ZONE_TYPES.stream(), args[3]);
                case "alarm" -> filter(Stream.of("on", "off"), args[3]);
                default -> List.of();
            };
            default -> List.of();
        };
    }

    private static final List<String> MACHINES =
        List.of("creator", "burner", "centrifuge", "fridge", "rack", "scp294", "scp038");

    /** /lab give items served by the datapack (everything except the plugin's
     *  own hazmat/geiger/sample). */
    private static final List<String> DATAPACK_ITEMS =
        List.of("kit", "rod", "pipette", "manual", "table", "element",
            "scp009", "scp999", "scp207", "scp148", "scp500", "scp008", "quarter",
            "scp268", "scp1499", "scp714", "scp018", "scp427", "scp1033",
            "nvg", "ziptie", "handcuffs", "battery", "medkit", "scp005");

    /** The lab-datapack's load function writes its version to #datapack in
     *  lab.var. No marker (or an old one) means dispatched functions would
     *  fail silently - so refuse up front and say why. */
    private int datapackVersion() {
        Objective obj = Bukkit.getScoreboardManager().getMainScoreboard().getObjective("lab.var");
        if (obj == null) return 0;
        var score = obj.getScore("#datapack");
        return score.isScoreSet() ? score.getScore() : 0;
    }

    private boolean datapackReady(CommandSender sender) {
        int version = datapackVersion();
        if (version >= DATAPACK_VERSION) return true;
        error(sender, version == 0
            ? "The lab-datapack is missing from this world - nothing to give."
            : "The lab-datapack in this world is outdated (v0." + version + ", need v0." + DATAPACK_VERSION + "+).");
        error(sender, "Get Lab.zip from github.com/alavesa/lab-datapack/releases, extract into world/datapacks/, then /minecraft:reload.");
        return false;
    }

    /** Run a lab-datapack function as the given player (the datapack is the
     *  engine; this command is the interface). */
    private void runAs(Player player, String function) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
            "execute as " + player.getUniqueId() + " at @s run function " + function);
    }

    public org.bukkit.NamespacedKey keyOf(String name) {
        return new org.bukkit.NamespacedKey(this, name);
    }

    private List<String> filter(Stream<String> options, String prefix) {
        return options.filter(o -> o.startsWith(prefix.toLowerCase())).sorted().toList();
    }

    private boolean usage(CommandSender sender) {
        sender.sendMessage(Component.text(
            "/lab give hazmat|geiger|sample|kit|rod|pipette|manual|table|scp... [player] | give element <player> <symbol> [count] | place <" + String.join("|", MACHINES) + "> | removemachines | admin | scp1499 sethere|info | zone add <name> <radiation|toxic|cryo|decon> <radius> | zone alarm <name> on|off | zone remove <name> | zone list | reload",
            NamedTextColor.AQUA));
        return true;
    }

    private boolean error(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.RED));
        return true;
    }
}
