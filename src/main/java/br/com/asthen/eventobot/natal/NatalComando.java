package br.com.asthen.eventobot.natal;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.OfflinePlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * /natal: jogadores usam sair, ranking e time; administradores (eventobot.natal.admin) controlam o
 * evento. O mesmo texto de ajuda aparece em /evento natal.
 */
final class NatalComando implements CommandExecutor, TabCompleter {

    private static final String ADMIN = "eventobot.natal.admin";

    private static final List<String> ADMIN_SUBS = List.of(
            "painel", "setranking", "removeranking", "iniciar", "amarelo", "vermelho", "verde", "azul", "preparar", "liberar", "status", "times",
            "participantes", "ativos", "espectadores", "encerrarrodada", "cancelarrodada", "cancelar",
            "encerrar", "remover", "resetar", "setlobby", "setentrada", "setspawn", "setespectador", "zerarranking");
    private static final List<String> JOGADOR_SUBS = List.of("ajuda", "sair", "ranking", "time");

    private final NatalModule m;

    NatalComando(NatalModule m) {
        this.m = m;
    }

    // ------------------------------------------------------------------ ajuda

    static void ajuda(NatalModule m, CommandSender s, boolean admin) {
        s.sendMessage(NatalModule.texto("§6§l=== Evento de Natal ==="));
        s.sendMessage(NatalModule.texto("§e/natal sair §7- sai do evento"));
        s.sendMessage(NatalModule.texto("§e/natal ranking §7- top de kills"));
        s.sendMessage(NatalModule.texto("§e/natal time §7- mostra o seu time"));
        if (!admin) {
            return;
        }
        s.sendMessage(NatalModule.texto("§c§lAdministração:"));
        s.sendMessage(NatalModule.texto("§e/natal painel §7- painel de administração (tudo em botões)"));
        s.sendMessage(NatalModule.texto("§e/natal setranking|removeranking §7- ranking flutuante no lobby"));
        s.sendMessage(NatalModule.texto("§e/natal iniciar §7- abre a escolha de times"));
        s.sendMessage(NatalModule.texto("§e/natal <cor> local <1|2> §7- escolhe o time de cada local (ex.: /natal amarelo local 1)"));
        s.sendMessage(NatalModule.texto("§e/natal setlobby §7- lobby do evento (onde os times esperam)"));
        s.sendMessage(NatalModule.texto("§e/natal setentrada §7- spawn de entrada no evento"));
        s.sendMessage(NatalModule.texto("§e/natal setspawn <1|2> §7- spawn do Local 1 ou 2 na arena"));
        s.sendMessage(NatalModule.texto("§e/natal setespectador §7- ponto dos espectadores"));
        s.sendMessage(NatalModule.texto("§e/natal preparar §7- leva os dois times aos seus spawns"));
        s.sendMessage(NatalModule.texto("§e/natal liberar §7- libera a arena (só com os dois times posicionados)"));
        s.sendMessage(NatalModule.texto("§e/natal encerrarrodada <cor> §7- encerra a rodada com um vencedor"));
        s.sendMessage(NatalModule.texto("§e/natal cancelarrodada §7- cancela a rodada atual"));
        s.sendMessage(NatalModule.texto("§e/natal cancelar §7- cancela o evento"));
        s.sendMessage(NatalModule.texto("§e/natal encerrar §7- encerra o evento"));
        s.sendMessage(NatalModule.texto("§e/natal status §7- estado, rodada e times"));
        s.sendMessage(NatalModule.texto("§e/natal times §7- jogadores de cada time (registrados/presentes)"));
        s.sendMessage(NatalModule.texto("§e/natal participantes|ativos|espectadores §7- listas"));
        s.sendMessage(NatalModule.texto("§e/natal remover <jogador> §7- tira o time de um jogador"));
        s.sendMessage(NatalModule.texto("§e/natal resetar <jogador> §7- tira de espectador e volta a jogador"));
        s.sendMessage(NatalModule.texto("§e/natal zerarranking confirmar §7- apaga o ranking do Natal"));
        s.sendMessage(NatalModule.texto("§7Estado atual: §f" + m.estado.rotulo));
    }

