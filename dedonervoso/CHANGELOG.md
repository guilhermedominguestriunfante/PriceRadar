# Changelog

Formato baseado em [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/); versões seguem
[SemVer](https://semver.org/lang/pt-BR/).

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
