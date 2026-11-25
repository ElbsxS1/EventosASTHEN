package br.com.asthen.eventobot.natal;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** Painel de administração do Natal (baú de 54 slots). Abre com /natal painel. */
final class NatalPainel {

    static final class Holder implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private static final int[] ENC = {37, 38, 39, 40};

    private final NatalModule m;

    NatalPainel(NatalModule m) {
        this.m = m;
    }

    boolean ehPainel(Inventory inv) {
        return inv != null && inv.getHolder(false) instanceof Holder;
    }

    private static Component t(String legado) {
        return NatalModule.texto(legado).decoration(TextDecoration.ITALIC, false);
    }

    private void colocar(Inventory inv, int slot, Material mat, String nome, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(t(nome));
        List<Component> l = new ArrayList<>();
        for (String s : lore) {
            l.add(t(s));
        }
        meta.lore(l);
        it.setItemMeta(meta);
        inv.setItem(slot, it);
    }

    private static String marca(Location l) {
        return l == null ? "§c✗ não definido" : "§a✓ definido";
    }

    void abrir(Player p) {
        Holder h = new Holder();
        Inventory inv = Bukkit.createInventory(h, 54, Component.text("Painel do Natal", NamedTextColor.DARK_RED));
        h.inv = inv;
        colocar(inv, 4, Material.CLOCK, "§6Estado: §f" + m.estado.rotulo,
                "§7Rodada: §f" + m.rodadaId(),
                "§7Clique: ver status no chat");
        colocar(inv, 10, Material.LIME_DYE, "§aAbrir escolha de times", "§7Libera o menu de times para os jogadores");
        colocar(inv, 11, Material.COMPASS, "§ePreparar rodada", "§7Leva os dois times aos spawns");
        colocar(inv, 12, Material.EMERALD, "§aLiberar arena", "§7Contagem e início do combate");
        colocar(inv, 13, Material.ORANGE_DYE, "§6Cancelar rodada", "§7Volta todos ao lobby do evento");
        colocar(inv, 14, Material.RED_DYE, "§cCancelar evento", "§7Restaura os inventários");
        colocar(inv, 15, Material.BARRIER, "§4Encerrar evento", "§7Fim do evento");

        Time l1 = m.local1();
        Time l2 = m.local2();
        colocar(inv, 19, l1 != null ? l1.lana : Material.GRAY_WOOL, "§fLocal 1: " + (l1 != null ? l1.colorido() : "§7nenhum"),
                "§7Clique para trocar o time");
        colocar(inv, 20, l2 != null ? l2.lana : Material.GRAY_WOOL, "§fLocal 2: " + (l2 != null ? l2.colorido() : "§7nenhum"),
                "§7Clique para trocar o time");

        colocar(inv, 28, Material.RED_BED, "§eLobby do evento", marca(m.posicao("lobby")), "§7Clique: define na sua posição");
        colocar(inv, 29, Material.OAK_DOOR, "§eEntrada", marca(m.posicao("entrada")), "§7Clique: define na sua posição");
        colocar(inv, 30, Material.WHITE_BANNER, "§eSpawn do Local 1", marca(m.posicao("spawn1")), "§7Clique: define na sua posição");
        colocar(inv, 31, Material.BLACK_BANNER, "§eSpawn do Local 2", marca(m.posicao("spawn2")), "§7Clique: define na sua posição");
        colocar(inv, 32, Material.ENDER_EYE, "§eEspectadores", marca(m.posicao("espectador")), "§7Clique: define na sua posição");

        Time[] ts = Time.values();
        for (int i = 0; i < ts.length; i++) {
            colocar(inv, ENC[i], ts[i].lana, "§fEncerrar rodada: " + ts[i].colorido(), "§7Dá a vitória a este time");
        }

        colocar(inv, 45, Material.NAME_TAG, "§6Ranking flutuante: colocar aqui",
                m.holo.definido() ? "§aJá existe (clique move)" : "§7Ainda não existe",
                "§7Fica acima da sua posição");
        colocar(inv, 46, Material.SHEARS, "§cRemover ranking flutuante");
        colocar(inv, 47, Material.TNT, "§4Zerar ranking de kills", "§7Use SHIFT + clique");
        colocar(inv, 49, Material.BOOK, "§bLista de times", "§7Clique: mostra no chat");
        colocar(inv, 53, Material.ARROW, "§7Fechar");
        p.openInventory(inv);
    }

    void aoClicar(Player p, int slot) {
        aoClicar(p, slot, false);
    }

    void aoClicar(Player p, int slot, boolean shift) {
        switch (slot) {
            case 4 -> p.performCommand("natal status");
            case 10 -> m.abrirSelecao(p);
            case 11 -> m.preparar(p);
            case 12 -> m.liberar(p);
            case 13 -> m.cancelarRodada(p);
            case 14 -> m.finalizarEvento(p, EstadoNatal.EVENTO_CANCELADO);
            case 15 -> m.finalizarEvento(p, EstadoNatal.EVENTO_ENCERRADO);
            case 19 -> ciclar(p, 1);
            case 20 -> ciclar(p, 2);
            case 28 -> ponto(p, "lobby", "Lobby do evento");
            case 29 -> ponto(p, "entrada", "Entrada");
            case 30 -> ponto(p, "spawn1", "Spawn do Local 1");
            case 31 -> ponto(p, "spawn2", "Spawn do Local 2");
            case 32 -> ponto(p, "espectador", "Ponto dos espectadores");
            case 37, 38, 39, 40 -> m.encerrarRodada(p, Time.values()[slot - 37]);
            case 45 -> {
                m.holo.definir(p.getLocation());
                m.enviar(p, "§aRanking flutuante colocado aqui.");
            }
            case 46 -> {
                m.holo.remover();
                m.enviar(p, "§aRanking flutuante removido.");
            }
            case 47 -> {
                if (shift) {
                    m.ranking.zerar();
                    m.atualizarVisual();
                    m.holo.atualizar();
                    m.enviar(p, "§aRanking zerado.");
                } else {
                    m.enviar(p, "§eSegure SHIFT e clique para zerar.");
                    return;
                }
            }
            case 49 -> p.performCommand("natal times");
            case 53 -> {
                p.closeInventory();
                return;
            }
            default -> {
                return;
            }
        }
        if (p.isOnline() && p.getOpenInventory().getTopInventory().getHolder(false) instanceof Holder) {
            abrir(p);
        }
    }

    private void ponto(Player p, String chave, String nome) {
        if (!m.noMundo(p)) {
            m.enviar(p, "§cEntre no mundo do evento para definir este ponto.");
            return;
        }
        m.definirPosicao(chave, p.getLocation());
        m.enviar(p, "§a" + nome + " definido aqui.");
    }

    /** Passa para o próximo time livre (pula o time do outro local); depois do último, limpa. */
    private void ciclar(Player p, int local) {
        Time atual = local == 1 ? m.local1() : m.local2();
        Time outro = local == 1 ? m.local2() : m.local1();
        Time[] ts = Time.values();
        int i = atual == null ? -1 : atual.ordinal();
        for (int k = 1; k <= ts.length; k++) {
            Time cand = ts[(i + k) % ts.length];
            if (cand != outro) {
                String erro = m.definirLocal(local, cand);
                if (erro != null) {
                    m.enviar(p, "§c" + erro);
                } else {
                    m.atualizarVisual();
                }
                return;
            }
        }
    }
}
