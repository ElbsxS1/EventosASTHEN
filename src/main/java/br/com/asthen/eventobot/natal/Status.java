package br.com.asthen.eventobot.natal;

/** Situação de um jogador no Evento de Natal. */
public enum Status {
    /** No lobby do evento (pode ter time, aguardando rodada). */
    LOBBY,
    /** Participando da rodada em andamento. */
    ATIVO,
    /** Eliminado na rodada atual (assiste como espectador). */
    ELIMINADO,
    /** Espectador: entrou tarde ou a equipe foi eliminada do evento. */
    ESPECTADOR
}
