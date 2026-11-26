package br.com.asthen.eventobot.natal;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Regras do Natal que dependem de eventos do jogo: isolamento do inventário, bloqueios, combate por
 * bolinha de neve e comandos. Todas as proteções valem só para jogadores comuns no mundo do evento
 * (administradores com eventobot.natal.admin ficam isentos).
 */
final class NatalListener implements Listener {

    private static final List<String> SEMPRE_BLOQUEADOS = List.of("lobby", "hub", "lob", "spawn", "l");

    private final NatalModule m;

    NatalListener(NatalModule m) {
        this.m = m;
    }

    private String tag(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(m.chaveItem, PersistentDataType.STRING);
    }

    // ------------------------------------------------------------------ entrada e saída

    @EventHandler
    public void aoEntrarNoServidor(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        m.aoEntrarNoServidor(p);
        // Espera os outros plugins (ex.: o teleporte de entrada) terminarem antes de decidir.
        Bukkit.getScheduler().runTaskLater(m.plugin(), () -> {
            if (p.isOnline() && m.noMundo(p)) {
                m.entrar(p);
            }
        }, 5L);
    }

    @EventHandler
    public void aoSairDoServidor(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (m.noEventoAgora(p.getUniqueId())) {
            m.sair(p, true);
        }
    }

    @EventHandler
    public void aoTrocarDeMundo(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        if (m.noEventoAgora(p.getUniqueId()) && !m.noMundo(p)) {
            m.sair(p, false);
        }
        if (m.noMundo(p)) {
            m.entrar(p);
        }
    }

