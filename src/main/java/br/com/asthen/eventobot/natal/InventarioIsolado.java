package br.com.asthen.eventobot.natal;

import org.bukkit.GameMode;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Guarda o inventário normal do jogador em disco (um arquivo por UUID) antes de ele entrar no evento e
 * devolve na saída. O arquivo é gravado de forma atômica e só é apagado depois da restauração, então
 * queda do servidor, desconexão ou falha de teleporte nunca perdem nem duplicam itens.
 *
 * Regra de segurança: se já existe um backup para o UUID, ele é o inventário ORIGINAL e nunca é
 * sobrescrito (o inventário atual do jogador, nesse caso, é só o de evento).
 */
final class InventarioIsolado {

    private final File pasta;
    private final Logger log;

    InventarioIsolado(File dadosDoPlugin, Logger log) {
        this.pasta = new File(dadosDoPlugin, "natal-inventarios");
        this.log = log;
        this.pasta.mkdirs();
    }

    private File arquivo(UUID id) {
        return new File(pasta, id + ".yml");
    }

    boolean existe(UUID id) {
        return arquivo(id).exists();
    }

    /** Salva (se ainda não houver backup) e esvazia o inventário. Retorna false se não deu para salvar. */
    boolean isolar(Player p) {
        UUID id = p.getUniqueId();
        if (!existe(id)) {
            YamlConfiguration y = new YamlConfiguration();
            PlayerInventory inv = p.getInventory();
            List<ItemStack> itens = new ArrayList<>();
            for (ItemStack item : inv.getContents()) {
                itens.add(item);
            }
            y.set("nome", p.getName());
            y.set("itens", itens);
            y.set("nivel", p.getLevel());
            y.set("exp", (double) p.getExp());
            y.set("modo", p.getGameMode().name());
            try {
                File tmp = new File(arquivo(id).getPath() + ".tmp");
                y.save(tmp);
                Files.move(tmp.toPath(), arquivo(id).toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException | RuntimeException e) {
                log.log(Level.SEVERE, "Não consegui salvar o inventário de " + p.getName()
                        + "; ele NÃO entra no evento.", e);
                return false;
            }
        }
        limpar(p);
        return true;
    }

    /** Remove tudo do inventário visível: itens, armadura e mão secundária. */
    void limpar(Player p) {
        PlayerInventory inv = p.getInventory();
        inv.clear();
        inv.setArmorContents(new ItemStack[4]);
        inv.setItemInOffHand(null);
        p.setItemOnCursor(null);
        p.setLevel(0);
        p.setExp(0f);
    }

    /** Devolve o inventário original e apaga o backup. Sem backup, não faz nada. */
    boolean restaurar(Player p) {
        UUID id = p.getUniqueId();
        File f = arquivo(id);
        if (!f.exists()) {
            return false;
        }
        try {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            List<?> bruto = y.getList("itens");
            if (bruto == null) {
                throw new IOException("backup sem itens");
            }
            PlayerInventory inv = p.getInventory();
            ItemStack[] itens = new ItemStack[inv.getContents().length];
            for (int i = 0; i < itens.length && i < bruto.size(); i++) {
                Object o = bruto.get(i);
                itens[i] = o instanceof ItemStack s ? s : null;
            }
            limpar(p);
            inv.setContents(itens);
            p.setLevel(y.getInt("nivel"));
            p.setExp((float) y.getDouble("exp"));
            try {
                p.setGameMode(GameMode.valueOf(y.getString("modo", "SURVIVAL")));
            } catch (IllegalArgumentException e) {
                p.setGameMode(GameMode.SURVIVAL);
            }
        } catch (IOException | RuntimeException e) {
            log.log(Level.SEVERE, "Falha ao restaurar o inventário de " + p.getName()
                    + "; o backup foi mantido em " + f.getPath(), e);
            return false;
        }
        if (!f.delete()) {
            log.warning("Inventário de " + p.getName() + " restaurado, mas não consegui apagar " + f.getPath());
        }
        return true;
    }
}
