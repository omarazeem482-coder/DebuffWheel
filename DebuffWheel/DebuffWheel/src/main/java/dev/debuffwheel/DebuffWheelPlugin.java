package dev.debuffwheel;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public final class DebuffWheelPlugin extends JavaPlugin {

    private DebuffManager manager;
    private WheelSpinner spinner;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new DebuffManager(this);
        manager.load();
        spinner = new WheelSpinner(this, manager);

        Bukkit.getPluginManager().registerEvents(manager, this);

        DebuffCommand cmd = new DebuffCommand(this, manager, spinner);
        var pc = getCommand("debuffwheel");
        if (pc != null) { pc.setExecutor(cmd); pc.setTabCompleter(cmd); }

        // Re-apply debuffs every 5 seconds (covers milk, totems, death, etc.)
        Bukkit.getScheduler().runTaskTimer(this, manager::tick, 100L, 100L);

        // Optional automatic daily spin
        Bukkit.getScheduler().runTaskTimer(this, this::checkAutoSpin, 200L, 1200L);
    }

    @Override
    public void onDisable() {
        if (manager != null) manager.save();
    }

    private void checkAutoSpin() {
        if (!getConfig().getBoolean("auto-daily-spin.enabled", false)) return;
        if (manager.alreadyAutoSpunToday() || manager.wheel().isEmpty()) return;
        try {
            LocalTime target = LocalTime.parse(getConfig().getString("auto-daily-spin.time", "18:00"),
                    DateTimeFormatter.ofPattern("H:mm"));
            LocalTime now = LocalTime.now();
            if (now.isBefore(target)) return;
        } catch (Exception e) {
            getLogger().warning("Invalid auto-daily-spin.time (use HH:mm)");
            return;
        }
        manager.markAutoSpun();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (manager.getActive(p.getUniqueId()) == null && !spinner.isSpinning(p.getUniqueId())) spinner.spin(p);
        }
    }

    public static Component msg(String text) {
        return Component.text("[Wheel] ", NamedTextColor.GOLD).append(Component.text(text, NamedTextColor.YELLOW));
    }

    public static String formatTime(long ms) {
        long totalMin = Math.max(0, ms) / 60_000L;
        long h = totalMin / 60, m = totalMin % 60;
        if (h > 0) return h + "h " + m + "m";
        return m + "m";
    }
}
