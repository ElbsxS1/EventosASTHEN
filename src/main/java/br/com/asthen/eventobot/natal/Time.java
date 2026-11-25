package br.com.asthen.eventobot.natal;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Material;

import java.util.Locale;

/** As quatro equipes do Evento de Natal. */
public enum Time {
    AMARELO("Amarelo", NamedTextColor.YELLOW, Color.fromRGB(0xF2C200), Material.YELLOW_WOOL, "§e"),
    VERMELHO("Vermelho", NamedTextColor.RED, Color.fromRGB(0xD62828), Material.RED_WOOL, "§c"),
    VERDE("Verde", NamedTextColor.GREEN, Color.fromRGB(0x2E9E3A), Material.GREEN_WOOL, "§a"),
    AZUL("Azul", NamedTextColor.BLUE, Color.fromRGB(0x2F5BD6), Material.BLUE_WOOL, "§9");

    public final String nome;
    public final NamedTextColor cor;
    public final Color armadura;
    public final Material lana;
    public final String codigo;

    Time(String nome, NamedTextColor cor, Color armadura, Material lana, String codigo) {
        this.nome = nome;
        this.cor = cor;
        this.armadura = armadura;
        this.lana = lana;
        this.codigo = codigo;
    }

    /** Nome colorido para texto legado (placar). */
    public String colorido() {
        return codigo + nome;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Aceita "amarelo", "Amarelo", "AMARELO". Retorna null se não existir. */
    public static Time porNome(String texto) {
        if (texto == null) {
            return null;
        }
        String t = texto.trim().toUpperCase(Locale.ROOT);
        for (Time time : values()) {
            if (time.name().equals(t)) {
                return time;
            }
        }
        return null;
    }
}
