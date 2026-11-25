package br.com.asthen.eventobot.natal;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** Ranking de kills flutuando no lobby (TextDisplay), visível para todos. Posição própria em holograma.yml. */
final class NatalHolograma {

    private final NatalModule m;
    private final File arquivo;
    private TextDisplay entidade;
    private BukkitTask tarefa;

    NatalHolograma(NatalModule m) {
        this.m = m;
        this.arquivo = new File(m.plugin().getDataFolder(), "holograma.yml");
    }

    void iniciar() {
        tarefa = Bukkit.getScheduler().runTaskTimer(m.plugin(), this::atualizar, 40L, 100L);
    }

    void parar() {
        if (tarefa != null) {
            tarefa.cancel();
        }
        apagar();
    }

    private Location local() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(arquivo);
        if (!y.contains("x")) {
            return null;
        }
        World w = Bukkit.getWorld(y.getString("mundo", ""));
        if (w == null) {
            return null;
        }
        return new Location(w, y.getDouble("x"), y.getDouble("y"), y.getDouble("z"));
    }

    boolean definido() {
        return local() != null;
    }

    void definir(Location l) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("mundo", l.getWorld().getName());
        y.set("x", l.getX());
        y.set("y", l.getY() + 2.5);
        y.set("z", l.getZ());
        try {
            y.save(arquivo);
        } catch (IOException e) {
            m.plugin().getLogger().warning("Não salvei holograma.yml: " + e.getMessage());
        }
        apagar();
        atualizar();
    }

    void remover() {
        if (arquivo.exists() && !arquivo.delete()) {
            m.plugin().getLogger().warning("Não consegui apagar holograma.yml");
        }
        apagar();
    }

    private void apagar() {
        if (entidade != null) {
            entidade.remove();
            entidade = null;
        }
    }

    void atualizar() {
        Location l = local();
        if (l == null) {
            apagar();
            return;
        }
        if (!l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) {
            return;
        }
        if (entidade == null || !entidade.isValid()) {
            entidade = l.getWorld().spawn(l, TextDisplay.class, d -> {
                d.setPersistent(false);
                d.setBillboard(Display.Billboard.CENTER);
                d.setViewRange(2f);
            });
        }
        Component t = NatalModule.texto("§6§l★ RANKING DE NATAL ★");
        List<Ranking.Entrada> top = m.ranking.top(m.holoTop());
        if (top.isEmpty()) {
            t = t.append(Component.newline()).append(NatalModule.texto("§7ninguém pontuou ainda"));
        }
        for (int i = 0; i < top.size(); i++) {
            t = t.append(Component.newline()).append(NatalModule.texto(
                    "§e" + (i + 1) + "º §f" + top.get(i).nome() + " §7- §c" + top.get(i).kills() + " kills"));
        }
        entidade.text(t);
    }
}
