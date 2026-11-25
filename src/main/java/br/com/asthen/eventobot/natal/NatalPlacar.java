package br.com.asthen.eventobot.natal;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Interface do evento:
 * - Scoreboard lateral por jogador (estado, time, ranking no lobby ou jogadores restantes na rodada).
 * - Times coloridos no scoreboard (nome colorido e prefixo na lista de jogadores).
 * - Barra superior (BossBar) com os jogadores restantes de cada equipe durante a rodada.
 *
 * Scoreboard e BossBar são enviados ao Bedrock pelo Geyser. Não existe HUD personalizada no Bedrock, por
 * isso estes são os recursos usados nas duas plataformas.
 */
final class NatalPlacar {

    private final NatalModule m;
    private final Map<UUID, Scoreboard> placares = new HashMap<>();
    private final Map<UUID, List<String>> linhas = new HashMap<>();
    private final BossBar barra = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);

    NatalPlacar(NatalModule m) {
        this.m = m;
    }

    void mostrar(Player p) {
        Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective obj = sb.registerNewObjective("natal", Criteria.DUMMY,
                Component.text("NATAL", NamedTextColor.RED, TextDecoration.BOLD));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        placares.put(p.getUniqueId(), sb);
        linhas.remove(p.getUniqueId());
        p.setScoreboard(sb);
        atualizar(p);
    }

    void remover(Player p) {
        UUID id = p.getUniqueId();
        p.hideBossBar(barra);
        if (placares.remove(id) != null) {
            linhas.remove(id);
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    void atualizarTodos() {
        atualizarBarra();
        for (UUID id : new ArrayList<>(placares.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) {
                placares.remove(id);
                linhas.remove(id);
                continue;
            }
            atualizar(p);
        }
    }

    private void atualizar(Player p) {
        Scoreboard sb = placares.get(p.getUniqueId());
        if (sb == null) {
            return;
        }
        sincronizarTimes(sb);
        Objective obj = sb.getObjective("natal");
        if (obj == null) {
            return;
        }
        List<String> novas = montarLinhas(p);
        List<String> antigas = linhas.get(p.getUniqueId());
        if (novas.equals(antigas)) {
            return;
        }
        if (antigas != null) {
            for (String l : antigas) {
                sb.resetScores(l);
            }
        }
        int total = novas.size();
        for (int i = 0; i < total; i++) {
            obj.getScore(novas.get(i)).setScore(total - i);
        }
        linhas.put(p.getUniqueId(), novas);
    }

    /** Mantém, em cada scoreboard, os 4 times coloridos com os jogadores certos. */
    private void sincronizarTimes(Scoreboard sb) {
        for (Time t : Time.values()) {
            Team team = sb.getTeam("natal_" + t.id());
            if (team == null) {
                team = sb.registerNewTeam("natal_" + t.id());
            }
            team.color(t.cor);
            team.prefix(Component.text("[" + t.nome + "] ", t.cor));
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, nomeVisivel());
            Set<String> desejados = new HashSet<>();
            for (Player p : m.presentes(t)) {
                desejados.add(p.getName());
            }
            for (String entrada : new ArrayList<>(team.getEntries())) {
                if (!desejados.contains(entrada)) {
                    team.removeEntry(entrada);
                }
            }
            for (String nome : desejados) {
                if (!team.hasEntry(nome)) {
                    team.addEntry(nome);
                }
            }
        }
        // Quem está no evento sem time também tem o nome escondido durante a rodada.
        Team sem = sb.getTeam("natal_sem");
        if (sem == null) {
            sem = sb.registerNewTeam("natal_sem");
        }
        sem.setOption(Team.Option.NAME_TAG_VISIBILITY, nomeVisivel());
        Set<String> comTime = new HashSet<>();
        for (Time t : Time.values()) {
            for (Player p : m.presentes(t)) {
                comTime.add(p.getName());
            }
        }
        Set<String> semTime = new HashSet<>();
        for (UUID id : placares.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && !comTime.contains(p.getName())) {
                semTime.add(p.getName());
            }
        }
        for (String entrada : new ArrayList<>(sem.getEntries())) {
            if (!semTime.contains(entrada)) {
                sem.removeEntry(entrada);
            }
        }
        for (String nome : semTime) {
            if (!sem.hasEntry(nome)) {
                sem.addEntry(nome);
            }
        }
    }

    /** Durante a rodada o nome sobre a cabeça fica escondido para todos. */
    private Team.OptionStatus nomeVisivel() {
        return m.estado.emRodada() ? Team.OptionStatus.NEVER : Team.OptionStatus.ALWAYS;
    }

    private boolean daRodada(Time t) {
        return m.estado.emRodada() && (t == m.local1() || t == m.local2());
    }

    private List<String> montarLinhas(Player p) {
        NatalModule.Participante pt = m.participante(p.getUniqueId());
        List<String> l = new ArrayList<>();
        l.add("§7Estado: §f" + m.estado.rotulo);
        String time = pt != null && pt.time != null ? pt.time.colorido() : "§7nenhum";
        l.add("§7Time: " + time);
        if (pt != null && (pt.status == Status.ESPECTADOR || pt.status == Status.ELIMINADO)) {
            l.add("§8Você é espectador");
        }
        l.add("§r ");
        if (m.estado.emRodada()) {
            for (Time t : Time.values()) {
                int n = daRodada(t) ? m.ativos(t) : m.registrados(t);
                l.add(t.codigo + "■ " + t.nome + ": §f" + n);
            }
        } else {
            l.add("§6Ranking de kills");
            List<Ranking.Entrada> top = m.ranking.top(m.rankingTop());
            if (top.isEmpty()) {
                l.add("§7ninguém ainda");
            }
            for (int i = 0; i < top.size(); i++) {
                String nome = top.get(i).nome();
                if (nome.length() > 14) {
                    nome = nome.substring(0, 14);
                }
                l.add("§e" + (i + 1) + "º §f" + nome + " §7- §f" + top.get(i).kills());
            }
        }
        return unicas(l);
    }

    /** Entradas do scoreboard precisam ser únicas e curtas. */
    private static List<String> unicas(List<String> original) {
        List<String> saida = new ArrayList<>();
        Set<String> usadas = new HashSet<>();
        for (String linha : original) {
            String s = linha.length() > 38 ? linha.substring(0, 38) : linha;
            while (!usadas.add(s)) {
                s = s + "§r";
            }
            saida.add(s);
        }
        return saida;
    }

    /** Barra superior: um quadrado colorido por jogador ativo de cada equipe; o eliminado some. */
    private void atualizarBarra() {
        boolean rodada = m.estado.emRodada();
        Component titulo = Component.empty();
        for (Time t : Time.values()) {
            boolean ativa = daRodada(t);
            int n = ativa ? m.ativos(t) : m.registrados(t);
            titulo = titulo.append(Component.text(t.nome + " ", t.cor, TextDecoration.BOLD))
                    .append(Component.text("■".repeat(Math.max(0, n)), ativa ? t.cor : NamedTextColor.DARK_GRAY))
                    .append(Component.text("   "));
        }
        barra.name(titulo);
        for (UUID id : placares.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                continue;
            }
            if (rodada) {
                p.showBossBar(barra);
            } else {
                p.hideBossBar(barra);
            }
        }
    }
}
