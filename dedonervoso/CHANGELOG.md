# Changelog

Formato baseado em [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/); versões seguem
[SemVer](https://semver.org/lang/pt-BR/).

## [1.3.1] — 2026-09-29 · Lobby

Versão 6. Só visual: o formato online não mudou, então a 1.3.0 continua jogando online.

### Tela inicial (lobby)
- O mascote ocupa a tela e os comandos ficam nas bordas, como no protótipo aprovado:
  - topo: avatar numa moldura octogonal dourada com o nível, nome e barra de XP, moedas com "+"
    (loja), convites de duelo (com contador) e configurações;
  - esquerda: cartão do desafio do dia (recompensa ou contagem para o próximo), cartão do próximo
    boss com a ilustração dele (toque seleciona a fase quando liberada), loja e conquistas;
  - direita: gaveta de amigos com recorde da Arena ou fase de cada um e o botão de duelo; sem
    online, um convite para ativar;
  - base: a missão mais perto da recompensa num balão, o seletor de fase (estrelas e melhor tempo),
    o emblema da ARENA, o JOGAR dourado com brilho e o menu (Upgrades, Duelo, Missões, Ranking,
    Perfil).
- O link do LinkedIn do desenvolvedor fica em Configurações → Sobre.

### Visual em todas as telas
- Fontes novas: Bungee nos títulos, botões e números; Permanent Marker no detalhe grafite.
- Painéis de vidro com cantos cortados e acentos dourados, botão principal como placa dourada com
  brilho passando, botões redondos com aro, títulos com filete dourado.
- A partida usa a cena da rua escurecida de fundo, mantendo as cores de STOP e FRENZY.
- No duelo, amigos sem recorde da Arena mostram a fase em vez de "melhor 0".

## [1.3.0] — 2026-09-29 · Campanha e Arena

Versão 5. O online passa a exigir esta versão: o ranking agora é da Arena e as regras do servidor
mudaram (`minAppVersion` 5).

### Fases
- A fase termina no instante em que a missão é cumprida ("MISSÃO CUMPRIDA!", com câmera lenta e o
  tempo na tela); os 60 s passam a ser só o limite. Falhar também encerra na hora (sem vidas).
- Estrelas pelo tempo: tempo da partida + 2 s por STOP errado. ★★ no tempo de um jogador médio,
  ★★★ no de um habilidoso (calibrados por simulação, `StageTimesReport`). Durante a partida,
  embaixo do relógio, aparecem as estrelas em jogo e quanto tempo falta para perdê-las.
- SURVIVAL virou "Sobreviva a N STOPs" (duas STOPs a mais, mais juntas, 48 s de limite) e termina
  no último STOP; as estrelas contam os erros (sem erro ★★★, 1 erro ★★).
- O recorde de cada fase é o melhor tempo (mostrado na tela inicial).
- Todas as fases e a Arena usam a mesma semente para todos: mesmos STOPs e zonas em cada tentativa.

### Bosses
- Fases 10, 20, 30…: O DEDO FURIOSO, PUNHO DE FERRO, O CRONÔMETRO, GLITCH e REI NERVOSO (da 60 em
  diante voltam mais fortes). Ilustrados, com barra de vida ilustrada e o rosto do boss.
- A vida do boss é a meta de pontos: tocar nele vale ×3 e o centro é PERFECT; ele anda e foge do
  dedo, ruge (o STOP) e ataca: ESCUDO, INVESTIDA, APAGÃO, -3 s no relógio e TELEPORTE, sempre
  avisados antes. Abaixo de 50% e 20% da vida entra em FÚRIA.
- Derrotar o boss encerra a fase com explosão e som próprios.

### Arena e ranking
- Botão ARENA na tela inicial (liberado depois do boss da fase 10): 60 s valendo pontos, sem
  upgrades, a mesma arena para todos durante a semana ISO.
- Os rankings Global, da Semana e de Amigos passam a contar só a Arena (`arenaBest` no perfil e
  `arenaWeeks/{semana}/scores`). As partidas da campanha só atualizam a fase mais alta.
- O resultado da Arena mostra a posição da semana (TOP X% ou #N de M).
- O recorde antigo vira "Recorde clássico" no perfil; o ranking do aparelho recomeça com a Arena.

### Economia
- Moedas mais difíceis: base, desempenho, estrelas, primeira vitória, recorde, bônus de boss,
  COIN BOOST (+6% por nível), desafio diário, missões, subida de nível e moedas das zonas
  douradas e dos PERFECT, todos reduzidos. Repetir uma fase já vencida paga metade da base.
- Bônus de rapidez: moedas e XP pelos segundos que sobraram.

### Visual e som
- Abertura nova com a ilustração do mascote: a cena surge do escuro, o dedo bate no celular no
  impacto do som (clarão, tremor, faíscas) e os "CLICA! CLICA! VAI!" pulsam.
- Tela inicial com o mascote (tocar nele solta faíscas); os menus usam a cena desfocada de fundo.
- Ícone novo (o rosto do mascote), também na abertura do Android 12+.
- Sons novos: rugido, golpe e ataque do boss, derrota do boss, missão cumprida e o impacto da
  abertura.
- Tela de resultado nova: tempo, pontos, taps, TAP/s médio e máximo, combo, precisão, reflexo,
  moedas e XP, e o que falta para a próxima estrela.

## [1.2.0] — 2026-09-28 · Duelo ao vivo

Duelos ao vivo entre amigos (versionCode 4), pelo Realtime Database do projeto
`dedo-nervoso-7284`. O online passa a exigir esta versão.

### Duelo
- Botão DUELO na Home, com o online conectado. O lobby explica os itens e lista os desafios
  recebidos e os amigos, cada um com DESAFIAR.
- O convite chega na hora ("X te desafiou!"), com ACEITAR e RECUSAR, e nunca aparece no meio de
  uma partida. O desafio expira em 60 s. Para chamar o amigo, dá para mandar mensagem pelo
  WhatsApp.
- Os dois começam juntos: o início é marcado pelo relógio do servidor, com o 3-2-1 sincronizado.
- Na partida:
  - o placar do rival e uma barra comparando os dois ficam no lugar do objetivo;
  - quem mantém o ritmo enquanto uma bola cai captura o item dela: LENTO (9/s·3s), RELÓGIO
    (11/s·4s) ou STOP (13/s·5s);
  - o botão do item joga no rival, e o toque nele não conta como toque do jogo.
- Não há pausa: sair do app não para o relógio, e o Voltar pergunta se quer desistir.
- No resultado: VITÓRIA, DERROTA ou EMPATE, os dois placares e os itens. Se o rival não
  terminar, é W.O. Dá para pedir REVANCHE.

### Ferramentas
- O build e os testes rodam no Windows com o SDK do Android Studio, sem WSL.
- O `publishRelease` confere o APK antes de publicar: nomes de arquivo com `/` (o `aapt2` do
  Windows gravava `\`), fontes, sons e a configuração do online.
- `DuelFlowTest` joga o duelo inteiro contra os emuladores, dos dois lados.

## [1.1.1] — 2026-09-28 · Online ligado

Primeira versão com o online ligado (versionCode 3), no projeto Firebase `dedo-nervoso-7284`
(Firestore em São Paulo, login anônimo). O online passa a exigir esta versão.

### Online
- O APK sai com o projeto do Firebase configurado: ranking Global, da Semana e entre Amigos
  funcionando de verdade.
- `publishRelease` se recusa a publicar um APK sem a configuração do online
  (`-PofflineRelease=true` para uma exceção).

### Ferramentas
- `firebase/verify-online.sh` confere o projeto real do jeito que o jogo usa: login, renovação
  da sessão, regras, rankings (amigos, global, semana) e limpeza. A chave vem do ambiente ou do
  Firebase CLI e não aparece na saída.
- Checagem ao vivo opcional (`DEDO_LIVE_FIREBASE=1`) contra o projeto real, com jogadores
  descartáveis que são apagados no fim:
  - `OnlineLiveTest` roda o código online do jogo: cadastro, amizade, sessão retomada, partida e
    rankings, e confere que nada ficou para trás;
  - o teste do release comprova que o bytecode ofuscado do APK entra online sozinho.
- Os testes nunca falam com o projeto real, mesmo num build que tenha a chave.

## [1.1.0] — 2026-09-28 · Dedo Nervoso

Novo nome e primeira versão online (`com.dedonervoso.app`, versionCode 2). É um app novo para o
Android (pacote e assinatura novos): quem tinha o TAP TAP 1.0.0 instala este separadamente.

### Identidade
- Nome **Dedo Nervoso**, ícone da luva com o dedo tremendo (versão temática/monocromática),
  logo DEDO / NERVOSO com tremedeira e abertura animada com som (um toque pula).
- Fonte de títulos renomeada "Nervoso Display" (derivada da Orbitron, OFL).

### Online (opcional, Firebase)
- Ranking online Global, da Semana e entre Amigos, por pontos ou por taps.
- Código de amigo no Perfil, *Convidar* (compartilha código + link) e *Adicionar amigo*.
- Conta anônima automática; *Apagar dados online* remove tudo do servidor.
- Regras de segurança no servidor: cada um só altera os próprios dados, valores plausíveis,
  recorde nunca diminui, versão mínima para gravar.

### Versões e atualizações
- Aviso de **nova versão** na tela inicial, com novidades e botão Baixar; *Procurar
  atualização* nas Configurações.
- O online exige a versão mais recente; offline continua liberado em qualquer versão.
- Release sempre assinado com a mesma chave (atualizações instalam por cima);
  `publishRelease` gera `release/dedo-nervoso.apk` e `release/version.json`.

### Correções
- Build de release a partir de um `clean` (regras do ProGuard eram apagadas antes de rodar).
- Testes do app agora rodam de novo quando recursos ou assets mudam.

## [1.0.0] — 2026-09-28

Primeira versão jogável e instalável (`com.dedonervoso.app`, versionCode 1).

### Jogo
- Partida de 60 s com contagem 3-2-1, HUD mínimo (fase, objetivo, tempo, pontuação, taps,
  combo, TPS) e resultado com revelação progressiva, estrelas e comemoração de recorde.
- Toque julgado no timestamp do evento, com resposta visual, sonora e tátil imediata.
- Combo com níveis de multiplicador e quebra por inatividade.
- STOP com aviso prévio, janela de reação e penalidades por nível (leve / média / pesada);
  Fake STOP (vira GO! x2) e READY… WAIT… TAP! com medição do tempo de reação.
- Hot Zones x2 / x3 / x5, moeda, combo, +tempo, CRITICAL e GOLDEN ZONE; zonas fixas,
  móveis, em órbita, teleporte, que encolhem e obrigatórias; acerto PERFECT no centro.
- FRENZY (5 s, pontos x2) e MEGA FRENZY raro (6,5 s, x4).
- Fases 1–30 feitas à mão apresentando uma mecânica por vez; fases 31+ geradas sem fim;
  tipos SPEED, SCORE, COMBO, PRECISION, SURVIVAL, PERFECT, FRENZY e BOSS (a cada 10).
- Dificuldade adaptativa leve após falhas seguidas (objetivo 90% / 80% / 72%).
- Pausa automática ao sair do app, retomada com 3-2-1; no máximo 2 dedos simultâneos;
  limitador anti-trapaça e validação de resultados improváveis.

### Progressão
- TAP COINS, 9 upgrades com preços progressivos (sem compras reais, sem pay-to-win).
- XP e nível do jogador, 25 conquistas, 3 missões diárias, desafio do dia com sequência.
- Ranking local (geral, semana, hoje) com interface pronta para fontes online futuras.
- Perfil local (apelido e avatar) com estatísticas completas.
- Salvamento automático em JSON versionado e atômico; recuperação de arquivo corrompido.

### Apresentação
- Visual neon com fundo animado, partículas, anéis, textos flutuantes, tremor de tela.
- Logo, ícone adaptativo (com versão monocromática) e splash.
- Onboarding curto e cartões de introdução para cada mecânica nova.
- 40 efeitos sonoros sintetizados no build e trilha procedural adaptativa.
- Vibração por evento (toque, perfect, erro, comemoração).
- Configurações: música, efeitos, vibração, reduzir efeitos, FPS, idioma (PT/EN),
  resetar progresso com dupla confirmação, Sobre.
- Botão do desenvolvedor (LinkedIn) na tela inicial e em Sobre.

### Técnico
- Motor em Kotlin puro (`core`) separado do cliente Android (`app`), com máquina de
  estados explícita, eventos via `GameListener` e balanceamento centralizado em
  `GameBalance`, calibrado por simulação de jogadores.
- Renderização própria em Canvas acelerado com loop no Choreographer; pools fixos de
  efeitos e nenhuma alocação por frame no gameplay.
- Pipeline de APK próprio (`buildSrc`): aapt2, ProGuard (encolhe, otimiza, ofusca),
  d8/dx, zipalign e assinatura v2/v3.
- Testes: unidade do motor, progressão e áudio; simulação de balanceamento; fluxos
  completos no Robolectric com renderização real; smoke test do bytecode de release.
