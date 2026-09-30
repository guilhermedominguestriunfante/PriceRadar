# Dedo Nervoso

Jogo arcade de reflexo para Android: toque o mais rápido que conseguir, **pare no STOP**,
acerte as **Hot Zones**, encha o **FRENZY** e avance por fases infinitas com um **boss** a cada 10.
Cada fase acaba assim que a missão é cumprida (60 s são o limite) e as estrelas vêm do tempo; a
**Arena** da semana (60 s iguais para todos, sem upgrades) vale no ranking online com os amigos.

- Pacote: `com.dedonervoso.app` · versão **1.3.0** (versionCode 5)
- Android 8.0+ (minSdk 26) · targetSdk 35 · somente retrato
- Offline por padrão; online opcional (Firebase). Sem anúncios, sem compras, sem pay-to-win.
- Idiomas: português e inglês (automático pelo sistema ou escolhido nas configurações).

## Baixar e instalar

O APK de cada versão publicada fica em [`release/`](release/):

- **Link fixo (versão mais recente, depois do merge no `main`):**
  <https://raw.githubusercontent.com/guilhermedominguestriunfante/PriceRadar/main/dedonervoso/release/dedo-nervoso.apk>
- `release/version.json` descreve a versão (número, novidades, versão mínima para o online) e é
  o que o jogo consulta para avisar de atualizações.

No celular, abra o link, baixe e toque no arquivo; permita "instalar apps desconhecidos" para o
navegador. O Play Protect pode avisar que o app é de um desenvolvedor desconhecido
(*Mais detalhes → Instalar mesmo assim*): é normal para apps fora da Play Store.

Todas as versões são assinadas com a mesma chave, então as atualizações instalam **por cima**,
sem perder o progresso.

## Como jogar

1. **TAP** em qualquer lugar da arena. Toques seguidos formam **COMBO**, que sobe o
   multiplicador (x1.5, x2, x3, x4…). Parar de tocar quebra o combo (o prazo começa em
   2 s e diminui nas fases avançadas).
2. **STOP**: a tela avisa (bordas vermelhas + "!") e depois mostra STOP. **Não toque** até
   aparecer TAP. Tocar no STOP custa pontos, o combo e, em fases com vidas, uma vida.
   Um STOP pode ser **falso** (vira GO!) e há o **READY… WAIT… TAP!**, que mede seu reflexo.
3. **Hot Zones**: círculos x2 / x3 / x5, moeda, combo, +tempo e CRITICAL. Toque dentro para
   multiplicar; no centro é **PERFECT**. A partir da fase 21 elas se movem e encolhem
   (depois também orbitam e teleportam); algumas são **obrigatórias** (tocar fora é erro).
4. **FRENZY**: a barra inferior enche com o combo. Cheia = 5 s de pontos em dobro
   (e, raramente, o **MEGA FRENZY**: 6,5 s valendo x4). A **GOLDEN ZONE** aparece de
   surpresa e vale muitas moedas.
5. **Missão cumprida = fim da fase.** As estrelas vêm do tempo (+2 s por STOP errado); o recorde
   de cada fase é o melhor tempo. Nas fases de sobrevivência, passe por todos os STOPs.
6. **Boss** (fases 10, 20, 30…): toque nele (×3; o centro é PERFECT) até zerar a vida. Ele foge,
   ruge (STOP) e ataca: escudo, investida, apagão, -3 s e teleporte, e fica mais bravo ferido.
7. **Arena** (depois do primeiro boss): 60 s valendo pontos, sem upgrades, a mesma para todos na
   semana. É ela que conta nos rankings.
8. Ao final: estrelas, recordes, **TAP COINS** e XP. Gaste as moedas nos **upgrades**.

Vale tocar com dois dedos; um terceiro dedo simultâneo é ignorado.
Sair do app pausa a partida; ao voltar, há contagem 3-2-1.

## Conteúdo

- **Fases**: 1–30 feitas à mão, cada uma apresentando uma mecânica (TAP, COMBO, moedas,
  FRENZY, STOP, chefe, Hot Zones, PERFECT, reflexo, zonas especiais, zonas móveis, zona
  obrigatória, fake STOP, CRITICAL); a partir da 31, geradas de forma determinística e
  sem fim. Tipos: SPEED, SCORE, COMBO, PRECISION, SURVIVAL, PERFECT, FRENZY e BOSS
  (a cada 10 fases, com 3 vidas e STOP mais frequente).
- **Dificuldade adaptativa leve**: após falhas seguidas na mesma fase, o objetivo baixa
  (90% → 80% → 72%). Nunca fica mais difícil que o normal.
- **Upgrades** (economia progressiva): Double Tap, Triple Tap, Combo Boost, Combo Shield,
  Stop Shield, Coin Boost, Hot Zone Boost, Frenzy Boost e Critical Boost.
- **Progressão**: XP e nível do jogador, 25 conquistas, 3 missões diárias, desafio do dia
  com sequência (streak), ranking local (geral / semana / hoje) e estatísticas completas.
- **Áudio**: 40 efeitos sintetizados no build (sem arquivos de terceiros) e trilha
  procedural que reage ao combo, ao STOP e ao FRENZY. Vibração com efeitos do sistema.
