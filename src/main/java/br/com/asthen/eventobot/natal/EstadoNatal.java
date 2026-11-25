package br.com.asthen.eventobot.natal;

/** Estados do Evento de Natal. Só transições válidas são feitas pelo NatalModule. */
public enum EstadoNatal {
    AGUARDANDO("Aguardando"),
    SELECAO_EQUIPES("Seleção de equipes"),
    PREPARACAO("Preparação"),
    TIMES_POSICIONADOS("Times posicionados"),
    ARENA_LIBERADA("Arena liberada"),
    PARTIDA_EM_ANDAMENTO("Partida em andamento"),
    RODADA_FINALIZADA("Rodada finalizada"),
    EVENTO_ENCERRADO("Evento encerrado"),
    EVENTO_CANCELADO("Evento cancelado");

    public final String rotulo;

    EstadoNatal(String rotulo) {
        this.rotulo = rotulo;
    }

    /** Há uma rodada acontecendo (da preparação até o combate). */
    public boolean emRodada() {
        return this == PREPARACAO || this == TIMES_POSICIONADOS
                || this == ARENA_LIBERADA || this == PARTIDA_EM_ANDAMENTO;
    }

    /** Estados em que um jogador ainda pode escolher o time. */
    public boolean permiteEscolherTime() {
        return this == SELECAO_EQUIPES || this == RODADA_FINALIZADA;
    }
}
