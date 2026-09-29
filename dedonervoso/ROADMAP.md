# Roadmap

Plano aprovado em 2026-09-29, uma versão por vez, cada uma com um APK para testar:

- **1.3 Campanha e Arena** (feita): fim da fase ao cumprir a missão, estrelas pelo tempo, bosses
  com barra de vida, Arena semanal no ranking, economia mais difícil, abertura e visual novos.
- **1.4 Identidade**: coleção de avatares, molduras, auras do Top 10, títulos, loja de cosméticos
  (só TAP COINS; GLOBAL ELITE e prêmios de boss nunca à venda), ligas por temporada na Arena.
- **1.5 Amigos e Boss Mundial**: fantasmas por fase, DESAFIAR AMIGO, melhor de 3 no duelo, itens
  APAGÃO / BÔNUS FALSO / ESCUDO, boss mundial diário ao vivo (jogadores reais, ranking de dano).
- **1.6 Variedade**: Eventos Nervosos (STOP RUSH, BLACKOUT, GOLD RUSH, PERFECT MODE, OVERLOAD),
  comandos novos (SWIPE, HOLD, SEQUÊNCIA, círculo que encolhe), fases feitas à mão até a 50.

## Feito (1.2) — Duelo ao vivo

- **Convite**: escolher um amigo e convidar; se ele estiver com o jogo aberto, o convite aparece
  na hora; se não, vai um link pelo WhatsApp.
- **Partida simultânea**: os dois jogam a mesma fase com exatamente os mesmos STOPs e zonas
  (mesma semente), com o placar do adversário ao vivo no topo. Firebase Realtime Database
  (stream em tempo real pela API REST, sem SDK), plano gratuito.
- **Itens de sabotagem**: cai uma bola na tela e só é capturada mantendo o ritmo por alguns
  segundos (ex.: 12 toques/s por 3 s — calibrado jogando). Com ela você lança no adversário:
  relógio −3 s, lentidão, STOP surpresa, tela embaçada; e há um escudo para se defender.
- Desconexão tratada (quem cai perde após alguns segundos), anti-trapaça básico.

## Em seguida

- **Entrar com Google** (um toque) ligando a conta anônima — progresso na nuvem para trocar
  de celular. Precisa liberar `dl.google.com` no ambiente e registrar o app Android no
  Firebase (pacote + SHA-1 em docs/FIREBASE.md).
- **Salvamento na nuvem** do progresso (moedas, fases, upgrades) ligado à conta.
- **Notificações com o app fechado** (convites de duelo): Firebase Cloud Messaging + Cloud
  Functions (exige o plano Blaze com cartão, com cota gratuita).
- **Mais viciante**: revanche instantânea, recompensa diária crescente, modo infinito,
  coleção de cosméticos, eventos semanais.

## Play Store

- Build com o Android Gradle Plugin (App Bundle `.aab`), targetSdk exigido no ano, política de
  privacidade (pode ficar no GitHub Pages), formulário de dados, classificação 13+.
- Teste fechado com 12 testadores por 14 dias (os amigos do ranking).
- Monetização sem pay-to-win: vídeo recompensado opcional, "remover anúncios", cosméticos.

## Princípios que não mudam

Sem pay-to-win, sem coleta de dados pessoais além do necessário para o online, online sempre
opcional e o jogo sempre jogável offline.
