package br.com.asthen.eventobot.natal;

import br.com.asthen.eventobot.EventoBotPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Evento de Natal: 4 times, rodadas de bolinha de neve entre dois times escolhidos pelo administrador.
 *
 * Tudo roda na thread principal do servidor, então as transições de estado são atômicas: uma rodada só
 * termina uma vez (o estado muda para RODADA_FINALIZADA antes de qualquer outra coisa).
 */
public final class NatalModule {

    /** Dados de um jogador no evento (guardados por UUID, nunca por nome). */
    static final class Participante {
        Time time;
        Status status = Status.LOBBY;
        int hits;
        boolean naRodada;
        boolean classificado;
    }

    private final EventoBotPlugin plugin;
    final Ranking ranking;
    final InventarioIsolado inventarios;
    final NatalPlacar placar;
    final NatalMenu menu;
    final NatalPainel painel;
    final NatalHolograma holo;
    final NamespacedKey chaveItem;
    final NamespacedKey chaveRodada;

    private final File arquivoDados;
    private YamlConfiguration dados;

    EstadoNatal estado = EstadoNatal.AGUARDANDO;
    final Map<UUID, Participante> participantes = new LinkedHashMap<>();
    private Time local1;
    private Time local2;
    private int rodadaId;
    private Time ultimoVencedor;

    private final Set<UUID> noEvento = new HashSet<>();
    private final Set<UUID> tpLiberado = new HashSet<>();
    private final Set<UUID> saidaLiberada = new HashSet<>();

    private BukkitTask tarefa;
    private BukkitTask contagemTarefa;
    private int ciclos;

    public NatalModule(EventoBotPlugin plugin) {
        this.plugin = plugin;
        this.chaveItem = new NamespacedKey(plugin, "natal_item");
        this.chaveRodada = new NamespacedKey(plugin, "natal_rodada");
        this.arquivoDados = new File(plugin.getDataFolder(), "natal.yml");
        this.ranking = new Ranking(plugin.getDataFolder(), plugin.getLogger());
        this.inventarios = new InventarioIsolado(plugin.getDataFolder(), plugin.getLogger());
        this.placar = new NatalPlacar(this);
        this.menu = new NatalMenu(this);
        this.painel = new NatalPainel(this);
        this.holo = new NatalHolograma(this);
        carregarDados();
    }

    // ------------------------------------------------------------------ ciclo de vida

    /** Registra comandos e listeners e inicia a tarefa periódica. */
    public void ajuda(CommandSender s) {
        NatalComando.ajuda(this, s, true);
    }