    // ------------------------------------------------------------------ execução

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] args) {
        boolean admin = s.hasPermission(ADMIN);
        if (args.length == 0 || args[0].equalsIgnoreCase("ajuda")) {
            ajuda(m, s, admin);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "sair" -> sair(s);
            case "ranking" -> ranking(s);
            case "time" -> meuTime(s);
            default -> {
                if (!admin) {
                    s.sendMessage(NatalModule.texto("§cVocê não tem permissão para este comando."));
                    return true;
                }
                administrar(s, sub, args);
            }
        }
        return true;
    }

    private void sair(CommandSender s) {
        if (!(s instanceof Player p)) {
            m.enviar(s, "§cApenas jogadores.");
            return;
        }
        if (!m.noMundo(p) || m.admin(p) && !m.noEventoAgora(p.getUniqueId())) {
            m.enviar(p, "§cVocê não está no evento.");
            return;
        }
        Location destino = m.plugin().lobbyPrincipal();
        if (destino == null) {
            m.enviar(p, "§cNão encontrei o lobby principal.");
            return;
        }
        m.permitirSaida(p);
        p.teleportAsync(destino);
    }

    private void ranking(CommandSender s) {
        List<Ranking.Entrada> top = m.ranking.top(m.rankingTop());
        s.sendMessage(NatalModule.texto("§6§lRanking de kills do Natal"));
        if (top.isEmpty()) {
            s.sendMessage(NatalModule.texto("§7Ninguém pontuou ainda."));
            return;
        }
        for (int i = 0; i < top.size(); i++) {
            s.sendMessage(NatalModule.texto("§e" + (i + 1) + "º §f" + top.get(i).nome() + " §7- §f" + top.get(i).kills()));
        }
    }

    private void meuTime(CommandSender s) {
        if (!(s instanceof Player p)) {
            m.enviar(s, "§cApenas jogadores.");
            return;
        }
        NatalModule.Participante pt = m.participante(p.getUniqueId());
        if (pt == null || pt.time == null) {
            m.enviar(p, "§7Você ainda não escolheu um time.");
        } else {
            m.enviar(p, "§7Seu time: " + pt.time.colorido() + " §7(" + pt.status.name().toLowerCase(Locale.ROOT) + ")");
        }
    }

    private void administrar(CommandSender s, String sub, String[] args) {
        Time cor = Time.porNome(sub);
        if (cor != null) {
            definirLocal(s, cor, args);
            return;
        }
        switch (sub) {
            case "painel" -> {
                if (s instanceof Player pl) {
                    m.painel.abrir(pl);
                } else {
                    m.enviar(s, "§cUse dentro do jogo.");
                }
            }
            case "setranking" -> {
                if (s instanceof Player pl) {
                    m.holo.definir(pl.getLocation());
                    m.enviar(s, "§aRanking flutuante colocado aqui.");
                } else {
                    m.enviar(s, "§cUse dentro do jogo.");
                }
            }
            case "removeranking" -> {
                m.holo.remover();
                m.enviar(s, "§aRanking flutuante removido.");
            }
            case "iniciar" -> m.abrirSelecao(s);
            case "preparar" -> m.preparar(s);
            case "liberar" -> m.liberar(s);
            case "cancelarrodada" -> m.cancelarRodada(s);
            case "cancelar" -> m.finalizarEvento(s, EstadoNatal.EVENTO_CANCELADO);
            case "encerrar" -> m.finalizarEvento(s, EstadoNatal.EVENTO_ENCERRADO);
            case "encerrarrodada" -> encerrarRodada(s, args);
            case "status" -> status(s);
            case "times" -> times(s);
            case "participantes" -> listar(s, "Participantes", null);
            case "ativos" -> listar(s, "Jogadores ativos", Status.ATIVO);
            case "espectadores" -> listarEspectadores(s);
            case "remover" -> remover(s, args);
            case "resetar" -> resetar(s, args);
            case "setlobby" -> posicao(s, "lobby", "Lobby do evento");
            case "setentrada" -> posicao(s, "entrada", "Spawn de entrada");
            case "setespectador" -> posicao(s, "espectador", "Ponto dos espectadores");
            case "setspawn" -> setSpawn(s, args);
            case "zerarranking" -> zerarRanking(s, args);
            default -> ajuda(m, s, true);
        }
    }

    private void definirLocal(CommandSender s, Time cor, String[] args) {
        if (args.length < 3 || !args[1].equalsIgnoreCase("local")) {
            m.enviar(s, "§cUso: /natal " + cor.id() + " local <1|2>");
            return;
        }
        int n;
        try {
            n = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            n = 0;
        }
        if (n != 1 && n != 2) {
            m.enviar(s, "§cO local precisa ser 1 ou 2.");
            return;
        }
        String erro = m.definirLocal(n, cor);
        if (erro != null) {
            m.enviar(s, "§c" + erro);
            return;
        }
        m.enviar(s, "§aLocal " + n + ": time " + cor.colorido() + "§a.");
        m.atualizarVisual();
    }

    private void encerrarRodada(CommandSender s, String[] args) {
        Time t = args.length > 1 ? Time.porNome(args[1]) : null;
        if (t == null) {
            m.enviar(s, "§cUso: /natal encerrarrodada <amarelo|vermelho|verde|azul>");
            return;
        }
        m.encerrarRodada(s, t);
    }

    private void posicao(CommandSender s, String chave, String nome) {
        if (!(s instanceof Player p)) {
            m.enviar(s, "§cUse este comando dentro do jogo.");
            return;
        }
        if (!m.noMundo(p)) {
            m.enviar(p, "§cEntre no mundo do evento para definir este ponto.");
            return;
        }
        m.definirPosicao(chave, p.getLocation());
        m.enviar(p, "§a" + nome + " definido aqui.");
    }

    private void setSpawn(CommandSender s, String[] args) {
        if (args.length < 2 || !(args[1].equals("1") || args[1].equals("2"))) {
            m.enviar(s, "§cUso: /natal setspawn <1|2>");
            return;
        }
        posicao(s, "spawn" + args[1], "Spawn do Local " + args[1]);
    }

    private void remover(CommandSender s, String[] args) {
        if (args.length < 2) {
            m.enviar(s, "§cUso: /natal remover <jogador>");
            return;
        }
        OfflinePlayer off = Bukkit.getOfflinePlayer(args[1]);
        if (m.removerDoTimeForcado(off.getUniqueId())) {
            m.enviar(s, "§aO jogador " + args[1] + " foi tirado do time e pode escolher de novo.");
        } else {
            m.enviar(s, "§cNão deu para remover (sem time, desconhecido ou dentro de uma rodada).");
        }
    }

    private void resetar(CommandSender s, String[] args) {
        if (args.length < 2) {
            m.enviar(s, "§cUso: /natal resetar <jogador>");
            return;
        }
        OfflinePlayer off = Bukkit.getOfflinePlayer(args[1]);
        if (m.resetarJogador(off.getUniqueId())) {
            m.enviar(s, "§a" + args[1] + " voltou a ser jogador (lobby do evento), mantendo o time.");
        } else {
            m.enviar(s, "§cJogador não está registrado no evento.");
        }
    }

    private void zerarRanking(CommandSender s, String[] args) {
        if (args.length < 2 || !args[1].equalsIgnoreCase("confirmar")) {
            m.enviar(s, "§eIsso apaga o ranking do Natal. Para confirmar: §f/natal zerarranking confirmar");
            return;
        }
        m.ranking.zerar();
        m.atualizarVisual();
        m.enviar(s, "§aRanking do Natal zerado.");
    }

    // ------------------------------------------------------------------ informações

    private String nome(UUID id) {
        OfflinePlayer off = Bukkit.getOfflinePlayer(id);
        return off.getName() != null ? off.getName() : id.toString().substring(0, 8);
    }

    private void status(CommandSender s) {
        s.sendMessage(NatalModule.texto("§6§lStatus do Natal"));
        s.sendMessage(NatalModule.texto("§7Estado: §f" + m.estado.rotulo + " §7| Rodada: §f" + m.rodadaId()
                + " §7| Evento ativo: §f" + (m.ativo() ? "natal" : "outro")));
        s.sendMessage(NatalModule.texto("§7Local 1: " + (m.local1() != null ? m.local1().colorido() : "§8-")
                + " §7| Local 2: " + (m.local2() != null ? m.local2().colorido() : "§8-")));
        s.sendMessage(NatalModule.texto("§7Pontos: lobby " + marca(m.posicao("lobby")) + " entrada " + marca(m.posicao("entrada"))
                + " spawn1 " + marca(m.posicao("spawn1")) + " spawn2 " + marca(m.posicao("spawn2"))
                + " espectador " + marca(m.posicao("espectador"))));
        if (m.estado == EstadoNatal.PREPARACAO || m.estado == EstadoNatal.TIMES_POSICIONADOS) {
            s.sendMessage(NatalModule.texto("§7Times posicionados: " + (m.prontos() ? "§asim" : "§enão ainda")));
        }
        if (m.ultimoVencedor() != null) {
            s.sendMessage(NatalModule.texto("§7Último vencedor: " + m.ultimoVencedor().colorido()));
        }
    }

    private static String marca(Location l) {
        return l == null ? "§c✗§7" : "§a✓§7";
    }

    private void times(CommandSender s) {
        s.sendMessage(NatalModule.texto("§6§lTimes"));
        for (Time t : Time.values()) {
            StringBuilder nomes = new StringBuilder();
            for (Map.Entry<UUID, NatalModule.Participante> e : m.participantes.entrySet()) {
                if (e.getValue().time == t) {
                    boolean presente = Bukkit.getPlayer(e.getKey()) != null && m.noEventoAgora(e.getKey());
                    nomes.append(presente ? "§f" : "§8").append(nome(e.getKey())).append("§7, ");
                }
            }
            s.sendMessage(NatalModule.texto(t.colorido() + " §7registrados §f" + m.registrados(t) + "§7/" + m.maxPorTime()
                    + " presentes §f" + m.presentes(t).size() + " §7ativos §f" + m.ativos(t)
                    + (nomes.length() > 0 ? " §7- " + nomes : "")));
        }
    }

    private void listar(CommandSender s, String titulo, Status filtro) {
        List<String> nomes = new ArrayList<>();
        for (Map.Entry<UUID, NatalModule.Participante> e : m.participantes.entrySet()) {
            if (filtro == null || e.getValue().status == filtro) {
                NatalModule.Participante pt = e.getValue();
                nomes.add((pt.time != null ? pt.time.codigo : "§7") + nome(e.getKey()));
            }
        }
        s.sendMessage(NatalModule.texto("§6" + titulo + " §7(" + nomes.size() + "): §f" + String.join("§7, ", nomes)));
    }

    private void listarEspectadores(CommandSender s) {
        List<String> nomes = new ArrayList<>();
        for (Map.Entry<UUID, NatalModule.Participante> e : m.participantes.entrySet()) {
            Status st = e.getValue().status;
            if (st == Status.ESPECTADOR || st == Status.ELIMINADO) {
                nomes.add(nome(e.getKey()));
            }
        }
        s.sendMessage(NatalModule.texto("§6Espectadores §7(" + nomes.size() + "): §f" + String.join("§7, ", nomes)));
    }

    // ------------------------------------------------------------------ sugestões

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        boolean admin = s.hasPermission(ADMIN);
        if (args.length == 1) {
            adicionar(out, JOGADOR_SUBS, args[0]);
            if (admin) {
                adicionar(out, ADMIN_SUBS, args[0]);
            }
            return out;
        }
        if (!admin) {
            return out;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            if (Time.porNome(sub) != null) {
                adicionar(out, List.of("local"), args[1]);
            } else if (sub.equals("setspawn")) {
                adicionar(out, List.of("1", "2"), args[1]);
            } else if (sub.equals("encerrarrodada")) {
                adicionar(out, List.of("amarelo", "vermelho", "verde", "azul"), args[1]);
            } else if (sub.equals("remover") || sub.equals("resetar")) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    adicionar(out, List.of(p.getName()), args[1]);
                }
            } else if (sub.equals("zerarranking")) {
                adicionar(out, List.of("confirmar"), args[1]);
            }
        } else if (args.length == 3 && Time.porNome(sub) != null) {
            adicionar(out, List.of("1", "2"), args[2]);
        }
        return out;
    }

    private static void adicionar(List<String> out, List<String> opcoes, String prefixo) {
        String p = prefixo.toLowerCase(Locale.ROOT);
        for (String o : opcoes) {
            if (o.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(o);
            }
        }
    }
}
