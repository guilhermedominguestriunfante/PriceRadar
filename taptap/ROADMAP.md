# Roadmap

O que vem depois da 1.0.0, em ordem de prioridade.

## Curto prazo (1.0.x)

- **Testes em aparelhos reais**: rodar a 1.0.0 em 3–4 aparelhos (um de entrada, um médio,
  um topo de linha, um com tela 20:9 e notch) medindo latência toque→som, FPS com efeitos
  cheios e consumo de bateria numa sessão de 15 min; ajustar `GameBalance` com dados reais
  de jogadores (a calibração atual vem de jogadores simulados).
- **Build padrão Android**: migrar do plugin próprio (`buildSrc`) para o Android Gradle
  Plugin quando o repositório Maven do Google estiver acessível, mantendo o nome dos APKs.
  Isso habilita R8, App Bundle (`.aab`), baseline profiles e o Android Lint.
- **CI**: workflow que roda `./gradlew check publishApks` a cada push e publica os APKs
  como artefatos; assinatura de release via secrets do repositório.
- **Testes instrumentados** (emulador) para o que o Robolectric não cobre: SoundPool e
  AudioTrack reais, vibração, insets de notch e navegação por gestos.

## Médio prazo (1.1)

- **Ranking online opcional**: nova implementação de `LeaderboardSource` (global, amigos,
  temporadas), com os resultados passando pelo `ResultValidator` também no servidor.
  Continuará opcional: o jogo segue 100% jogável offline e sem conta.
- **Desafio diário compartilhado**: mesma semente para todos no dia, com placar próprio.
- **Mais conteúdo**: novos tipos de zona (bomba que exige evitar, zona de ritmo), novos
  chefes com padrões próprios e eventos de fim de semana.
- **Cosméticos por moedas**: temas de cor e trilhas de partículas — sem efeito no placar.
- **Acessibilidade**: modo daltônico com paleta alternativa, tamanho de texto ajustável e
  opção de vibração mais forte para o STOP.

## Longo prazo

- Temporadas com passe gratuito de recompensas cosméticas.
- Modo versus local (dois jogadores na mesma tela, metade cada).
- Replays curtos exportáveis do melhor momento da partida.
- Mais idiomas (espanhol primeiro).

## Princípios que não mudam

Sem anúncios, sem compras com dinheiro real, sem pay-to-win, sem coleta de dados pessoais
e sem exigir conta para jogar.