    public void registrar() {
        NatalComando comando = new NatalComando(this);
        var natal = plugin.getCommand("natal");
        if (natal != null) {
            natal.setExecutor(comando);
            natal.setTabCompleter(comando);
        }
        plugin.getServer().getPluginManager().registerEvents(new NatalListener(this), plugin);
        tarefa = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 10L);
        holo.iniciar();
    }

    /** Ao desligar o servidor/plugin: devolve inventários de quem está no evento e salva tudo. */
    public void desligar() {
        holo.parar();
        if (tarefa != null) {
            tarefa.cancel();
        }
        if (contagemTarefa != null) {
            contagemTarefa.cancel();
        }
        for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            if (noEvento.contains(p.getUniqueId()) || inventarios.existe(p.getUniqueId())) {
                placar.remover(p);
                normalizar(p);
                inventarios.restaurar(p);
                mostrarTodos(p);
            }
        }
        noEvento.clear();
        salvarDados();
    }

    /** O /event off fechou o mundo: a partida acaba e os participantes são esquecidos. */
    public void aoEventoFechado() {
        cancelarContagem();
        if (estado != EstadoNatal.EVENTO_CANCELADO) {
            estado = EstadoNatal.EVENTO_ENCERRADO;
        }
        limparProjeteis();
        participantes.clear();
        local1 = null;
        local2 = null;
        salvarDados();
    }

    /** Marca que o jogador pode sair do mundo do evento agora (teleporte do próprio EventoBot). */
    public void permitirSaida(Player p) {
        UUID id = p.getUniqueId();
        saidaLiberada.add(id);
        Bukkit.getScheduler().runTaskLater(plugin, () -> saidaLiberada.remove(id), 100L);
    }

    // ------------------------------------------------------------------ configuração

    boolean ativo() {
        return "natal".equalsIgnoreCase(plugin.getConfig().getString("evento-ativo", "natal"));
    }

    int maxPorTime() {
        return Math.max(1, plugin.getConfig().getInt("natal.max-por-time", 4));
    }

    int acertosParaEliminar() {
        return Math.max(1, plugin.getConfig().getInt("natal.acertos-para-eliminar", 3));
    }

    int minPorTime() {
        return Math.max(1, plugin.getConfig().getInt("natal.min-jogadores-por-time", 1));
    }

    double raioPreparacao() {
        return Math.max(0.5, plugin.getConfig().getDouble("natal.raio-preparacao", 3.0));
    }

    int contagemInicio() {
        return Math.max(0, plugin.getConfig().getInt("natal.contagem-inicio", 5));
    }

    int recargaBolinha() {
        return Math.max(0, plugin.getConfig().getInt("natal.recarga-bolinha-ticks", 5));
    }

    int bolinhasPorPilha() {
        return Math.max(1, Math.min(64, plugin.getConfig().getInt("natal.bolinhas-por-pilha", 16)));
    }

    int holoTop() {
        return plugin.getConfig().getInt("natal.holograma-top", 10);
    }

    int rankingTop() {
        return Math.max(1, plugin.getConfig().getInt("natal.ranking-top", 3));
    }

    List<String> comandosPermitidos() {
        return plugin.getConfig().getStringList("natal.comandos-permitidos");
    }

    // ------------------------------------------------------------------ utilidades

    World mundo() {
        return plugin.getMundoEvento();
    }

    boolean noMundo(Player p) {
        World w = mundo();
        return w != null && p.getWorld().equals(w);
    }

    boolean admin(Player p) {
        return p.hasPermission("eventobot.natal.admin");
    }

    /** Jogador comum dentro do mundo do evento (sujeito a isolamento e bloqueios). */
    boolean restrito(Player p) {
        return ativo() && noMundo(p) && !admin(p);
    }

    static boolean ehBedrock(UUID id) {
        // Os UUIDs do Floodgate têm os 64 bits mais significativos zerados.
        return id.getMostSignificantBits() == 0L;
    }

    Participante participante(UUID id) {
        return participantes.get(id);
    }

    Time local1() {
        return local1;
    }

    Time local2() {
        return local2;
    }

    int rodadaId() {
        return rodadaId;
    }

    boolean combateAtivo() {
        return estado == EstadoNatal.PARTIDA_EM_ANDAMENTO;
    }

    EventoBotPlugin plugin() {
        return plugin;
    }

    static Component texto(String legado) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                .deserialize(legado);
    }

    void enviar(CommandSender quem, String legado) {
        quem.sendMessage(texto("§8[§cNatal§8] §f" + legado));
    }

    private void aoJogadoresDoEvento(java.util.function.Consumer<Player> acao) {
        World w = mundo();
        if (w == null) {
            return;
        }
        for (Player p : new ArrayList<>(w.getPlayers())) {
            acao.accept(p);
        }
    }

    // ------------------------------------------------------------------ posições (natal.yml)

    Location posicao(String chave) {
        World w = mundo();
        if (w == null || !dados.contains(chave + ".x")) {
            return null;
        }
        return new Location(w, dados.getDouble(chave + ".x"), dados.getDouble(chave + ".y"),
                dados.getDouble(chave + ".z"), (float) dados.getDouble(chave + ".yaw"),
                (float) dados.getDouble(chave + ".pitch"));
    }

    void definirPosicao(String chave, Location l) {
        dados.set(chave + ".x", l.getX());
        dados.set(chave + ".y", l.getY());
        dados.set(chave + ".z", l.getZ());
        dados.set(chave + ".yaw", (double) l.getYaw());
        dados.set(chave + ".pitch", (double) l.getPitch());
        salvarDados();
    }

    private Location mundoSpawn() {
        World w = mundo();
        return w == null ? null : w.getSpawnLocation().add(0.5, 0, 0.5);
    }

    Location destinoLobby() {
        Location l = posicao("lobby");
        if (l == null) {
            l = posicao("entrada");
        }
        return l != null ? l : mundoSpawn();
    }

    Location destinoEntrada() {
        Location l = posicao("entrada");
        return l != null ? l : destinoLobby();
    }

    Location destinoEspectador() {
        Location l = posicao("espectador");
        if (l == null) {
            l = posicao("spawn1");
        }
        return l != null ? l : destinoEntrada();
    }

    Location spawnDoTime(Time t) {
        if (t == null) {
            return null;
        }
        if (t == local1) {
            return posicao("spawn1");
        }
        if (t == local2) {
            return posicao("spawn2");
        }
        return null;
    }

    private void carregarDados() {
        dados = YamlConfiguration.loadConfiguration(arquivoDados);
        EstadoNatal salvo;
        try {
            salvo = EstadoNatal.valueOf(dados.getString("estado", "AGUARDANDO"));
        } catch (IllegalArgumentException e) {
            salvo = EstadoNatal.AGUARDANDO;
        }
        rodadaId = dados.getInt("rodada", 0);
        if (salvo.emRodada()) {
            // O servidor caiu/reiniciou no meio da partida: cancela, sem tentar continuar.
            estado = EstadoNatal.EVENTO_CANCELADO;
            plugin.getLogger().warning("[Natal] A partida estava em andamento (" + salvo.rotulo
                    + ") quando o servidor parou. Evento CANCELADO; marque uma nova data. "
                    + "Os inventários guardados serão devolvidos quando os jogadores entrarem.");
            dados.set("estado", estado.name());
            dados.set("motivo-cancelamento", "interrupção do servidor");
            salvarDados();
        } else {
            estado = salvo == EstadoNatal.EVENTO_CANCELADO ? EstadoNatal.EVENTO_CANCELADO : EstadoNatal.AGUARDANDO;
        }
    }

    void salvarDados() {
        dados.set("estado", estado.name());
        dados.set("rodada", rodadaId);
        try {
            File pasta = arquivoDados.getParentFile();
            if (pasta != null) {
                pasta.mkdirs();
            }
            dados.save(arquivoDados);
        } catch (IOException e) {
            plugin.getLogger().warning("[Natal] Não consegui salvar natal.yml: " + e.getMessage());
        }
    }

    private void mudarEstado(EstadoNatal novo) {
        estado = novo;
        salvarDados();
        atualizarVisual();
    }

    void atualizarVisual() {
        placar.atualizarTodos();
        atualizarVisibilidade();
    }

    private boolean lutador(UUID id) {
        Participante pt = participantes.get(id);
        return pt != null && pt.naRodada && pt.status == Status.ATIVO;
    }

    /**
     * Durante a rodada, quem está lutando só enxerga quem também está lutando: espectadores, eliminados e
     * quem espera no lobby ficam invisíveis para eles (mesmo estando sobre a arena). Fora da rodada todos
     * se veem normalmente.
     */
    void atualizarVisibilidade() {
        List<Player> noEv = new ArrayList<>();
        for (UUID id : noEvento) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                noEv.add(p);
            }
        }
        boolean rodada = estado.emRodada();
        for (Player viewer : noEv) {
            boolean viewerLuta = rodada && lutador(viewer.getUniqueId());
            for (Player alvo : noEv) {
                if (alvo.equals(viewer)) {
                    continue;
                }
                if (viewerLuta && !lutador(alvo.getUniqueId())) {
                    viewer.hidePlayer(plugin, alvo);
                } else {
                    viewer.showPlayer(plugin, alvo);
                }
            }
        }
    }

    private final Map<UUID, org.bukkit.GameMode> modoAnterior = new HashMap<>();

    /** Admin: alterna o modo espectador para atravessar barreiras e blocos; volta ao modo anterior. */
    boolean alternarAtravessar(Player p) {
        org.bukkit.GameMode antes = modoAnterior.remove(p.getUniqueId());
        if (antes != null) {
            p.setGameMode(antes);
            return false;
        }
        modoAnterior.put(p.getUniqueId(), p.getGameMode() == org.bukkit.GameMode.SPECTATOR
                ? org.bukkit.GameMode.CREATIVE : p.getGameMode());
        p.setGameMode(org.bukkit.GameMode.SPECTATOR);
        return true;
    }

    /** Ao desconectar, o admin não fica preso no modo espectador. */
    void restaurarAtravessar(Player p) {
        org.bukkit.GameMode antes = modoAnterior.remove(p.getUniqueId());
        if (antes != null) {
            p.setGameMode(antes);
        }
    }

    /** Ao sair do evento: volta a ver todos e a ser visto por todos. */
    void mostrarTodos(Player p) {
        for (Player outro : Bukkit.getOnlinePlayers()) {
            if (!outro.equals(p)) {
                p.showPlayer(plugin, outro);
                outro.showPlayer(plugin, p);
            }
        }
    }

    // ------------------------------------------------------------------ itens

    private ItemStack item(Material m, String nome, String tag, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(texto(nome).decoration(TextDecoration.ITALIC, false));
        if (lore.length > 0) {
            List<Component> linhas = new ArrayList<>();
            for (String l : lore) {
                linhas.add(texto(l).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(linhas);
        }
        meta.getPersistentDataContainer().set(chaveItem, PersistentDataType.STRING, tag);
        it.setItemMeta(meta);
        return it;
    }

    ItemStack itemPainel() {
        return item(Material.NETHER_STAR, "§c§lPainel do Natal", "painel", "§7Clique direito: abre o painel de administração");
    }

    ItemStack itemMenu() {
        return item(Material.NETHER_STAR, "§6§lEscolher time", "menu",
                "§7Clique com o botão direito", "§7para escolher a sua equipe.");
    }

    ItemStack itemBolinhas() {
        ItemStack it = item(Material.SNOWBALL, "§f§lBolinha de Neve", "bolinha");
        it.setAmount(bolinhasPorPilha());
        return it;
    }

    private ItemStack peca(Material m, Time t) {
        ItemStack it = new ItemStack(m);
        LeatherArmorMeta meta = (LeatherArmorMeta) it.getItemMeta();
        meta.setColor(t.armadura);
        meta.displayName(texto(t.codigo + "Time " + t.nome).decoration(TextDecoration.ITALIC, false));
        meta.setUnbreakable(true);
        meta.getPersistentDataContainer().set(chaveItem, PersistentDataType.STRING, "uniforme");
        it.setItemMeta(meta);
        return it;
    }

    private void vestir(Player p, Time t) {
        if (t == null) {
            p.getInventory().setArmorContents(new ItemStack[4]);
            return;
        }
        p.getInventory().setHelmet(peca(Material.LEATHER_HELMET, t));
        p.getInventory().setChestplate(peca(Material.LEATHER_CHESTPLATE, t));
        p.getInventory().setLeggings(peca(Material.LEATHER_LEGGINGS, t));
        p.getInventory().setBoots(peca(Material.LEATHER_BOOTS, t));
    }

    /** Kit do lobby do evento: só a estrela de escolha (se ainda sem time) e o uniforme (se já tem time). */
    void darKitLobby(Player p, Participante pt) {
        inventarios.limpar(p);
        vestir(p, pt.time);
        if (pt.status == Status.LOBBY) {
            p.getInventory().setItem(4, itemMenu());
        }
    }

    /** Kit de combate: bolinhas infinitas e uniforme. Nada além disso. */
    void darKitCombate(Player p, Participante pt) {
        inventarios.limpar(p);
        vestir(p, pt.time);
        p.getInventory().setItem(0, itemBolinhas());
        p.getInventory().setHeldItemSlot(0);
    }

    void limparKit(Player p) {
        inventarios.limpar(p);
    }

    /** Repõe as bolinhas no slot 0 (infinitas). */
    void reporBolinhas(Player p) {
        Participante pt = participantes.get(p.getUniqueId());
        if (pt == null || !pt.naRodada || pt.status != Status.ATIVO) {
            return;
        }
        p.getInventory().setItem(0, itemBolinhas());
    }

    // ------------------------------------------------------------------ modo de jogo / aparência

    /** Volta o jogador ao estado "normal" do evento (aventura, sem invisibilidade). */
    void normalizar(Player p) {
        p.removePotionEffect(PotionEffectType.INVISIBILITY);
        p.setCollidable(true);
        if (!admin(p)) {
            p.setFlying(false);
            p.setAllowFlight(false);
        }
        p.setGameMode(GameMode.ADVENTURE);
    }

    private void aplicarEspectador(Player p) {
        limparKit(p);
        if (ehBedrock(p.getUniqueId())) {
            // Bedrock não tem modo espectador de verdade: aventura + voo + invisível + sem colisão.
            p.setGameMode(GameMode.ADVENTURE);
            p.setAllowFlight(true);
            p.setFlying(true);
            p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY,
                    PotionEffect.INFINITE_DURATION, 0, false, false, false));
            p.setCollidable(false);
        } else {
            p.setGameMode(GameMode.SPECTATOR);
        }
    }

    /** Aplica o modo certo conforme o status e o estado atual do evento. */
    void aplicarModo(Player p, Participante pt) {
        boolean espectando = (pt.status == Status.ELIMINADO || pt.status == Status.ESPECTADOR)
                && estado.emRodada();
        if (espectando) {
            aplicarEspectador(p);
        } else {
            normalizar(p);
        }
    }

    /** Teleporte do próprio plugin (liberado para o bloqueio de teleportes). */
    void tp(Player p, Location destino) {
        if (destino == null) {
            return;
        }
        UUID id = p.getUniqueId();
        tpLiberado.add(id);
        try {
            p.teleport(destino);
        } finally {
            tpLiberado.remove(id);
        }
    }

    boolean teleporteLiberado(UUID id) {
        return tpLiberado.contains(id) || saidaLiberada.contains(id);
    }

    // ------------------------------------------------------------------ entrada e saída

    /** O jogador entrou no mundo do evento: isola o inventário e o coloca no fluxo certo. */
    public void entrar(Player p) {
        if (!ativo() || !noMundo(p) || admin(p)) {
            return;
        }
        UUID id = p.getUniqueId();
        if (!noEvento.add(id)) {
            return;
        }
        if (!inventarios.isolar(p)) {
            noEvento.remove(id);
            p.sendMessage(texto("§cNão foi possível guardar o seu inventário com segurança. Você não entrou no evento."));
            Location saida = plugin.lobbyPrincipal();
            permitirSaida(p);
            if (saida != null) {
                p.teleportAsync(saida);
            }
            return;
        }
        Participante pt = participantes.computeIfAbsent(id, k -> new Participante());
        placar.mostrar(p);

        switch (estado) {
            case PREPARACAO, TIMES_POSICIONADOS -> {
                if (pt.naRodada && pt.status == Status.ATIVO && spawnDoTime(pt.time) != null) {
                    darKitCombate(p, pt);
                    normalizar(p);
                    tp(p, spawnDoTime(pt.time));
                } else {
                    entrarComoEspectador(p, pt);
                }
            }
            case ARENA_LIBERADA, PARTIDA_EM_ANDAMENTO -> entrarComoEspectador(p, pt);
            default -> {
                if (pt.status == Status.ELIMINADO) {
                    pt.status = Status.ESPECTADOR;
                }
                darKitLobby(p, pt);
                normalizar(p);
                tp(p, destinoEntrada());
            }
        }
        atualizarVisual();
    }

    private void entrarComoEspectador(Player p, Participante pt) {
        if (pt.status != Status.ELIMINADO) {
            pt.status = Status.ESPECTADOR;
        }
        pt.naRodada = false;
        aplicarModo(p, pt);
        tp(p, destinoEspectador());
        p.sendMessage(texto("§eUma partida já está em andamento. Você assiste como espectador."));
    }

    /**
     * O jogador saiu do mundo do evento (troca de mundo, desconexão): devolve o inventário e aplica as
     * regras de abandono. Seguro de chamar mais de uma vez.
     */
    public void sair(Player p, boolean desconectou) {
        UUID id = p.getUniqueId();
        boolean estava = noEvento.remove(id);
        Participante pt = participantes.get(id);
        if (estava && pt != null && pt.naRodada && pt.status == Status.ATIVO) {
            if (estado == EstadoNatal.ARENA_LIBERADA || estado == EstadoNatal.PARTIDA_EM_ANDAMENTO) {
                eliminar(null, id, pt, null, null, desconectou ? "desconexão" : "saiu da arena");
            } else if (estado == EstadoNatal.PREPARACAO || estado == EstadoNatal.TIMES_POSICIONADOS) {
                pt.naRodada = false;
                pt.status = Status.LOBBY;
            }
        }
        if (!estava && !inventarios.existe(id)) {
            return; // não estava no evento: nada para devolver
        }
        placar.remover(p);
        normalizar(p);
        inventarios.restaurar(p);
        mostrarTodos(p);
        saidaLiberada.remove(id);
        atualizarVisual();
    }

    /** Ao logar: se sobrou backup (queda do servidor, falha), devolve o inventário original antes de tudo. */
    public void aoEntrarNoServidor(Player p) {
        if (inventarios.existe(p.getUniqueId()) && !noEvento.contains(p.getUniqueId())) {
            placar.remover(p);
            normalizar(p);
            if (inventarios.restaurar(p)) {
                p.sendMessage(texto("§aSeu inventário original foi restaurado."));
            }
        }
    }

    // ------------------------------------------------------------------ escolha de time

    List<Participante> membros(Time t) {
        List<Participante> lista = new ArrayList<>();
        for (Participante pt : participantes.values()) {
            if (pt.time == t) {
                lista.add(pt);
            }
        }
        return lista;
    }

    int registrados(Time t) {
        return membros(t).size();
    }

    List<Player> presentes(Time t) {
        List<Player> lista = new ArrayList<>();
        for (Map.Entry<UUID, Participante> e : participantes.entrySet()) {
            if (e.getValue().time != t) {
                continue;
            }
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null && noEvento.contains(e.getKey()) && noMundo(p)) {
                lista.add(p);
            }
        }
        return lista;
    }

    int ativos(Time t) {
        int n = 0;
        for (Participante pt : participantes.values()) {
            if (pt.time == t && pt.naRodada && pt.status == Status.ATIVO) {
                n++;
            }
        }
        return n;
    }

    /** Resultado de uma tentativa de escolher time; null = deu certo, senão a mensagem de erro. */
    String escolherTime(Player p, Time t) {
        Participante pt = participantes.get(p.getUniqueId());
        if (pt == null || !noEvento.contains(p.getUniqueId())) {
            return "Você precisa estar no evento para escolher um time.";
        }
        if (!estado.permiteEscolherTime()) {
            return estado == EstadoNatal.AGUARDANDO
                    ? "Aguarde: o administrador ainda não abriu a escolha de times."
                    : "A escolha de times não está disponível agora.";
        }
        if (pt.status != Status.LOBBY) {
            return "Você não pode escolher um time agora.";
        }
        if (pt.time != null) {
            return "Você já está no time " + pt.time.colorido() + "§c e não pode trocar.";
        }
        if (registrados(t) >= maxPorTime()) {
            return "O time " + t.colorido() + "§c está cheio.";
        }
        pt.time = t;
        darKitLobby(p, pt);
        p.sendMessage(texto("§aVocê entrou no time " + t.colorido() + "§a!"));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        atualizarVisual();
        return null;
    }

    /** Admin: tira o time do jogador (ele poderá escolher de novo). */
    boolean removerDoTime(UUID id) {
        Participante pt = participantes.get(id);
        if (pt == null || pt.time == null) {
            return false;
        }
        if (pt.naRodada) {
            return false;
        }
        pt.time = null;
        pt.status = Status.LOBBY;
        pt.classificado = false;
        Player p = Bukkit.getPlayer(id);
        if (p != null && noEvento.contains(id)) {
            darKitLobby(p, pt);
            normalizar(p);
        }
        atualizarVisual();
        return true;
    }

    /**
     * Admin: tira o jogador de espectador/eliminado (ou da rodada) e o devolve ao lobby do evento como
     * jogador comum, mantendo o time.
     */
    boolean resetarJogador(UUID id) {
        Participante pt = participantes.get(id);
        if (pt == null) {
            return false;
        }
        boolean estavaNaRodada = pt.naRodada;
        pt.status = Status.LOBBY;
        pt.hits = 0;
        pt.naRodada = false;
        Player p = Bukkit.getPlayer(id);
        if (p != null && noEvento.contains(id)) {
            darKitLobby(p, pt);
            normalizar(p);
            tp(p, destinoLobby());
        }
        atualizarVisual();
        if (estavaNaRodada) {
            verificarFim();
        }
        return true;
    }

    /** Admin: tira o jogador do time (se estava numa rodada, ele sai dela primeiro). */
    boolean removerDoTimeForcado(UUID id) {
        if (!participantes.containsKey(id)) {
            return false;
        }
        resetarJogador(id);
        return removerDoTime(id);
    }

    // ------------------------------------------------------------------ administração das rodadas

    void abrirSelecao(CommandSender s) {
        if (estado != EstadoNatal.AGUARDANDO && estado != EstadoNatal.EVENTO_ENCERRADO
                && estado != EstadoNatal.EVENTO_CANCELADO) {
            enviar(s, "§cO evento já está em andamento (estado: " + estado.rotulo + ").");
            return;
        }
        participantes.values().forEach(pt -> {
            pt.time = null;
            pt.status = Status.LOBBY;
            pt.hits = 0;
            pt.naRodada = false;
            pt.classificado = false;
        });
        local1 = null;
        local2 = null;
        mudarEstado(EstadoNatal.SELECAO_EQUIPES);
        aoJogadoresDoEvento(p -> {
            Participante pt = participantes.get(p.getUniqueId());
            if (pt != null && noEvento.contains(p.getUniqueId())) {
                darKitLobby(p, pt);
                p.sendMessage(texto("§aA escolha de times foi aberta! Use a estrela para escolher a sua equipe."));
            }
        });
        enviar(s, "§aEscolha de times aberta.");
    }

    String definirLocal(int numero, Time t) {
        if (!(estado == EstadoNatal.SELECAO_EQUIPES || estado == EstadoNatal.RODADA_FINALIZADA)) {
            return "Só dá para escolher os times da rodada na seleção de equipes ou entre rodadas.";
        }
        Time outro = numero == 1 ? local2 : local1;
        if (outro == t) {
            return "O time " + t.nome + " já está no Local " + (numero == 1 ? 2 : 1) + ".";
        }
        if (numero == 1) {
            local1 = t;
        } else {
            local2 = t;
        }
        return null;
    }

    /** Verifica as condições e informa o motivo se não puder preparar. Retorna null se estiver tudo certo. */
    String verificarPreparacao() {
        if (!(estado == EstadoNatal.SELECAO_EQUIPES || estado == EstadoNatal.RODADA_FINALIZADA)) {
            return "O estado atual (" + estado.rotulo + ") não permite preparar uma rodada.";
        }
        if (local1 == null || local2 == null) {
            return "Escolha os dois times: /natal <cor> local 1 e /natal <cor> local 2.";
        }
        if (local1 == local2) {
            return "O mesmo time não pode ocupar o Local 1 e o Local 2.";
        }
        if (posicao("spawn1") == null || posicao("spawn2") == null) {
            return "Defina os spawns da arena: /natal setspawn 1 e /natal setspawn 2.";
        }
        if (destinoLobby() == null) {
            return "O mundo do evento não está carregado.";
        }
        if (presentes(local1).size() < minPorTime() || presentes(local2).size() < minPorTime()) {
            return "Cada time precisa de pelo menos " + minPorTime() + " jogador(es) presente(s) no evento.";
        }
        return null;
    }

    void preparar(CommandSender s) {
        String erro = verificarPreparacao();
        if (erro != null) {
            enviar(s, "§c" + erro);
            return;
        }
        rodadaId++;
        limparProjeteis();
        for (Participante pt : participantes.values()) {
            pt.naRodada = false;
            pt.hits = 0;
        }
        posicionar(local1, posicao("spawn1"));
        posicionar(local2, posicao("spawn2"));
        mudarEstado(EstadoNatal.PREPARACAO);
        for (Time t : new Time[] {local1, local2}) {
            int ausentes = registrados(t) - presentes(t).size();
            if (ausentes > 0) {
                enviar(s, "§e" + ausentes + " jogador(es) do time " + t.colorido()
                        + "§e não estão no evento e ficam de fora desta rodada.");
            }
        }
        anunciarAosParticipantes("§eRodada " + rodadaId + ": " + local1.colorido() + "§e contra "
                + local2.colorido() + "§e! Aguarde no seu ponto até a arena ser liberada.");
        enviar(s, "§aPreparação iniciada. Os times estão sendo posicionados.");
    }

    private void posicionar(Time t, Location spawn) {
        List<Player> lista = presentes(t);
        int n = lista.size();
        for (int i = 0; i < n; i++) {
            Player p = lista.get(i);
            Participante pt = participantes.get(p.getUniqueId());
            if (pt == null || pt.status == Status.ESPECTADOR || pt.status == Status.ELIMINADO) {
                continue;
            }
            pt.naRodada = true;
            pt.status = Status.ATIVO;
            pt.hits = 0;
            Location l = spawn.clone();
            if (n > 1) {
                double ang = 2 * Math.PI * i / n;
                l.add(Math.cos(ang) * 1.2, 0, Math.sin(ang) * 1.2);
            }
            darKitCombate(p, pt);
            normalizar(p);
            tp(p, l);
        }
    }

    /** Todos os jogadores da rodada estão presentes e dentro do raio de preparação do seu ponto. */
    boolean prontos() {
        if (local1 == null || local2 == null) {
            return false;
        }
        if (ativos(local1) < minPorTime() || ativos(local2) < minPorTime()) {
            return false;
        }
        double limite = raioPreparacao() + 2.0;
        for (Map.Entry<UUID, Participante> e : participantes.entrySet()) {
            Participante pt = e.getValue();
            if (!pt.naRodada || pt.status != Status.ATIVO) {
                continue;
            }
            Player p = Bukkit.getPlayer(e.getKey());
            Location spawn = spawnDoTime(pt.time);
            if (p == null || spawn == null || !noMundo(p) || !noEvento.contains(e.getKey())) {
                return false;
            }
            if (p.getLocation().distanceSquared(spawn) > limite * limite) {
                return false;
            }
        }
        return true;
    }

    void liberar(CommandSender s) {
        if (estado != EstadoNatal.TIMES_POSICIONADOS) {
            enviar(s, "§cSó dá para liberar a arena quando os dois times estiverem posicionados "
                    + "(estado atual: " + estado.rotulo + ").");
            return;
        }
        if (!prontos()) {
            enviar(s, "§cOs times ainda não estão todos posicionados.");
            return;
        }
        mudarEstado(EstadoNatal.ARENA_LIBERADA);
        enviar(s, "§aArena liberada. Contagem iniciada.");
        iniciarContagem();
    }

    private void iniciarContagem() {
        cancelarContagem();
        final int[] restante = {contagemInicio()};
        if (restante[0] <= 0) {
            iniciarCombate();
            return;
        }
        contagemTarefa = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (estado != EstadoNatal.ARENA_LIBERADA) {
                cancelarContagem();
                return;
            }
            if (restante[0] <= 0) {
                cancelarContagem();
                iniciarCombate();
                return;
            }
            final int n = restante[0];
            aoParticipantesOnline(p -> {
                p.showTitle(Title.title(Component.text(String.valueOf(n), NamedTextColor.GOLD),
                        Component.text("Preparem-se!", NamedTextColor.YELLOW)));
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1f);
            });
            restante[0]--;
        }, 0L, 20L);
    }

    private void cancelarContagem() {
        if (contagemTarefa != null) {
            contagemTarefa.cancel();
            contagemTarefa = null;
        }
    }

    private void iniciarCombate() {
        if (estado != EstadoNatal.ARENA_LIBERADA) {
            return;
        }
        mudarEstado(EstadoNatal.PARTIDA_EM_ANDAMENTO);
        aoParticipantesOnline(p -> {
            p.showTitle(Title.title(Component.text("VALENDO!", NamedTextColor.RED),
                    Component.text("3 acertos eliminam", NamedTextColor.YELLOW)));
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.2f);
        });
    }

    private void aoParticipantesOnline(java.util.function.Consumer<Player> acao) {
        aoJogadoresDoEvento(p -> {
            if (noEvento.contains(p.getUniqueId())) {
                acao.accept(p);
            }
        });
    }

    private void anunciarAosParticipantes(String legado) {
        aoParticipantesOnline(p -> p.sendMessage(texto("§8[§cNatal§8] §f" + legado)));
    }

    // ------------------------------------------------------------------ combate

    /** Chamado quando uma bolinha acerta um jogador. Valida tudo antes de contar o acerto. */
    void registrarAcerto(Snowball bola, Player alvo) {
        if (estado != EstadoNatal.PARTIDA_EM_ANDAMENTO) {
            return;
        }
        Integer id = bola.getPersistentDataContainer().get(chaveRodada, PersistentDataType.INTEGER);
        if (id == null || id != rodadaId) {
            return; // lançada fora desta rodada
        }
        ProjectileSource fonte = bola.getShooter();
        if (!(fonte instanceof Player atacante) || atacante.equals(alvo)) {
            return;
        }
        Participante pa = participantes.get(atacante.getUniqueId());
        Participante pt = participantes.get(alvo.getUniqueId());
        if (pa == null || pt == null) {
            return;
        }
        if (!pa.naRodada || pa.status != Status.ATIVO || !pt.naRodada || pt.status != Status.ATIVO) {
            return;
        }
        if (pa.time == null || pa.time == pt.time) {
            return; // fogo amigo não conta
        }
        pt.hits++;
        int total = acertosParaEliminar();
        atacante.playSound(atacante.getLocation(), Sound.ENTITY_ARROW_HIT_PLAYER, 1f, 1f);
        alvo.playSound(alvo.getLocation(), Sound.ENTITY_PLAYER_HURT, 1f, 1.3f);
        atacante.sendActionBar(texto("§aAcerto em §f" + alvo.getName() + " §7(" + Math.min(pt.hits, total) + "/" + total + ")"));
        alvo.sendActionBar(texto("§cVocê foi atingido por §f" + atacante.getName() + " §7(" + Math.min(pt.hits, total) + "/" + total + ")"));
        if (pt.hits < total) {
            alvo.showTitle(Title.title(Component.text("⚠ ATINGIDO!", NamedTextColor.RED),
                    Component.text("Acertos: " + pt.hits + "/" + total, NamedTextColor.YELLOW),
                    Title.Times.times(java.time.Duration.ZERO, java.time.Duration.ofMillis(800),
                            java.time.Duration.ofMillis(200))));
        }
        if (pt.hits >= total) {
            eliminar(alvo, alvo.getUniqueId(), pt, atacante, pa, "acerto");
        }
    }

    /**
     * Elimina um participante da rodada. {@code alvo} pode ser null (desconexão). O status ATIVO é
     * conferido primeiro, então uma eliminação nunca é processada duas vezes.
     */
    void eliminar(Player alvo, UUID id, Participante pt, Player matador, Participante pm, String motivo) {
        if (pt.status != Status.ATIVO || !pt.naRodada) {
            return;
        }
        pt.status = Status.ELIMINADO;
        pt.hits = 0;
        boolean killValida = matador != null && pm != null && pm.time != null && pm.time != pt.time
                && estado == EstadoNatal.PARTIDA_EM_ANDAMENTO;
        if (killValida) {
            ranking.adicionarKill(matador.getUniqueId(), matador.getName());
        }
        String nome = alvo != null ? alvo.getName() : nomeDe(id);
        String corTime = pt.time != null ? pt.time.codigo : "§f";
        anunciarAosParticipantes(corTime + nome + "§7 foi eliminado"
                + (killValida ? " por §f" + matador.getName() : " (" + motivo + ")") + "§7.");
        if (alvo != null && alvo.isOnline()) {
            aplicarEspectador(alvo);
            alvo.showTitle(Title.title(Component.text("Eliminado", NamedTextColor.RED),
                    Component.text("Agora você assiste a partida", NamedTextColor.GRAY)));
        }
        atualizarVisual();
        verificarFim();
    }

    private String nomeDe(UUID id) {
        var off = Bukkit.getOfflinePlayer(id);
        return off.getName() != null ? off.getName() : "?";
    }

    /** Fim de rodada: quando um dos dois times fica sem jogadores ativos. */
    private void verificarFim() {
        if (estado != EstadoNatal.PARTIDA_EM_ANDAMENTO || local1 == null || local2 == null) {
            return;
        }
        int a1 = ativos(local1);
        int a2 = ativos(local2);
        if (a1 == 0 && a2 == 0) {
            cancelarRodadaInterno("Os dois times ficaram sem jogadores. Rodada cancelada.");
        } else if (a1 == 0) {
            finalizarRodada(local2);
        } else if (a2 == 0) {
            finalizarRodada(local1);
        }
    }

    private void limparProjeteis() {
        World w = mundo();
        if (w == null) {
            return;
        }
        for (Entity e : w.getEntitiesByClass(Snowball.class)) {
            if (e.getPersistentDataContainer().has(chaveRodada, PersistentDataType.INTEGER)) {
                e.remove();
            }
        }
    }

    /** Fecha a rodada com um vencedor. A mudança de estado vem primeiro: só acontece uma vez. */
    void finalizarRodada(Time vencedor) {
        if (!estado.emRodada() || local1 == null || local2 == null) {
            return;
        }
        Time perdedor = vencedor == local1 ? local2 : local1;
        estado = EstadoNatal.RODADA_FINALIZADA;
        cancelarContagem();
        limparProjeteis();
        ultimoVencedor = vencedor;

        for (Participante pt : participantes.values()) {
            boolean daRodada = pt.naRodada;
            pt.naRodada = false;
            pt.hits = 0;
            if (daRodada && pt.time == vencedor) {
                pt.classificado = true;
                pt.status = Status.LOBBY;
            } else if (daRodada && pt.time == perdedor) {
                pt.status = Status.ESPECTADOR;
            } else if (pt.status == Status.ELIMINADO) {
                pt.status = Status.ESPECTADOR;
            }
        }
        voltarAoLobbyDoEvento();
        local1 = null;
        local2 = null;
        salvarDados();
        atualizarVisual();
        aoParticipantesOnline(p -> {
            p.showTitle(Title.title(texto(vencedor.codigo + "Time " + vencedor.nome + " venceu!"),
                    Component.text("Rodada " + rodadaId + " finalizada", NamedTextColor.GRAY)));
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        });
    }

    private void cancelarRodadaInterno(String motivo) {
        if (!estado.emRodada()) {
            return;
        }
        estado = EstadoNatal.SELECAO_EQUIPES;
        cancelarContagem();
        limparProjeteis();
        for (Participante pt : participantes.values()) {
            if (pt.status == Status.ATIVO || pt.status == Status.ELIMINADO) {
                pt.status = Status.LOBBY;
            }
            pt.naRodada = false;
            pt.hits = 0;
        }
        voltarAoLobbyDoEvento();
        local1 = null;
        local2 = null;
        salvarDados();
        atualizarVisual();
        anunciarAosParticipantes("§e" + motivo);
    }

    void cancelarRodada(CommandSender s) {
        if (!estado.emRodada()) {
            enviar(s, "§cNão há rodada em andamento.");
            return;
        }
        cancelarRodadaInterno("A rodada foi cancelada pelo administrador.");
        enviar(s, "§aRodada cancelada. Os times voltaram ao lobby.");
    }

    void encerrarRodada(CommandSender s, Time vencedor) {
        if (!estado.emRodada()) {
            enviar(s, "§cNão há rodada em andamento.");
            return;
        }
        if (vencedor != local1 && vencedor != local2) {
            enviar(s, "§cO vencedor precisa ser um dos dois times da rodada.");
            return;
        }
        finalizarRodada(vencedor);
        enviar(s, "§aRodada encerrada. Vencedor: " + vencedor.colorido() + "§a.");
    }

    /** Leva todos do evento de volta ao lobby do evento e reaplica kit e modo conforme o status. */
    private void voltarAoLobbyDoEvento() {
        aoParticipantesOnline(p -> {
            Participante pt = participantes.get(p.getUniqueId());
            if (pt == null) {
                return;
            }
            darKitLobby(p, pt);
            normalizar(p);
            tp(p, destinoLobby());
        });
    }

    /** Cancela ou encerra o evento inteiro e manda todos de volta ao lobby principal. */
    void finalizarEvento(CommandSender s, EstadoNatal fim) {
        cancelarContagem();
        limparProjeteis();
        Location saida = plugin.lobbyPrincipal();
        aoJogadoresDoEvento(p -> {
            if (admin(p)) {
                return;
            }
            permitirSaida(p);
            if (saida != null) {
                p.teleportAsync(saida);
            }
        });
        participantes.clear();
        local1 = null;
        local2 = null;
        mudarEstado(fim);
        enviar(s, fim == EstadoNatal.EVENTO_CANCELADO ? "§aEvento cancelado." : "§aEvento encerrado.");
    }

    // ------------------------------------------------------------------ tarefa periódica

    private void tick() {
        if (!ativo()) {
            return;
        }
        if (estado == EstadoNatal.PREPARACAO && prontos()) {
            mudarEstado(EstadoNatal.TIMES_POSICIONADOS);
            aoJogadoresDoEvento(p -> {
                if (admin(p)) {
                    enviar(p, "§aOs dois times estão posicionados. Use §e/natal liberar§a para liberar a arena.");
                }
            });
        } else if (estado == EstadoNatal.TIMES_POSICIONADOS && !prontos()) {
            mudarEstado(EstadoNatal.PREPARACAO);
        }
        if (++ciclos % 4 == 0) {
            atualizarVisual();
        }
    }

    // ------------------------------------------------------------------ restrição de movimento

    /** Antes da liberação, o jogador ativo não pode se afastar do seu ponto. */
    boolean forado(Player p, Location para) {
        if (estado != EstadoNatal.PREPARACAO && estado != EstadoNatal.TIMES_POSICIONADOS
                && estado != EstadoNatal.ARENA_LIBERADA) {
            return false;
        }
        Participante pt = participantes.get(p.getUniqueId());
        if (pt == null || !pt.naRodada || pt.status != Status.ATIVO) {
            return false;
        }
        Location spawn = spawnDoTime(pt.time);
        if (spawn == null || !spawn.getWorld().equals(para.getWorld())) {
            return false;
        }
        double dx = para.getX() - spawn.getX();
        double dz = para.getZ() - spawn.getZ();
        double r = raioPreparacao() + 1.5;
        return dx * dx + dz * dz > r * r;
    }

    // ------------------------------------------------------------------ informações para comandos

    Time ultimoVencedor() {
        return ultimoVencedor;
    }

    boolean noEventoAgora(UUID id) {
        return noEvento.contains(id);
    }
}
