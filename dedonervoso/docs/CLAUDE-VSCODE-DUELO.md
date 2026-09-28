# Tarefa para o Claude (VS Code): terminar o duelo ao vivo do Dedo Nervoso

Você vai terminar o **duelo ao vivo** do jogo Android **Dedo Nervoso** e publicar a versão
**1.2.0**. O servidor e o motor do jogo já estão prontos e testados. Falta a parte do app: telas,
HUD e convites. Leia também `dedonervoso/docs/DUELO.md`.

## Regras

- Trabalhe no branch `claude/tap-tap-master-spec-1s18av` do repositório
  `guilhermedominguestriunfante/PriceRadar`. O jogo fica em `dedonervoso/`.
- **Nunca** mostre, grave ou faça commit de segredos: a chave de API do Firebase, o keystore ou a
  senha. Eles vêm só de variáveis de ambiente.
- Não afrouxe as regras de segurança (`firebase/*.rules*`) para um teste passar.
- Siga o estilo do código: Kotlin, motor próprio em Canvas, sem AndroidX nem SDK do Firebase
  (tudo por REST). Os comentários de código ficam em inglês e os textos do jogo em PT e EN.
- Antes de cada commit, rode `./gradlew check` (na pasta `dedonervoso`), com os emuladores
  ligados, e só faça commit com tudo verde.

## 1. Ambiente (Windows: use WSL com Ubuntu)

As ferramentas de build do projeto são as do Linux (Debian), então use o **WSL**.

1. No PowerShell, rode `wsl --install -d Ubuntu`.
2. Abra o VS Code na pasta dentro do WSL (extensão "WSL").
3. No terminal do WSL:

```bash
git clone -b claude/tap-tap-master-spec-1s18av https://github.com/guilhermedominguestriunfante/PriceRadar.git
cd PriceRadar/dedonervoso
sudo bash tools/setup-cloud-toolchain.sh   # JDK 21, aapt2, zipalign, apksigner, dx, android.jar
sudo apt-get install -y nodejs npm          # para os emuladores do Firebase
(cd firebase && npx --yes firebase-tools@13 emulators:start --project demo-dedonervoso) &   # auth 9099, firestore 8080, database 9000
./gradlew check                             # tudo deve passar antes de começar
```

Para o release, a pessoa usuária exporta no terminal do WSL, sem colar no chat, estas três
variáveis:

- `DEDO_KEYSTORE_B64` e `DEDO_KEYSTORE_PASSWORD`: estão no arquivo `dedo-nervoso-assinatura.txt`;
- `DEDO_FIREBASE_API_KEY`: a chave de API da Web, em Firebase → Configurações do projeto.

## 2. O que já existe (use, não reescreva)

**Servidor: `core/online/DuelService.kt` e `RealtimeDb.kt`**

- Métodos:
  - `challenge(session, profile, friendUid)`, que retorna o `duelId`;
  - `cancel`, `accept(invite)`, que retorna false se a sala sumiu, e `decline`;
  - `join`, que retorna o **offset** (relógio do servidor − local);
  - `setStart(session, id, startAtServerMs)`, só para o anfitrião, depois do aceite;
  - `report(session, id, DuelReport(...))`, que também retorna o offset;
  - `throwItem(session, id, index, type)`, com índice 0..9, um por item;
  - `close`, `room`;
  - `watch(session, id, onRoom)`, em que `onRoom` recebe null quando a sala acaba;
  - `watchInvites(session, onInvites)`.
- `DuelRoom` traz `host`, `guest`, `seed`, `accepted`, `startAtMs`, `players` (placar, `done`,
  `atMs`) e `throws`. Os callbacks chegam na thread do stream: poste para a UI.
- Uma sessão Firebase sai da conta online:
  `FirebaseClient(config, http).resume(account.uid, account.refreshToken)` (em thread de rede).

**Motor: `core/engine/Duel.kt` e `GameSession`**

- `GameSession(StageCatalog.duel(), Loadout.NONE, alturaArena, seed = room.seed, listener, duel = true)`.
- Estado: `orbs`, `activeOrb` (`item.requiredTps`, `progress`, `fall(matchTimeMs)`, `x`),
  `heldItem`, `slowActive`, `slowFraction`, `clockLostMs`.
- Ações: `useItem(now)`, que devolve o item jogado, e `receiveItem(DuelItem.of(type)!!, now)`.
- Listener: `onOrbSpawn`, `onOrbCaptured`, `onOrbMissed`, `onItemUsed`, `onItemHit`.

**Regras e testes**

- Regras: `firebase/database.rules.json`.
- Testes de referência: `DuelEmulatorTest` e `DuelSessionTest`.
- Fluxo de app contra os emuladores, como modelo: `app/src/test/.../OnlineFlowTest.kt`. Ele
  usa `GameHarness` e `Endpoints.firebaseOverride = FirebaseConfig.emulator(...)`, que já inclui
  o banco.

