# EventosASTHEN (EventoBot)

Plugin **Paper 26.3** (Java 25) para eventos do servidor. É independente do [Lobby-Guard](https://github.com/ElbsxS1/Lobby-Guard): o lobby protege e distribui, este plugin cuida dos eventos.

Hoje ele traz o aldeão de eventos com a contagem regressiva. O **Evento de Natal** (times, bolinhas de neve, ranking) e o **Carnaval** vão entrar aqui como módulos independentes, cada um com configuração, estado e dados próprios.

## Compilar

O repositório compila sozinho no GitHub: a cada push, em **Actions → Build → Artifacts → EventoBot**, fica o jar. Para compilar no PC: JDK 25 e `mvn package` (o jar sai em `target/EventoBot-1.0.0.jar`).

## Primeiro uso

1. No lobby, no ponto do aldeão: `/event setnpc`.
2. Coloque a pasta do mundo do evento no servidor com o nome `eventos` (configurável em `config.yml`).
3. `/event on` abre na hora, ou `/event time 2h5m53s` agenda a abertura.

## Comandos (permissão `eventobot.admin`, padrão OP)

| Comando | O que faz |
|---|---|
| `/event on` | Carrega o mundo e libera o aldeão para todos |
| `/event off` | Leva quem está no evento ao lobby, descarrega o mundo e volta o nome para "Sem Evento" |
| `/event time <2h5m53s>` | Contagem regressiva até abrir; o nome do aldeão mostra o tempo |
| `/event time cancel` | Cancela a contagem |
| `/event setnpc` | Move o aldeão para a sua posição |
| `/event status` | Mostra estado, tempo restante e posição do aldeão |

`/event time` precisa de unidade (`90s`, `1m30s`, `2h5m53s`). Quem tem `eventobot.bypass` (OP por padrão) entra pelo aldeão mesmo com o evento fechado.

## Comportamento

- A contagem é salva em `plugins/EventoBot/data.yml` a cada 10 segundos e ao desligar.
- Se o servidor reiniciar com o evento aberto, o mundo é carregado de novo e o evento volta aberto.
- O aldeão é invulnerável, sem IA, mantém o chunk carregado e é recriado se alguém o remover.
- Se o evento está aberto e o mundo foi descarregado, o plugin tenta carregá-lo quando alguém clica.

## 🔗 Ecossistema Asthen

Este projeto faz parte do ecossistema **Asthen**, um conjunto de plugins e ferramentas que trabalham juntos para integrar o servidor Minecraft, o gerenciamento de jogadores, o lobby, os comandos, os sistemas internos e o Discord.

**Projetos relacionados:**

- [AsthenEssencials](https://github.com/ElbsxS1/AsthenEssencials)
- [DiscordKitBridge](https://github.com/ElbsxS1/DiscordKitBridge)
- [Lobby-Guard](https://github.com/ElbsxS1/Lobby-Guard)
- [BOT-ASTHEN](https://github.com/contatorzlkz-prog/BOT-ASTHEN)