    // ------------------------------------------------------------------ combate (só bolinhas de neve)

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void aoLancar(ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof Snowball bola) || !(bola.getShooter() instanceof Player p)) {
            return;
        }
        if (!m.restrito(p)) {
            return;
        }
        NatalModule.Participante pt = m.participante(p.getUniqueId());
        boolean pode = pt != null && m.combateAtivo() && pt.naRodada && pt.status == Status.ATIVO;
        if (!pode) {
            e.setCancelled(true);
            Bukkit.getScheduler().runTask(m.plugin(), () -> m.reporBolinhas(p));
            return;
        }
        // Marca a bolinha com a rodada: só acertos da rodada atual contam.
        bola.getPersistentDataContainer().set(m.chaveRodada, PersistentDataType.INTEGER, m.rodadaId());
        int recarga = m.recargaBolinha();
        if (recarga > 0) {
            p.setCooldown(Material.SNOWBALL, recarga);
        }
        Bukkit.getScheduler().runTask(m.plugin(), () -> m.reporBolinhas(p));
    }

    @EventHandler
    public void aoImpacto(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof Snowball bola)) {
            return;
        }
        if (!bola.getPersistentDataContainer().has(m.chaveRodada, PersistentDataType.INTEGER)) {
            return;
        }
        if (e.getHitEntity() instanceof Player alvo) {
            m.registrarAcerto(bola, alvo);
        }
        bola.remove();
    }

    /** Ninguém causa dano a ninguém no evento: só a contagem de acertos elimina. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void aoDano(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player alvo && m.restrito(alvo)) {
            e.setCancelled(true);
            return;
        }
        if (e instanceof EntityDamageByEntityEvent d && d.getDamager() instanceof Player atacante
                && m.restrito(atacante)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void aoFome(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && m.restrito(p)) {
            e.setCancelled(true);
        }
    }

    /** Morte por qualquer outro meio (ex.: comando de admin): nada cai e nada é perdido. */
    @EventHandler
    public void aoMorrer(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (m.restrito(p)) {
            e.setKeepInventory(true);
            e.setKeepLevel(true);
            e.getDrops().clear();
            e.setDroppedExp(0);
        }
    }

    @EventHandler
    public void aoRenascer(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (m.restrito(p)) {
            Location l = m.destinoLobby();
            if (l != null) {
                e.setRespawnLocation(l);
            }
        }
    }

    // ------------------------------------------------------------------ movimento

    @EventHandler(ignoreCancelled = true)
    public void aoMover(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (!m.restrito(p)) {
            return;
        }
        Location para = e.getTo();
        if (para == null) {
            return;
        }
        World w = p.getWorld();
        if (para.getY() < w.getMinHeight() - 5) {
            // Caiu no vazio: volta ao lugar certo, fora do evento de movimento.
            Bukkit.getScheduler().runTask(m.plugin(), () -> {
                if (!p.isOnline() || !m.noMundo(p)) {
                    return;
                }
                NatalModule.Participante pt = m.participante(p.getUniqueId());
                Location destino = pt != null && pt.naRodada ? m.spawnDoTime(pt.time) : null;
                p.setFallDistance(0f);
                m.tp(p, destino != null ? destino : m.destinoLobby());
            });
            return;
        }
        if (e.hasChangedPosition() && m.forado(p, para)) {
            // Antes da liberação: não deixa sair do ponto, mas permite olhar ao redor.
            Location de = e.getFrom();
            Location corrigido = para.clone();
            corrigido.setX(de.getX());
            corrigido.setZ(de.getZ());
            e.setTo(corrigido);
        }
    }

    /** Teleportes de outros plugins/comandos não tiram ninguém do evento. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void aoTeleportar(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        if (!m.ativo() || m.admin(p)) {
            return;
        }
        World evento = m.mundo();
        if (evento == null || !e.getFrom().getWorld().equals(evento)) {
            return;
        }
        if (m.teleporteLiberado(p.getUniqueId())) {
            return;
        }
        e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void aoMudarModo(PlayerGameModeChangeEvent e) {
        Player p = e.getPlayer();
        if (m.restrito(p) && m.noEventoAgora(p.getUniqueId())) {
            // O plugin troca o modo por conta própria; mudanças externas são desfeitas.
            if (!m.teleporteLiberado(p.getUniqueId())) {
                Bukkit.getScheduler().runTask(m.plugin(), () -> {
                    NatalModule.Participante pt = m.participante(p.getUniqueId());
                    if (p.isOnline() && pt != null && m.restrito(p)) {
                        m.aplicarModo(p, pt);
                    }
                });
            }
        }
    }

    // ------------------------------------------------------------------ inventário e itens

    @EventHandler(priority = EventPriority.HIGH)
    public void aoClicarInventario(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player adm && m.painel.ehPainel(e.getView().getTopInventory())) {
            e.setCancelled(true);
            if (m.admin(adm) && e.getClickedInventory() == e.getView().getTopInventory()) {
                if (e.getView().getTopInventory().getHolder(false) instanceof NatalPainel.JHolder jh) {
                    m.painel.aoClicarJogadores(adm, jh, e.getSlot(), e.isRightClick());
                } else {
                    m.painel.aoClicar(adm, e.getSlot(), e.isShiftClick());
                }
            }
            return;
        }
        if (!(e.getWhoClicked() instanceof Player p) || !m.restrito(p)) {
            return;
        }
        e.setCancelled(true);
        if (m.menu.ehMenu(e.getView().getTopInventory())
                && e.getClickedInventory() == e.getView().getTopInventory()) {
            m.menu.aoClicar(p, e.getSlot());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void aoArrastar(InventoryDragEvent e) {
        if (m.painel.ehPainel(e.getView().getTopInventory())
                || e.getWhoClicked() instanceof Player p && m.restrito(p)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void aoAbrirInventario(InventoryOpenEvent e) {
        if (e.getPlayer() instanceof Player p && m.restrito(p) && !m.menu.ehMenu(e.getInventory()) && !m.painel.ehPainel(e.getInventory())) {
            e.setCancelled(true); // baús, ender chest, shulker, bancadas...
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void aoSoltar(PlayerDropItemEvent e) {
        if (m.restrito(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void aoPegar(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && m.restrito(p)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void aoTrocarMao(PlayerSwapHandItemsEvent e) {
        if (m.restrito(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void aoInteragir(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if ("painel".equals(tag(e.getItem()))) {
            boolean dir = e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK;
            if (dir && e.getHand() == org.bukkit.inventory.EquipmentSlot.HAND) {
                e.setCancelled(true);
                if (m.admin(p)) {
                    m.painel.abrir(p);
                } else {
                    p.getInventory().setItemInMainHand(null);
                }
            }
            return;
        }
        if (!m.restrito(p)) {
            return;
        }
        String t = tag(e.getItem());
        boolean direito = e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK;
        if (direito && "menu".equals(t)) {
            e.setCancelled(true);
            if (e.getHand() == org.bukkit.inventory.EquipmentSlot.HAND) {
                m.menu.abrir(p);
            }
            return;
        }
        // Nenhum bloco/mecanismo pode ser usado; só a bolinha de neve pode ser lançada.
        e.setUseInteractedBlock(Event.Result.DENY);
        if (!"bolinha".equals(t)) {
            e.setUseItemInHand(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void aoInteragirComEntidade(PlayerInteractEntityEvent e) {
        if (m.restrito(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void aoQuebrar(BlockBreakEvent e) {
        if (m.restrito(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void aoColocar(BlockPlaceEvent e) {
        if (m.restrito(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void aoManipularSuporte(PlayerArmorStandManipulateEvent e) {
        if (m.restrito(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void aoQuebrarQuadro(HangingBreakByEntityEvent e) {
        Entity remover = e.getRemover();
        if (remover instanceof Player p && m.restrito(p)) {
            e.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ comandos

    /** Dentro do evento só valem os comandos autorizados (e os de administradores). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void aoComando(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        boolean noEvento = m.restrito(p) || (m.noEventoAgora(id) && !m.admin(p));
        if (!noEvento) {
            return;
        }
        String msg = e.getMessage();
        if (msg.startsWith("/")) {
            msg = msg.substring(1);
        }
        String base = msg.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        int ns = base.indexOf(':');
        if (ns >= 0) {
            base = base.substring(ns + 1);
        }
        Command cmd = Bukkit.getCommandMap().getCommand(base);
        String nome = cmd != null ? cmd.getName().toLowerCase(Locale.ROOT) : base;
        // Comandos de saída para o lobby ficam sempre bloqueados (mesmo que o config os libere):
        // quem quiser sair usa /natal sair.
        java.util.Set<String> nomes = new java.util.HashSet<>(List.of(base, nome));
        if (cmd != null) {
            for (String a : cmd.getAliases()) {
                nomes.add(a.toLowerCase(Locale.ROOT));
            }
        }
        for (String bloqueado : SEMPRE_BLOQUEADOS) {
            if (nomes.contains(bloqueado)) {
                e.setCancelled(true);
                p.sendMessage(NatalModule.texto("§cVocê não pode sair assim durante o evento. Use §e/natal sair§c."));
                return;
            }
        }
        List<String> permitidos = m.comandosPermitidos();
        for (String permitido : permitidos) {
            String a = permitido.toLowerCase(Locale.ROOT);
            if (a.equals(nome) || a.equals(base)) {
                return;
            }
        }
        e.setCancelled(true);
        p.sendMessage(NatalModule.texto("§cEste comando não pode ser usado dentro do evento."));
    }
}