## 3. O que fazer

1. **Configuração:** o `gradle.properties` já tem `dedo.firebaseDatabaseUrl`.
   - Em `app/build.gradle.kts` (`generateOnlineConfig`), escreva também
     `databaseUrl=` no `online.properties`.
   - Em `Online.loadConfig`, passe esse valor para `FirebaseConfig(databaseUrl = ...)`.
   - O duelo só aparece com `config.duelsConfigured` e o online em `READY`.
2. **`app/platform/Duel.kt`:** o gerenciador na thread da UI, com a rede no executor `net`.
   - **Convites:** stream de `invites/<meu uid>` enquanto o app está aberto e online.
     Ignore os que têm mais de 10 min.
   - **Anfitrião:** `challenge` → espera `accepted` (60 s; depois, `cancel`) → `join` →
     `setStart(agoraServidor + 4000)`.
   - **Convidado:** `accept` → `join` → espera `startAtMs`.
   - **Hora local do início:** `startAtMs − offset`, convertida para `SystemClock.uptimeMillis`.
   - **Na partida:** `report` a cada ~400 ms e ao terminar (`done = true`). Aplique cada
     `throw` do rival uma única vez (pela `key`). O rival conta como desconectado se o `atMs`
     dele ficar mais de 8 s sem mudar.
   - **Fim:** espere o rival até 15 s (se ele não terminar, é W.O.). Depois o anfitrião chama
     `close`.
3. **Telas** (siga `RankingScreen` e `ProfileScreen`):
   - `DuelLobbyScreen`: explica os itens (LENTO 9/s·3s, RELÓGIO 11/s·4s, STOP 13/s·5s) e lista
     os convites recebidos e os amigos. Os amigos vêm de `online.leaderboard(FRIENDS)`, cada um
     com o botão DESAFIAR.
   - `DuelWaitScreen`: aguardando o amigo, com "Chamar no WhatsApp" (reuse `shareInvite`) e
     Cancelar.
   - `DuelResultScreen`: VITÓRIA, DERROTA ou EMPATE, os dois placares, os itens e o botão
     REVANCHE.
   - Botão **DUELO** na `HomeScreen`.
   - Diálogo global "X te desafiou!" (ACEITAR/RECUSAR), que não aparece no meio de uma partida.
4. **`PlayScreen` em modo duelo:** um parâmetro opcional com a partida.
   - **Início:** fase de espera até `início − 2400 ms`, e aí `session.start`.
   - **Pausa:** não existe. O Voltar pergunta "Desistir?", e sair do app não pausa.
   - **HUD:**
     - no lugar da barra de objetivo: o nick e o placar do rival, com uma barra comparando os
       dois;
     - a bola caindo em `x`, na altura `fall()`, com o anel de `progress` e o rótulo
       `requiredTps`;
     - o botão do item, cujo toque **não** conta como tap do jogo.
   - **Efeitos recebidos:** banner, flash e som. No LENTO, uma tinta azul durante `slowActive`.
   - **Ao terminar:** o `report` final e o `DuelResultScreen`.
5. **Textos** PT e EN em `core/i18n`. Reaproveite os sons que já existem em `Sfx`.
6. **Testes:**
   - um teste Robolectric do fluxo completo contra os emuladores, em que o rival é simulado
     com `DuelService` direto: convite, aceite, partida, item recebido e resultado;
   - o `./gradlew check` inteiro verde.
7. **Release 1.2.0:**
   - `versionCode` 4, `versionName` "1.2.0", `app/release-notes.json` e `CHANGELOG.md`;
   - `./gradlew check publishRelease`, com as 3 variáveis do passo 1;
   - confira no APK o SHA-256 do certificado `5b272550…`;
   - commit, push e PR para `main`, e faça o merge. É isso que libera o aviso de nova versão e
     o link fixo.
8. **Regras no Firebase de verdade:** peça à pessoa usuária para publicar a versão atual de
   `firebase/database.rules.json`. Ela corrige a falha em que o anfitrião criava a sala já
   aceita. Pode ser pelo console, colando em Realtime Database → Regras, ou, com o Firebase CLI
   logado, dentro de `dedonervoso/firebase`:
   `npx --yes firebase-tools@13 deploy --only database --project prod`.
9. **Teste real:** dois celulares com a 1.2.0 fazem um duelo inteiro.

## Pronto quando

- [ ] Um duelo entre dois aparelhos funciona do convite ao resultado, com itens nos dois sentidos.
- [ ] `./gradlew check` está verde, com os emuladores, e o teste novo do duelo existe.
- [ ] As regras corrigidas estão publicadas no projeto `dedo-nervoso-7284`.
- [ ] A 1.2.0 foi publicada e está no `main`, e nenhum segredo foi versionado.