- **Acessibilidade**: STOP nunca depende só da cor (ícone de mão + texto + som),
  opção **Reduzir efeitos** (sem tremor, flashes e piscadas), textos ajustados à largura.
- **Configurações**: música, efeitos sonoros, vibração, reduzir efeitos, mostrar FPS,
  ranking online, idioma, resetar progresso (com dupla confirmação), apagar dados online,
  procurar atualização e Sobre.
- **Identidade**: ícone da luva com o dedo "nervoso", logo DEDO / NERVOSO com tremedeira e
  abertura animada (pula com um toque).
- **Desenvolvedor**: botão com o LinkedIn do criador na tela inicial e em Sobre
  ([linkedin.com/in/guilhermekawe](https://www.linkedin.com/in/guilhermekawe/)).

## Online (opcional)

- **Ranking online**: Global, da Semana (zera toda segunda, 00:00 UTC) e entre **Amigos**, por
  pontos ou por taps. O jogador ativa em *Ranking → Online* ou nas Configurações, depois de ver o
  que fica visível (apelido, avatar e recordes).
- **Amigos**: cada jogador tem um **código de amigo** (ex.: `K7P-3QX`) no Perfil. *Convidar*
  compartilha o código e o link de download (WhatsApp ou qualquer app); *Adicionar amigo*
  recebe o código de alguém.
- **Conta**: anônima, criada sozinha (sem e-mail, sem senha); a identidade fica guardada no
  aparelho. *Configurações → Apagar dados online* remove tudo do servidor.
- **Versões**: o jogo consulta `release/version.json` ao abrir e avisa na tela inicial quando há
  versão nova (com as novidades e o botão Baixar). Quem estiver numa versão antiga continua
  jogando offline normalmente, mas **o online exige a versão mais recente**.
- Configuração do servidor: [docs/FIREBASE.md](docs/FIREBASE.md). Como publicar versões:
  [docs/RELEASING.md](docs/RELEASING.md).

## Privacidade

Permissões: `VIBRATE` e `INTERNET` (aviso de versões e, se o jogador ativar, o online). Nada de
localização, contatos, telefone ou e-mail. Offline, o progresso fica só no aparelho (JSON no
armazenamento interno, com backup do sistema se o usuário tiver ativado). Com o online ativo, o
servidor guarda apenas apelido, avatar, código de amigo, lista de amigos e recordes.

## Arquitetura

```
dedonervoso/
├── core/       Kotlin puro (sem Android): motor, regras, progressão, save, áudio, textos, online
├── app/        Cliente Android: activity, renderização, telas, som, vibração, armazenamento
├── tools/      Gerador dos efeitos sonoros (roda no build) e setup-cloud-toolchain.sh
├── testshim/   Substitutos mínimos de androidx.test para o Robolectric
├── buildSrc/   Plugin Gradle `dedonervoso.android-apk` que gera o APK com as ferramentas do SDK
├── firebase/   Regras do Firestore, emuladores e verify-online.sh
├── docs/       FIREBASE.md (configurar o online), CODEX-FIREBASE-SETUP.md (roteiro para o Codex)
│               e RELEASING.md (publicar versões)
└── release/    APK publicado + version.json (lido pelo jogo para avisar de atualizações)
```

**core** (testável na JVM, sem dependências):

- `engine/GameSession` — máquina de estados `READY → COUNTDOWN → PLAYING ⇄ STOP / FRENZY ⇄
  PAUSED → FINISHED`. Recebe toques com o timestamp do `MotionEvent` (o toque é julgado no
  instante em que aconteceu, não no próximo frame) e o relógio monotônico do Choreographer.
  Eventos saem por `GameListener` (tap, combo, STOP, zonas, frenzy, vidas, fim).
- `engine/GameBalance` — todos os números do jogo em um só lugar.
- `engine/Interrupts`, `Zone`, `TapMeters` — agenda de STOP / fake / reflexo, movimento
  analítico das zonas (congelam durante o STOP), TPS e limitador anti-trapaça (token bucket).
- `stage/StageCatalog` — fases à mão + geradas; metas calibradas por simulação
  (`BotPlayer` com perfis casual, médio e habilidoso).
- `progression/*` — economia, upgrades, XP, conquistas, missões, desafio diário, ranking
  (`LeaderboardSource` pronto para uma fonte online no futuro) e validação de resultados.
- `save/SaveCodec` — JSON versionado próprio, tolerante a campos ausentes/corrompidos.
- `online/*` — cliente REST do Firebase (conta anônima, Firestore), rankings, amigos e o
  manifesto de versões (`UpdatePolicy`: o online exige a versão mínima; offline nunca).
- `audio/*` — síntese dos efeitos e o motor de música procedural.

**app**:

- Uma `Activity` e uma `View` desenhada em Canvas acelerado por hardware, com loop no
  `Choreographer` (sem WebView). Telas são objetos `Screen` num `ScreenHost` com transições,
  diálogos e toasts.
- Efeitos com pool fixo (partículas, anéis, textos flutuantes), sem alocações por frame
  no gameplay.
- `SfxPlayer` (SoundPool, baixa latência), `MusicPlayer` (thread com AudioTrack),
  `Haptics` (thread dedicada), `SaveStore` (AtomicFile, escrita em segundo plano com
  debounce e flush ao pausar), `Updates` (aviso de versão) e `Online` (chamadas de rede numa
  thread própria, resultado entregue na thread da interface).

## Compilar

Requisitos: **JDK 21**, Android SDK com `platforms/android-35` e `platforms/android-34`
(os recursos são linkados contra a API 34) e build-tools com `aapt2`, `zipalign`,
`apksigner` e `d8` (ou `dx`). Informe o SDK em `local.properties` (`sdk.dir=...`) ou
em `ANDROID_HOME`. Ferramentas ausentes do SDK também são procuradas no `PATH`. Num
container Ubuntu/Debian novo (por exemplo, uma sessão do Claude Code na nuvem), rode
`bash tools/setup-cloud-toolchain.sh`. Ele instala o JDK e as ferramentas do Debian, baixa os
`android.jar` e grava o `local.properties`.

No **Windows**, o SDK do Android Studio serve direto (`aapt2.exe`, `d8.bat`, `apksigner.bat`…),
sem WSL. Sem a `android-34`, os recursos são linkados contra a `android-35`. Deixe o projeto num
caminho **sem espaços**, porque o Robolectric não encontra os próprios arquivos num caminho com
espaço.

```bash
./gradlew assembleDebug         # app/build/outputs/apk/debug/dedo-nervoso-debug.apk
./gradlew assembleRelease       # app/build/outputs/apk/release/dedo-nervoso-release.apk
./gradlew publishRelease        # release assinado → release/dedo-nervoso.apk + version.json
./gradlew installDebug          # instala no aparelho conectado (adb)
./gradlew check                 # todos os testes (ver abaixo)
```

**Assinatura de release**: `DEDO_KEYSTORE_B64` (o keystore em base64) e
`DEDO_KEYSTORE_PASSWORD` no ambiente — ou `keystore.properties` na raiz (`storeFile`,
`storePassword`, `keyAlias`, `keyPassword`). Keystores e `keystore.properties` estão no
`.gitignore` e **nunca** devem ser versionados. `publishRelease` se recusa a publicar um APK
assinado com a chave de debug.

**Online**: o projeto (`dedo-nervoso-7284`) está em `gradle.properties`. A chave de API da Web
vem de `DEDO_FIREBASE_API_KEY` no ambiente de build e nunca é versionada (veja
[docs/FIREBASE.md](docs/FIREBASE.md)). Sem a chave o build funciona, o online aparece como
indisponível, e `publishRelease` se recusa a publicar.

**Por que não AGP / Compose?** O ambiente em que o projeto foi criado não acessava o
repositório Maven do Google (dl.google.com), então o plugin em `buildSrc` executa
diretamente as ferramentas do SDK (aapt2 → Kotlin → ProGuard → d8/dx → zipalign →
apksigner v2/v3). Para um arcade de baixa latência, o motor próprio em Canvas também é a
escolha natural. A migração para o AGP está no [ROADMAP](ROADMAP.md).

## Testes

- `:core:test` — motor (estados, combo, STOP e penalidades, zonas, frenzy, pausa,
  anti-trapaça, multitoque), progressão, economia, save, catálogo de fases, JSON, áudio
  (sem clipping/NaN, volume, ducking no STOP) e simulação de balanceamento.
- `:app:test` — Robolectric com renderização Skia real: primeiro acesso + onboarding +
  partida completa + progresso salvo e recarregado, pausa ao sair com 3-2-1, STOP / zonas /
  frenzy, navegação por todos os menus, botão do LinkedIn, regra de 2 dedos, tela pequena.
  Capturas de tela vão para `app/build/screenshots/`.
- Online: testes de integração contra os **emuladores oficiais do Firebase** com as regras reais
  (contas, códigos, rankings, amigos, regras recusando trapaças) e o fluxo completo no app
  (ativar, ranking global, adicionar amigo, partida publicada no ranking da semana, versão
  obrigatória). Pulados quando os emuladores não estão rodando. Os testes nunca falam com o
  projeto real, mesmo num build que tenha a chave.
- `firebase/verify-online.sh <projeto>` — confere o **projeto real** do jeito que o jogo usa
  (login, renovação da sessão, regras, rankings, limpeza), sem mostrar a chave.
- `:app:releaseSmokeTest` — executa o **bytecode de release** (saída otimizada e ofuscada
  do ProGuard) jogando uma sessão inteira só por toques e confere o save em disco.

## Créditos

Desenvolvedor: [linkedin.com/in/guilhermekawe](https://www.linkedin.com/in/guilhermekawe/).

Fontes (SIL Open Font License 1.1, licenças em `app/src/main/assets/licenses/`):
"Nervoso Display", versão modificada da Orbitron (© 2018 The Orbitron Project Authors), e
Rajdhani (© 2014 Indian Type Foundry). Sons e música são gerados pelo próprio código.

Veja também: [CHANGELOG](CHANGELOG.md) · [ROADMAP](ROADMAP.md)
