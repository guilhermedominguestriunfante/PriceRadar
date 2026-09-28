# Configurar o Firebase (online do Dedo Nervoso)

O online usa o plano gratuito do Firebase (Spark): login anônimo + Cloud Firestore. Não precisa
de cartão. O jogo fala com o Firebase pela API REST, então o app não depende do Google Play
Services.

## 1. Criar o projeto

1. Em <https://console.firebase.google.com>, **Adicionar projeto** → nome `Dedo Nervoso`.
   O Google Analytics é opcional (pode desativar).
2. **Authentication** → *Vamos começar* → aba *Método de login* → **Anônimo** → Ativar → Salvar.
   (O login com Google entra numa próxima versão; veja o item 5.)
3. **Firestore Database** → *Criar banco de dados* → **modo de produção** →
   local **southamerica-east1 (São Paulo)**. O local não pode ser trocado depois.
4. Ainda no Firestore, aba **Regras**: apague o conteúdo e cole o arquivo
   [`firebase/firestore.rules`](../firebase/firestore.rules) inteiro → **Publicar**.
   Essas regras garantem que cada jogador só altera os próprios dados, que os valores são
   plausíveis, que um recorde nunca diminui e que só versões atuais escrevem.

## 2. Pegar as duas informações do app

Em ⚙ **Configurações do projeto** → **Geral**:

- **ID do projeto** (ex.: `dedo-nervoso-1a2b3`)
- **Chave de API da Web** (começa com `AIza…`)

A chave de API da Web do Firebase é um identificador público (vai dentro do app), mas mesmo
assim ela **não** deve ser colada no chat: guarde-a nas configurações do ambiente, abaixo.

## 3. Salvar no ambiente do Claude

No menu do ambiente (barra de título da sessão) → **Edit** → variáveis de ambiente:

| Variável | Valor |
|---|---|
| `DEDO_FIREBASE_PROJECT_ID` | o ID do projeto |
| `DEDO_FIREBASE_API_KEY` | a Chave de API da Web |
| `DEDO_KEYSTORE_B64` | a chave de assinatura (do arquivo `dedo-nervoso-assinatura.txt`) |
| `DEDO_KEYSTORE_PASSWORD` | a senha dessa chave (mesmo arquivo) |

Variáveis novas só valem em **sessões novas**. Com elas, cada build já sai com o online
ligado ao seu projeto e assinado com a mesma chave (as atualizações instalam por cima).

## 4. Testar

Instale o APK novo, abra **Ranking → Online → Ativar ranking online**. No console do
Firebase, em Firestore, devem aparecer as coleções `players` e `codes`.

## 5. Login com Google (próxima etapa)

Quando formos ligar o "Entrar com Google":

1. Em **Authentication → Método de login**, ative **Google** (informe o e-mail de suporte).
2. Em **Configurações do projeto → Seus apps → Adicionar app → Android**:
   - Pacote: `com.dedonervoso.app`
   - SHA-1 do certificado de release: `78:8D:D8:65:68:83:C9:A4:05:DC:3F:24:3B:8E:76:FA:14:8F:C3:72`
   - (SHA-256: `5B:27:25:50:BE:2A:D9:E8:9D:C1:C5:EF:B0:CC:27:18:42:BC:BE:5C:87:C8:B2:44:D4:A9:AA:6A:F0:C4:4D:E0`)
3. No ambiente do Claude, libere o domínio `dl.google.com` (é de onde vem a biblioteca do login).

## Limites do plano gratuito

50 mil leituras e 20 mil gravações por dia, 1 GiB armazenado. Uma partida grava no máximo 2
documentos; abrir um ranking lê até 50. Para um grupo de amigos, sobra muito.

## Para desenvolvedores: emuladores

Os testes de integração rodam contra os emuladores oficiais, com as mesmas regras:

```bash
cd firebase
npx firebase-tools emulators:start --project demo-dedonervoso
# em outro terminal, na raiz do projeto:
./gradlew check
```

Sem os emuladores rodando, esses testes são pulados automaticamente.
