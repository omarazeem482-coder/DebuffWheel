package dev.debuffwheel;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class DebuffCommand implements CommandExecutor, TabCompleter {

    private final DebuffWheelPlugin plugin;
    private final DebuffManager manager;
    private final WheelSpinner spinner;

    public DebuffCommand(DebuffWheelPlugin plugin, DebuffManager manager, WheelSpinner spinner) {
        this.plugin = plugin;
        this.manager = manager;
        this.spinner = spinner;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length == 0 ? "spin" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "spin" -> spin(s, a);
            case "status" -> status(s, a);
            case "list" -> {
                s.sendMessage(DebuffWheelPlugin.msg("Wheel entries:"));
                for (var d : manager.wheel()) s.sendMessage(DebuffWheelPlugin.msg(" - " + d.name()));
            }
            case "clear" -> {
                if (!admin(s)) return true;
                if (a.length < 2) { s.sendMessage(DebuffWheelPlugin.msg("Usage: /dw clear <player|*>")); return true; }
                for (Player p : targets(s, a[1])) {
                    manager.clear(p.getUniqueId());
                    s.sendMessage(DebuffWheelPlugin.msg("Cleared debuff for " + p.getName()));
                }
            }
            case "reset" -> {
                if (!admin(s)) return true;
                if (a.length < 2) { s.sendMessage(DebuffWheelPlugin.msg("Usage: /dw reset <player|*>")); return true; }
                for (Player p : targets(s, a[1])) {
                    manager.resetDaily(p.getUniqueId());
                    s.sendMessage(DebuffWheelPlugin.msg("Reset daily spin for " + p.getName()));
                }
            }
            case "reload" -> {
                if (!admin(s)) return true;
                plugin.reloadConfig();
                manager.load();
                s.sendMessage(DebuffWheelPlugin.msg("Reloaded."));
            }
            default -> s.sendMessage(DebuffWheelPlugin.msg("Usage: /dw <spin|status|list|clear|reset|reload>"));
        }
        return true;
    }

    private void spin(CommandSender s, String[] a) {
        if (manager.wheel().isEmpty()) { s.sendMessage(DebuffWheelPlugin.msg("The wheel is empty. Check config.yml.")); return; }

        // Spinning for someone else (or everyone) = admin
        if (a.length >= 2) {
            if (!admin(s)) return;
            for (Player p : targets(s, a[1])) {
                if (spinner.isSpinning(p.getUniqueId())) continue;
                spinner.spin(p);
            }
            return;
        }

        if (!(s instanceof Player p)) { s.sendMessage("Console must specify a player: /dw spin <player|*>"); return; }
        if (!p.hasPermission("debuffwheel.use")) { p.sendMessage(DebuffWheelPlugin.msg("No permission.")); return; }
        boolean bypass = p.hasPermission("debuffwheel.bypass");
        if (spinner.isSpinning(p.getUniqueId())) { p.sendMessage(DebuffWheelPlugin.msg("The wheel is already spinning!")); return; }
        if (!bypass && plugin.getConfig().getBoolean("once-per-day", true) && manager.hasSpunToday(p.getUniqueId())) {
            p.sendMessage(DebuffWheelPlugin.msg("You already spun today. Come back tomorrow!"));
            return;
        }
        if (!bypass && manager.getActive(p.getUniqueId()) != null) {
            p.sendMessage(DebuffWheelPlugin.msg("You already have an active debuff."));
            return;
        }
        spinner.spin(p);
    }

    private void status(CommandSender s, String[] a) {
        Player p;
        if (a.length >= 2 && admin(s)) p = Bukkit.getPlayer(a[1]);
        else if (s instanceof Player pl) p = pl;
        else { s.sendMessage("Specify a player."); return; }
        if (p == null) { s.sendMessage(DebuffWheelPlugin.msg("Player not found.")); return; }

        var act = manager.getActive(p.getUniqueId());
        if (act == null) { s.sendMessage(DebuffWheelPlugin.msg(p.getName() + " has no active debuff.")); return; }
        String name = manager.find(act.id()).map(DebuffManager.Debuff::name).orElse(act.id());
        s.sendMessage(DebuffWheelPlugin.msg(p.getName() + ": " + name + " - "
                + DebuffWheelPlugin.formatTime(manager.remainingMillis(p.getUniqueId())) + " left"));
    }

    private boolean admin(CommandSender s) {
        if (s.hasPermission("debuffwheel.admin")) return true;
        s.sendMessage(DebuffWheelPlugin.msg("No permission."));
        return false;
    }

    private List<Player> targets(CommandSender s, String arg) {
        if (arg.equals("*") || arg.equalsIgnoreCase("@a")) return new ArrayList<>(Bukkit.getOnlinePlayers());
        Player p = Bukkit.getPlayer(arg);
        if (p == null) { s.sendMessage(DebuffWheelPlugin.msg("Player not found: " + arg)); return List.of(); }
        return List.of(p);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.addAll(List.of("spin", "status", "list"));
            if (s.hasPermission("debuffwheel.admin")) out.addAll(List.of("clear", "reset", "reload"));
        } else if (a.length == 2 && s.hasPermission("debuffwheel.admin")
                && List.of("spin", "status", "clear", "reset").contains(a[0].toLowerCase(Locale.ROOT))) {
            out.add("*");
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        }
        String prefix = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(prefix));
        return out;
    }
}
