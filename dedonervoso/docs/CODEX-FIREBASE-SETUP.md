# Tarefa para o Codex: configurar o Firebase do Dedo Nervoso

> **Status:** feito em 28/09/2026, no projeto `dedo-nervoso-7284`. Os detalhes estão em
> [FIREBASE.md](FIREBASE.md). Este roteiro continua valendo para montar um projeto do zero.

> **Para você, antes de chamar o Codex (5 minutos)**
>
> 1. Instale no seu computador o **Node.js LTS** (18 ou mais novo) e o **Git**.
> 2. No **seu** terminal, rode o comando abaixo e conclua o login no navegador com a conta Google
>    do Firebase:
>
>    ```bash
>    npx --yes firebase-tools@13 login
>    ```
>
>    O Codex não consegue fazer esse login, porque ele precisa de um terminal interativo. O login fica
>    salvo no seu usuário do computador, e o Codex passa a usá-lo. No Windows, se o PowerShell
>    bloquear o `npx`, use o **Prompt de Comando**.
> 3. Abra o Codex **no seu computador** (Codex CLI, extensão do VS Code ou app de desktop), numa pasta
>    vazia. O Codex na nuvem (chatgpt.com/codex) não serve para esta tarefa, porque ele não enxerga o
>    login do item 2.
> 4. Deixe o Codex usar a internet e gravar fora da pasta, porque o cache do npm e o login do Firebase
>    ficam na sua pasta de usuário. Escolha o modo de acesso total ou aprove os comandos quando ele
>    pedir.
> 5. Mande este arquivo para o Codex com a mensagem: **"Siga exatamente as instruções deste arquivo e
>    no fim me entregue o relatório."**
> 6. No meio da tarefa ele vai pedir que você ative o login anônimo no console do Firebase (3
>    cliques). No fim você recebe o `FIREBASE-SETUP-REPORT.md`. Esse relatório é o que você leva de
>    volta para o Claude.

---

## Instruções para o Codex

Você vai preparar o backend online do jogo Android **Dedo Nervoso** no Firebase da pessoa usuária e,
no fim, escrever um relatório. O código do jogo já está pronto e testado contra os emuladores do
Firebase. O trabalho aqui é:

- criar e configurar o projeto;
- publicar as regras de segurança que já estão no repositório;
- rodar o script de verificação.

Leva uns 15 minutos.

### Contexto (não mude nada disto)

- Repositório: <https://github.com/guilhermedominguestriunfante/PriceRadar> (público).
- Branch: `claude/tap-tap-master-spec-1s18av`.
- Pasta do backend: `dedonervoso/firebase/`:
  - `firestore.rules`: regras de segurança. Publique **exatamente** como estão.
  - `firestore.indexes.json`: índices. Está vazio; publique mesmo assim.
  - `firebase.json`: configuração do Firebase CLI.
  - `verify-online.sh`: testa o projeto do jeito que o jogo usa e apaga tudo o que criou.
- App Android: pacote `com.dedonervoso.app`.
- O jogo usa **Authentication anônima** e o **Cloud Firestore** pela API REST, no plano gratuito
  Spark (sem cartão).
- Certificado de assinatura do app (para o futuro login com Google):
  - SHA-1: `788dd8656883c9a405dc3f243b8e76fa148fc372`
  - SHA-256: `5b272550be2ad9e89dc1c5efb0cc271842bcbe5c87c8b244d4a9aa6af0c44de0`

### Acessos de que você precisa

