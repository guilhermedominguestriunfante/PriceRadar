# Publicar uma versão

Os jogadores recebem o aviso de "Nova versão" dentro do jogo lendo
`release/version.json` do branch **main** do repositório. Então publicar é:

1. Subir a versão em `app/build.gradle.kts` (`versionCode` +1 e `versionName`).
2. Escrever as novidades em `app/release-notes.json` (pt e en) e no `CHANGELOG.md`.
3. Gerar o release assinado (precisa de `DEDO_KEYSTORE_B64` e `DEDO_KEYSTORE_PASSWORD`):

   ```bash
   ./gradlew check publishRelease
   ```

   Isso cria `release/dedo-nervoso.apk` e `release/version.json` e recusa publicar um APK
   assinado com a chave de debug.
4. Fazer commit e **merge no main**. A partir daí:
   - quem abrir o jogo vê "NOVA VERSÃO x.y.z" com as novidades e o botão Baixar;
   - o link fixo de download passa a entregar a versão nova:
     <https://raw.githubusercontent.com/guilhermedominguestriunfante/PriceRadar/main/dedonervoso/release/dedo-nervoso.apk>

## Online exige a versão mais nova

Por padrão `minOnlineVersionCode` = a versão publicada: quem não atualizar continua jogando
offline, mas o online pede a atualização. Para uma versão que não muda nada no online, dá para
manter as anteriores jogando online com `./gradlew publishRelease -PminOnlineVersionCode=N`.

Se a versão mudar o formato dos dados online, suba também `minAppVersion()` em
`firebase/firestore.rules` e publique as regras no console: assim nem um app antigo modificado
consegue gravar.

## Assinatura

Todas as versões precisam da **mesma chave** (senão o Android recusa instalar por cima). Ela
fica fora do repositório, nas variáveis `DEDO_KEYSTORE_B64` / `DEDO_KEYSTORE_PASSWORD`
(ou em `keystore.properties`, ignorado pelo git). Guarde uma cópia em local seguro.
