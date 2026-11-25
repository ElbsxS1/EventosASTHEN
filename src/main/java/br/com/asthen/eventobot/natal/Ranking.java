package br.com.asthen.eventobot.natal;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/** Ranking de kills do Natal, persistente e identificado por UUID. Arquivo próprio, separado do Carnaval. */
final class Ranking {

    record Entrada(UUID uuid, String nome, int kills) {
    }

    private final File arquivo;
    private final Logger log;
    private final Map<UUID, Integer> kills = new HashMap<>();
    private final Map<UUID, String> nomes = new HashMap<>();

    Ranking(File pasta, Logger log) {
        this.arquivo = new File(pasta, "natal-ranking.yml");
        this.log = log;
        carregar();
    }

    private void carregar() {
        kills.clear();
        nomes.clear();
        if (!arquivo.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(arquivo);
        ConfigurationSection s = y.getConfigurationSection("jogadores");
        if (s == null) {
            return;
        }
        for (String chave : s.getKeys(false)) {
            try {
                UUID id = UUID.fromString(chave);
                kills.put(id, s.getInt(chave + ".kills"));
                nomes.put(id, s.getString(chave + ".nome", "?"));
            } catch (IllegalArgumentException ignorado) {
                log.warning("Ranking do Natal: UUID inválido ignorado: " + chave);
            }
        }
    }

    /** Soma uma kill válida e grava imediatamente (escrita atômica). */
    void adicionarKill(UUID id, String nome) {
        kills.merge(id, 1, Integer::sum);
        nomes.put(id, nome);
        salvar();
    }

    void zerar() {
        kills.clear();
        nomes.clear();
        salvar();
    }

    int kills(UUID id) {
        return kills.getOrDefault(id, 0);
    }

    List<Entrada> top(int quantidade) {
        List<Entrada> lista = new ArrayList<>();
        for (Map.Entry<UUID, Integer> e : kills.entrySet()) {
            lista.add(new Entrada(e.getKey(), nomes.getOrDefault(e.getKey(), "?"), e.getValue()));
        }
        lista.sort(Comparator.comparingInt(Entrada::kills).reversed().thenComparing(Entrada::nome));
        return lista.size() > quantidade ? new ArrayList<>(lista.subList(0, quantidade)) : lista;
    }

    private void salvar() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Integer> e : kills.entrySet()) {
            String base = "jogadores." + e.getKey();
            y.set(base + ".nome", nomes.getOrDefault(e.getKey(), "?"));
            y.set(base + ".kills", e.getValue());
        }
        try {
            File pasta = arquivo.getParentFile();
            if (pasta != null) {
                pasta.mkdirs();
            }
            File tmp = new File(arquivo.getPath() + ".tmp");
            y.save(tmp);
            Files.move(tmp.toPath(), arquivo.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            log.warning("Não consegui salvar o ranking do Natal: " + e.getMessage());
        }
    }
}
