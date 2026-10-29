package dev.debuffwheel;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.Material;
import org.bukkit.Bukkit;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;

/** Holds the wheel entries, active debuffs and per-player daily spin data. */
public final class DebuffManager implements Listener {

    public record Debuff(String id, String name, PotionEffectType type, int amplifier) {}
    public record Active(String id, long expiresAt) {}

    private final DebuffWheelPlugin plugin;
    private final List<Debuff> wheel = new ArrayList<>();
    private final Map<UUID, Active> active = new HashMap<>();
    private final Map<UUID, String> lastSpin = new HashMap<>();
    private String lastAutoSpinDay = "";
    private final File dataFile;
    private long durationMillis;

    public DebuffManager(DebuffWheelPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
    }

    // ---------------------------------------------------------------- loading

    public void load() {
        FileConfiguration cfg = plugin.getConfig();
        durationMillis = Math.max(1, cfg.getLong("duration-minutes", 90)) * 60_000L;

        wheel.clear();
        for (Map<?, ?> m : cfg.getMapList("effects")) {
            String t = String.valueOf(m.get("type")).toLowerCase(Locale.ROOT);
            PotionEffectType type = Registry.EFFECT.get(NamespacedKey.minecraft(t));
            if (type == null) {
                plugin.getLogger().warning("Unknown effect in config: " + t);
                continue;
            }
            int level = m.get("level") instanceof Number n ? Math.max(1, n.intValue()) : 1;
            String name = m.get("name") != null ? String.valueOf(m.get("name")) : pretty(t) + " " + level;
            wheel.add(new Debuff(t + ":" + (level - 1), name, type, level - 1));
        }
        if (wheel.isEmpty()) plugin.getLogger().warning("The wheel has no valid effects!");

        active.clear();
        lastSpin.clear();
        if (dataFile.exists()) {
            YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
            var a = data.getConfigurationSection("active");
            if (a != null) for (String k : a.getKeys(false)) {
                try {
                    active.put(UUID.fromString(k),
                            new Active(a.getString(k + ".id", ""), a.getLong(k + ".expires")));
                } catch (IllegalArgumentException ignored) {}
            }
            var l = data.getConfigurationSection("last-spin");
            if (l != null) for (String k : l.getKeys(false)) {
                try { lastSpin.put(UUID.fromString(k), l.getString(k, "")); }
                catch (IllegalArgumentException ignored) {}
            }
            lastAutoSpinDay = data.getString("last-auto-spin", "");
        }
    }

    public void save() {
        YamlConfiguration data = new YamlConfiguration();
        active.forEach((u, a) -> {
            data.set("active." + u + ".id", a.id());
            data.set("active." + u + ".expires", a.expiresAt());
        });
        lastSpin.forEach((u, d) -> data.set("last-spin." + u, d));
        data.set("last-auto-spin", lastAutoSpinDay);
        try {
            plugin.getDataFolder().mkdirs();
            data.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save data.yml: " + e.getMessage());
        }
    }

    private static String pretty(String s) {
        StringBuilder sb = new StringBuilder();
        for (String part : s.split("_")) {
            if (part.isEmpty()) continue;
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }

    // ---------------------------------------------------------------- queries

    public List<Debuff> wheel() { return wheel; }

    public Debuff random() { return wheel.get(new Random().nextInt(wheel.size())); }

    public Optional<Debuff> find(String id) {
        return wheel.stream().filter(d -> d.id().equals(id)).findFirst();
    }

    public Active getActive(UUID u) {
        Active a = active.get(u);
        if (a != null && a.expiresAt() <= System.currentTimeMillis()) return null;
        return a;
    }

    public long remainingMillis(UUID u) {
        Active a = getActive(u);
        return a == null ? 0 : a.expiresAt() - System.currentTimeMillis();
    }

    public boolean hasSpunToday(UUID u) {
        return LocalDate.now().toString().equals(lastSpin.get(u));
    }

    public boolean alreadyAutoSpunToday() { return LocalDate.now().toString().equals(lastAutoSpinDay); }

    public void markAutoSpun() { lastAutoSpinDay = LocalDate.now().toString(); save(); }

    // ---------------------------------------------------------------- actions

    public void give(Player p, Debuff d) {
        active.put(p.getUniqueId(), new Active(d.id(), System.currentTimeMillis() + durationMillis));
        lastSpin.put(p.getUniqueId(), LocalDate.now().toString());
        save();
        enforce(p);
    }

    public void clear(UUID u) {
        Active a = active.remove(u);
        save();
        Player p = Bukkit.getPlayer(u);
        if (p != null && a != null) find(a.id()).ifPresent(d -> p.removePotionEffect(d.type()));
    }

    public void resetDaily(UUID u) {
        lastSpin.remove(u);
        save();
    }

    /** Makes sure an online player has the potion effect matching their debuff. */
    public void enforce(Player p) {
        UUID u = p.getUniqueId();
        Active a = active.get(u);
        if (a == null) return;
        Optional<Debuff> opt = find(a.id());
        long remainingMs = a.expiresAt() - System.currentTimeMillis();

        if (opt.isEmpty() || remainingMs <= 0) {
            opt.ifPresent(d -> p.removePotionEffect(d.type()));
            active.remove(u);
            save();
            if (remainingMs <= 0) p.sendMessage(DebuffWheelPlugin.msg("Your debuff has worn off. Good luck!"));
            return;
        }
        Debuff d = opt.get();
        int ticks = (int) Math.min(Integer.MAX_VALUE - 1, remainingMs / 50);
        PotionEffect cur = p.getPotionEffect(d.type());
        if (cur == null || cur.getAmplifier() != d.amplifier() || Math.abs(cur.getDuration() - ticks) > 200) {
            p.addPotionEffect(new PotionEffect(d.type(), ticks, d.amplifier(), false, true, true));
        }
    }

    public void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) enforce(p);
    }

    // -------------------------------------------------------------- listeners

    private void later(Player p) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) enforce(p); }, 2L);
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) { later(e.getPlayer()); }

    @EventHandler public void onRespawn(PlayerRespawnEvent e) { later(e.getPlayer()); }

    @EventHandler public void onConsume(PlayerItemConsumeEvent e) {
        if (e.getItem().getType() == Material.MILK_BUCKET) later(e.getPlayer());
    }

    @EventHandler public void onTotem(EntityResurrectEvent e) {
        if (e.getEntity() instanceof Player p) later(p);
    }
}
