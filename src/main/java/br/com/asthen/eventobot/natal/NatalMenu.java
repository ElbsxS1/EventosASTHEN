package br.com.asthen.eventobot.natal;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Menu de escolha de time (baú de 27 slots). Funciona no Bedrock porque o Geyser traduz o baú, sem
 * depender de formulários do Floodgate.
 */
final class NatalMenu {

    /** Identifica os inventários deste menu. */
    static final class Holder implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private static final int[] SLOTS = {10, 12, 14, 16};

    private final NatalModule m;

    NatalMenu(NatalModule m) {
        this.m = m;
    }

    boolean ehMenu(Inventory inv) {
        return inv != null && inv.getHolder(false) instanceof Holder;
    }

    void abrir(Player p) {
        Holder h = new Holder();
        Inventory inv = Bukkit.createInventory(h, 27, Component.text("Escolha o seu time", NamedTextColor.DARK_RED));
        h.inv = inv;
        NatalModule.Participante eu = m.participante(p.getUniqueId());
        Time[] times = Time.values();
        for (int i = 0; i < times.length; i++) {
            Time t = times[i];
            ItemStack it = new ItemStack(t.lana);
            ItemMeta meta = it.getItemMeta();
            meta.displayName(NatalModule.texto(t.codigo + "§lTime " + t.nome).decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            int reg = m.registrados(t);
            lore.add(linha("§7Jogadores: §f" + reg + "§7/§f" + m.maxPorTime()));
            for (Map.Entry<UUID, NatalModule.Participante> e : m.participantes.entrySet()) {
                if (e.getValue().time == t) {
                    var off = Bukkit.getOfflinePlayer(e.getKey());
                    lore.add(linha("§8- §f" + (off.getName() != null ? off.getName() : "?")));
                }
            }
            lore.add(linha(" "));
            if (eu != null && eu.time == t) {
                lore.add(linha("§aVocê está neste time"));
            } else if (eu != null && eu.time != null) {
                lore.add(linha("§7Você já escolheu um time"));
            } else if (reg >= m.maxPorTime()) {
                lore.add(linha("§cTime cheio"));
            } else {
                lore.add(linha("§aClique para entrar"));
            }
            meta.lore(lore);
            it.setItemMeta(meta);
            inv.setItem(SLOTS[i], it);
        }
        p.openInventory(inv);
    }

    private static Component linha(String legado) {
        return NatalModule.texto(legado).decoration(TextDecoration.ITALIC, false);
    }

    void aoClicar(Player p, int slot) {
        Time escolhido = null;
        for (int i = 0; i < SLOTS.length; i++) {
            if (SLOTS[i] == slot) {
                escolhido = Time.values()[i];
            }
        }
        if (escolhido == null) {
            return;
        }
        String erro = m.escolherTime(p, escolhido);
        if (erro != null) {
            p.sendMessage(NatalModule.texto("§c" + erro));
            return;
        }
        p.closeInventory();
    }
}