1. **Terminal no computador da pessoa usuária** com Node.js 18+ (`npx`), `git`, `curl` e `bash`.
   No Windows, use o `bash.exe` do Git. Ele costuma ficar em `C:\Program Files\Git\bin\` ou
   em `%LOCALAPPDATA%\Programs\Git\bin\`.
2. **Internet nos comandos**, para `registry.npmjs.org`, `github.com` e `*.googleapis.com`. Se o
   sandbox bloquear a rede ou a gravação em `~/.npm` e `~/.config`, peça aprovação para rodar o
   comando fora do sandbox.
3. **Firebase CLI já logado pela pessoa usuária.** Confira com
   `npx --yes firebase-tools@13 login:list`. **Não rode `firebase login` você mesmo**: ele falha fora
   de um terminal interativo. Se algum comando responder `Failed to authenticate`, peça que o login
   seja feito de novo no terminal da própria pessoa e repita o comando.
4. **Leitura do repositório**, que é público, então basta clonar. Não precisa de acesso ao GitHub.
5. **Nenhum acesso a faturamento.**

### Regras

- Não ative faturamento nem o plano Blaze. Se algo pedir cartão, **pare** e anote.
- Não altere nenhum arquivo do repositório e não faça commit nem push. Se o deploy der erro,
  **não conserte as regras**: anote o erro completo.
- Nunca mostre nem grave a **Chave de API da Web**, tokens ou senhas: nada no chat, no relatório ou
  em arquivos. Não rode `apps:sdkconfig` e não baixe `google-services.json`. O script de verificação
  lê a chave sozinho e não a exibe.
- Não crie Cloud Functions, Hosting, Storage nem App Check. Não ative outros provedores de login:
  só o Anônimo.
- Nunca use `--force` e não apague nada que já exista no projeto.
- **Cada comando roda num shell novo**: variáveis e `cd` não passam de um comando para o outro.
  - Escreva o ID do projeto por extenso onde aparece `PROJECT_ID`.
  - Rode os comandos dos passos 4 e 6 com o diretório de trabalho em `PriceRadar/dedonervoso/firebase`.
- Se um erro não se resolver com uma nova tentativa depois de 1 minuto, pare e anote a mensagem
  completa no relatório.

### Passo 0: preparar

```bash
git clone --branch claude/tap-tap-master-spec-1s18av --depth 1 https://github.com/guilhermedominguestriunfante/PriceRadar.git
git -C PriceRadar rev-parse HEAD
npx --yes firebase-tools@13 --version
npx --yes firebase-tools@13 login:list
npx --yes firebase-tools@13 projects:list
```

Confirme com a pessoa usuária que a conta mostrada por `login:list` é a certa.

### Passo 1: projeto

Pergunte qual opção a pessoa usuária prefere: um **projeto novo só para o jogo** (recomendado) ou
um projeto que já existe.

- **Novo.** O ID é único no mundo. Se `dedo-nervoso` já estiver em uso, tente `dedo-nervoso-` seguido
  de 4 dígitos aleatórios.

  ```bash
  npx --yes firebase-tools@13 projects:create dedo-nervoso --display-name "Dedo Nervoso"
  ```

  Se a criação falhar (termos não aceitos, limite de projetos), peça para criar pelo console e
  informar o ID: <https://console.firebase.google.com> → **Adicionar projeto** → nome
  `Dedo Nervoso` → Google Analytics **desativado** → **Criar**.
- **Existente.** Veja se já há um banco Firestore:

  ```bash
  npx --yes firebase-tools@13 firestore:databases:list --project PROJECT_ID
  ```

  Se o banco já for usado por outro app, **pare antes do passo 4** e avise: publicar as regras
  substitui as regras atuais inteiras. Nesse caso, recomende um projeto novo.

### Passo 2: login anônimo (a pessoa usuária faz no console)

Peça para abrir `https://console.firebase.google.com/project/PROJECT_ID/authentication/providers` e
clicar em:

1. **Vamos começar** (se aparecer);
2. **Anônimo**;
3. **Ativar**;
4. **Salvar**.

Siga com os passos 3 a 5 enquanto isso. O passo 6 precisa deste pronto.

### Passo 3: banco Firestore em São Paulo

Se o banco `(default)` já existir, não crie outro: só anote a região (o jogo funciona em qualquer
uma). Senão:

```bash
npx --yes firebase-tools@13 firestore:databases:create "(default)" --location southamerica-east1 --delete-protection ENABLED --project PROJECT_ID
npx --yes firebase-tools@13 firestore:databases:get "(default)" --project PROJECT_ID
```

Confira se aparece `southamerica-east1`. Num projeto recém-criado, um erro de API desativada costuma
sumir depois de 1 minuto. Se continuar, peça para a pessoa abrir o link que vem no erro e clicar em
**Ativar**. Outra saída é criar pelo console:

1. **Firestore Database** → **Criar banco de dados**;
2. edição **Standard** (se o console perguntar);
3. local `southamerica-east1 (São Paulo)`;
4. **Iniciar no modo de produção**.

A região não pode ser trocada depois.

### Passo 4: publicar regras e índices

No diretório `PriceRadar/dedonervoso/firebase`:

```bash
npx --yes firebase-tools@13 deploy --only firestore:rules,firestore:indexes --project PROJECT_ID
```

A saída deve terminar com `Deploy complete!`.

### Passo 5: registrar os apps

Veja o que já existe e crie **só o que faltar**:

```bash
npx --yes firebase-tools@13 apps:list --project PROJECT_ID
npx --yes firebase-tools@13 apps:create WEB "Dedo Nervoso Web" --project PROJECT_ID
npx --yes firebase-tools@13 apps:create ANDROID "Dedo Nervoso" --package-name com.dedonervoso.app --project PROJECT_ID
```

Depois cadastre as duas impressões digitais do certificado. Use o App ID do app Android (formato
`1:123456789:android:abc123`) e pule a que já aparecer na lista:

```bash
npx --yes firebase-tools@13 apps:android:sha:list ANDROID_APP_ID --project PROJECT_ID
npx --yes firebase-tools@13 apps:android:sha:create ANDROID_APP_ID 788dd8656883c9a405dc3f243b8e76fa148fc372 --project PROJECT_ID
npx --yes firebase-tools@13 apps:android:sha:create ANDROID_APP_ID 5b272550be2ad9e89dc1c5efb0cc271842bcbe5c87c8b244d4a9aa6af0c44de0 --project PROJECT_ID
```

