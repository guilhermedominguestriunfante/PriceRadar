# Duelo ao vivo

Dois amigos jogam a mesma arena ao mesmo tempo, por 60 segundos, e vence quem fizer mais pontos.
Enquanto uma bola cai, quem mantém o ritmo pedido captura o item dela e pode jogá-lo no rival.

| Item    | Para capturar       | Efeito no rival                 |
|---------|---------------------|---------------------------------|
| LENTO   | 9 toques/s por 3 s  | os toques valem metade por 5 s  |
| RELÓGIO | 11 toques/s por 4 s | perde 3 s do relógio            |
| STOP    | 13 toques/s por 5 s | um STOP surpresa                |

O duelo aparece com o online conectado e com o Realtime Database no build
(`dedo.firebaseDatabaseUrl` em `gradle.properties`, levado ao `online.properties`).

## Como funciona

- **Servidor:** `core/online/RealtimeDb.kt` (REST e streaming) e `core/online/DuelService.kt`
  (sala, convite, aceite, relógio, placar e itens). As regras ficam em
  `firebase/database.rules.json`: só os dois jogadores leem a sala, e cada um grava só a própria
  parte.
- **Motor:** `GameSession(duel = true)` com `StageCatalog.duel()` e `Loadout.NONE`. As bolas saem
  da semente da sala, então caem nos mesmos momentos para os dois.
- **App:** `app/platform/Duel.kt` roda na thread da interface, com a rede no executor `net`.
  - **Convites:** segue `invites/<uid>` enquanto o app está na frente e online, e ignora
    convites com mais de 10 minutos. O diálogo "X te desafiou!" nunca aparece no meio de uma
    partida.
  - **Anfitrião:** desafia, espera o aceite por até 60 s (depois cancela), entra na sala e
    marca o início para 4 s depois, pelo relógio do servidor.
  - **Convidado:** aceita, entra na sala e espera o horário de início.
  - **Horário local:** `início − offset`, convertido para `SystemClock.uptimeMillis`. O 3-2-1
    termina exatamente no início.
  - **Durante a partida:**
    - o placar é publicado a cada ~400 ms, e cada item do rival é aplicado uma única vez;
    - o rival aparece como SEM SINAL depois de 8 s sem relatório;
    - a partida continua com o app em segundo plano: um `Handler` mantém o relógio e os
      relatórios.
  - **Fim:** o relatório final (`done`) é repetido até chegar. Cada um espera o placar final do
    rival por até 15 s; se não chegar, é W.O. O anfitrião apaga a sala depois de publicar o
    próprio placar final.
  - **Desistir:** o Voltar ou o X pedem confirmação. Desistir conta como derrota e apaga a sala,
    e o rival vence.
- **Telas:**
  - `DuelLobbyScreen`: itens, convites recebidos e amigos com DESAFIAR;
  - `DuelWaitScreen`: aguardando o amigo, com "Chamar no WhatsApp" e Cancelar;
  - `PlayScreen` em modo duelo: placar do rival no lugar do objetivo, bola caindo com o anel de
    captura, botão do item e tinta azul durante o LENTO;
  - `DuelResultScreen`: VITÓRIA, DERROTA ou EMPATE, os dois placares, os itens e REVANCHE;
  - botão DUELO na Home, com o número de convites.

## Testes

- `DuelEmulatorTest` e `DuelSessionTest` (core): protocolo e regras do motor.
- `DuelFlowTest` (app, Robolectric contra os emuladores). O app joga um lado e o rival é uma
  segunda conta que usa o `DuelService` direto:
  - **desafio:** convite, aceite, partida com itens nos dois sentidos, VITÓRIA e sala fechada;
  - **convite recebido:** diálogo, aceite, STOP recebido e desistência.

## Produção

1. Publique `firebase/database.rules.json` no projeto `dedo-nervoso-7284`. Pode ser pelo console,
   em Realtime Database → Regras, ou, com o Firebase CLI logado, na pasta `firebase/`:
   `npx --yes firebase-tools@13 deploy --only database --project prod`.
2. Faça um duelo inteiro entre dois celulares com a versão 1.2.0.
