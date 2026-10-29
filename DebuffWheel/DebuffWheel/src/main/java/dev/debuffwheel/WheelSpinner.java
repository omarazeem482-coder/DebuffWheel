package dev.debuffwheel;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Duration;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** Plays the "wheel spin" animation as titles, then applies the debuff. */
public final class WheelSpinner {

    private final DebuffWheelPlugin plugin;
    private final DebuffManager manager;
    private final Set<UUID> spinning = new HashSet<>();
    private final Random random = new Random();

    public WheelSpinner(DebuffWheelPlugin plugin, DebuffManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public boolean isSpinning(UUID u) { return spinning.contains(u); }

    public void spin(Player target) {
        var wheel = manager.wheel();
        if (wheel.isEmpty() || !spinning.add(target.getUniqueId())) return;

        DebuffManager.Debuff result = manager.random();
        int frames = Math.max(5, plugin.getConfig().getInt("spin-frames", 24));

        new BukkitRunnable() {
            int tick = 0, nextFrame = 0, frame = 0;
            DebuffManager.Debuff shown = null;

            @Override public void run() {
                if (!target.isOnline()) { spinning.remove(target.getUniqueId()); cancel(); return; }
                if (tick++ < nextFrame) return;

                if (frame < frames) {
                    boolean last = frame == frames - 1;
                    DebuffManager.Debuff pick = last ? result : wheel.get(random.nextInt(wheel.size()));
                    while (!last && wheel.size() > 1 && pick == shown) pick = wheel.get(random.nextInt(wheel.size()));
                    shown = pick;

                    target.showTitle(Title.title(
                            Component.text(pick.name(), last ? NamedTextColor.RED : NamedTextColor.YELLOW),
                            Component.text("Spinning the wheel...", NamedTextColor.GRAY),
                            Title.Times.times(Duration.ZERO, Duration.ofMillis(700), Duration.ZERO)));
                    float pitch = 0.6f + 1.2f * frame / frames;
                    target.playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, pitch);

                    frame++;
                    nextFrame = tick + 2 + (frame * frame) / 60 + (last ? 15 : 0);
                    return;
                }

                // finished
                spinning.remove(target.getUniqueId());
                manager.give(target, result);
                target.showTitle(Title.title(
                        Component.text(result.name(), NamedTextColor.RED),
                        Component.text("for " + DebuffWheelPlugin.formatTime(manager.remainingMillis(target.getUniqueId())),
                                NamedTextColor.GOLD),
                        Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(4), Duration.ofSeconds(1))));
                target.playSound(target.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 0.6f);

                if (plugin.getConfig().getBoolean("broadcast", true)) {
                    Bukkit.broadcast(DebuffWheelPlugin.msg(target.getName() + " spun the wheel and got "
                            + result.name() + " for "
                            + DebuffWheelPlugin.formatTime(manager.remainingMillis(target.getUniqueId())) + "!"));
                }
                cancel();
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }
}