### Passo 6: verificar

Espere a pessoa usuária confirmar o passo 2. Depois, no diretório `PriceRadar/dedonervoso/firebase`:

```bash
bash verify-online.sh PROJECT_ID
```

O script:

- faz um login anônimo de teste e renova a sessão;
- testa as regras do jeito que o jogo usa;
- grava e lê os rankings de amigos, global e da semana;
- apaga tudo o que criou.

As respostas ficam só na memória: nada é gravado em disco. A chave de API é lida pelo Firebase
CLI e não aparece na saída.

Resultado esperado: todas as linhas `OK` e `TUDO CERTO` no fim. Se aparecer `FALHOU`, siga a dica da
linha (quase sempre é o passo 2 que ainda não foi feito) e rode de novo. **Nunca mude as regras
para passar no teste.**

### Passo 7 (opcional): Realtime Database para o duelo ao vivo

Peça para a pessoa usuária criar pelo console:

1. **Realtime Database** → **Criar banco de dados**;
2. **Estados Unidos (us-central1)**;
3. **Iniciar no modo bloqueado**.

As regras desse banco virão numa próxima versão do jogo; até lá, bloqueado é o seguro. Anote a URL
do banco (ex.: `https://PROJECT_ID-default-rtdb.firebaseio.com`).

### Passo 8: entregar (sem mostrar a chave)

Diga à pessoa usuária:

1. Copiar a **Chave de API da Web** em: Console do Firebase → ⚙ **Configurações do projeto** →
   **Geral**. A chave começa com `AIza`.
2. No Claude, abrir o menu do ambiente na barra de título da sessão → **Edit** e salvar como
   variáveis de ambiente:
   - `DEDO_FIREBASE_PROJECT_ID` = o ID do projeto;
   - `DEDO_FIREBASE_API_KEY` = a chave.

   Depois, abrir uma **sessão nova** do Claude, porque as variáveis só valem em sessões novas. A
   chave vai dentro do app, então não é uma senha, mas mesmo assim não deve ir para o chat nem para
   o repositório.
3. Entregar ao Claude o `FIREBASE-SETUP-REPORT.md`.

### Relatório final

Crie `FIREBASE-SETUP-REPORT.md` na pasta onde você começou, fora do clone `PriceRadar/`, e preencha
exatamente este modelo. **A chave de API e tokens não entram no relatório.**

````markdown
# Relatório: Firebase do Dedo Nervoso

- Data/hora:
- Commit do repositório (git rev-parse HEAD):
- Versão do Firebase CLI:
- Projeto: ID `...` · nome `...` · número `...`
- Projeto novo ou existente:
- Plano Spark, sem faturamento: sim/não

## Configuração
- Authentication · Anônimo ativado: sim/não
- Firestore (default): região `...` · modo produção: sim/não · proteção contra exclusão: sim/não · criado pelo CLI ou pelo console
- Regras e índices publicados: sim/não · última linha do deploy:
- App Web: App ID `...`
- App Android: App ID `...` · pacote `com.dedonervoso.app` · SHA-1 cadastrado: sim/não · SHA-256 cadastrado: sim/não
- Realtime Database (opcional): criado sim/não · região · modo · URL

## Verificação (saída completa de `bash verify-online.sh PROJECT_ID`)
```text
cole aqui
```

## Chave de API
- A pessoa usuária sabe onde copiar a chave e onde salvá-la no Claude: sim/não
- (A chave NÃO aparece neste relatório.)

## Problemas, avisos e passos feitos pela pessoa usuária

## Comandos executados (em ordem)
````

---

## Plano B: sem o Codex, pelo console (uns 10 minutos)

1. <https://console.firebase.google.com> → **Adicionar projeto** → `Dedo Nervoso` → Google Analytics
   desativado → **Criar**.
2. **Authentication** → **Vamos começar** → **Anônimo** → **Ativar** → **Salvar**.
3. **Firestore Database** → **Criar banco de dados** → edição Standard → `southamerica-east1 (São
   Paulo)` → **modo de produção**.
4. Ainda no Firestore, aba **Regras**: apague o conteúdo e cole o arquivo
   [`firebase/firestore.rules`](../firebase/firestore.rules) inteiro → **Publicar**.
5. ⚙ **Configurações do projeto** → **Geral** → **Seus apps** → ícone Web (`</>`) → apelido
   `Dedo Nervoso Web` → **Registrar app**. Não precisa configurar o Hosting.
6. Siga o passo 8 acima (a chave aparece nessa mesma página).
7. Opcional: para conferir tudo, você precisa de três coisas:
   - Node.js instalado;
   - o login do item 2 da caixa do início;
   - o repositório clonado.

   Com isso, rode `bash dedonervoso/firebase/verify-online.sh PROJECT_ID`.
