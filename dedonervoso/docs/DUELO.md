# Duelo ao vivo: onde parou

Branch: `claude/tap-tap-master-spec-1s18av` (a partir do `main` depois do merge da versão 1.1.1).

## Pronto e testado

- **Servidor (Realtime Database):**
  - `core/online/RealtimeDb.kt`: REST + streaming (SSE), sem SDK.
  - `core/online/DuelService.kt`: desafio, aceitar ou recusar, cancelar, sincronia de relógio,
    início simultâneo, placar ao vivo e itens.
  - `firebase/database.rules.json`: só os dois jogadores leem a sala, e cada um grava só a
    própria parte.
  - Testes: `DuelEmulatorTest`, contra o emulador (porta 9000, já no `firebase.json`).
- **Motor:**
  - `core/engine/Duel.kt` e `GameSession(duel = true)`: bolas que caem nos mesmos momentos para
    os dois.
  - A bola é capturada mantendo o ritmo pedido: SLOW 9 toques/s por 3 s, CLOCK 11/s por 4 s,
    STOP 13/s por 5 s.
  - Efeitos no rival: SLOW corta os pontos pela metade por 5 s, CLOCK tira 3 s do relógio e STOP
    força um STOP.
  - A arena é `StageCatalog.duel()`, sem upgrades.
  - Testes: `DuelSessionTest`.

## Falta (app)

1. `app/platform/Duel.kt`: gerencia convites (stream `invites/<uid>`) e a partida (sessão
   Firebase vinda da conta online, relógio, placar a cada ~400 ms, itens recebidos, rival
   desconectado).
2. Telas:
   - Lobby: amigos com DESAFIAR e convites recebidos.
   - Espera: aguardando o amigo, chamar no WhatsApp, cancelar.
   - Resultado: vitória, derrota ou empate, com revanche.
   - Botão DUELO na Home.
   - Diálogo global de convite.
3. `PlayScreen` em modo duelo:
   - `Loadout.NONE` e a semente da sala;
   - início no `startAt` do servidor;
   - sem pausa;
   - HUD: placar do rival, a bola caindo com o anel de captura e o ritmo pedido, e o botão do
     item;
   - efeitos recebidos.
4. Textos PT/EN, testes com Robolectric (o rival simulado pelo `DuelService`) e a versão 1.2.0.

## Servidor de produção (feito pelo usuário no console)

1. Criar o **Realtime Database** em us-central1, no modo bloqueado.
2. Publicar as regras em Realtime Database → Regras: colar `firebase/database.rules.json` e
   clicar em Publicar.
3. Pôr a URL do banco em `gradle.properties` como `dedo.firebaseDatabaseUrl`, por exemplo
   `https://dedo-nervoso-7284-default-rtdb.firebaseio.com`. Levar essa URL até o
   `online.properties` e até o `FirebaseConfig` do app faz parte do passo 1 de "Falta (app)".
