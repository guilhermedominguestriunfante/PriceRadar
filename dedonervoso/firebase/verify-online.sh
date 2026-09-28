#!/usr/bin/env bash
# Dedo Nervoso — checks a Firebase project the way the game uses it: anonymous sign-in, token
# refresh, the published Firestore rules, the friends/global/weekly boards. The test player it
# creates (and its sign-in account) is deleted at the end. Responses stay in memory (nothing is
# written to disk) and the Web API key is never printed.
#
#   bash verify-online.sh <projectId>
#
# The key comes from DEDO_FIREBASE_API_KEY when set; otherwise it is read with the Firebase CLI
# (after `npx --yes firebase-tools@13 login`). With FIREBASE_AUTH_EMULATOR_HOST and
# FIRESTORE_EMULATOR_HOST set, it checks the emulators instead.
set -uo pipefail

PROJECT="${1:?uso: bash verify-online.sh <projectId>}"
FIREBASE="${FIREBASE:-npx --yes firebase-tools@13}"
command -v node >/dev/null || { echo "FALHOU  precisa do Node.js 18+"; exit 1; }
command -v curl >/dev/null || { echo "FALHOU  precisa do curl"; exit 1; }

FAILED=0
KEY="" TOKEN="" REFRESH="" USER_ID="" CODE="" CREATED=0 STATUS="" BODY=""
WEEK="$(date -u +%G-W%V)"

# Prints one field ("a.b.0.c") of the JSON read from stdin; empty when missing or not JSON.
field() {
  node -e 'let s = ""; process.stdin.on("data", d => s += d).on("end", () => {
    let v; try { v = JSON.parse(s) } catch (e) {}
    for (const k of process.argv[1].split(".")) v = v == null ? undefined : v[k];
    process.stdout.write(v == null ? "" : String(v)) })' "$1"
}

# req METHOD URL [BODY] [ID_TOKEN] [CONTENT_TYPE]: sets STATUS and BODY (kept in memory).
req() {
  local args=(-s --max-time 30 -w '\n%{http_code}' -X "$1" "$2") out
  [ -n "${3:-}" ] && args+=(-H "Content-Type: ${5:-application/json}" --data "$3")
  [ -n "${4:-}" ] && args+=(-H "Authorization: Bearer $4")
  out="$(curl "${args[@]}" || true)"
  STATUS="${out##*$'\n'}"
  BODY="${out%$'\n'*}"
  [ "$BODY" = "$out" ] && BODY=""
}

check() {  # check DESCRIPTION EXPECTED_STATUS (against the last req)
  if [ "$STATUS" = "$2" ]; then
    printf 'OK      %s (HTTP %s)\n' "$1" "$STATUS"
  else
    printf 'FALHOU  %s: esperado HTTP %s, veio %s %s\n' "$1" "$2" "$STATUS" "$(printf '%s' "$BODY" | field error.message | tr '\n' ' ')"
    FAILED=1
  fi
}

# Deletes everything the test player owns: profile, friend code, weekly score.
delete_player() {
  req POST "$DOCS:commit" "{\"writes\":[{\"delete\":\"$NAME/players/$USER_ID\"},{\"delete\":\"$NAME/codes/$CODE\"},{\"delete\":\"$NAME/weeks/$WEEK/scores/$USER_ID\"}]}" "$TOKEN"
}

# Whatever happens, leave nothing behind.
cleanup() {
  [ "$CREATED" = 1 ] && delete_player
  [ -n "$TOKEN" ] && req POST "$AUTH/accounts:delete?key=$KEY" "{\"idToken\":\"$TOKEN\"}"
}
trap cleanup EXIT

if [ -n "${FIREBASE_AUTH_EMULATOR_HOST:-}" ] && [ -n "${FIRESTORE_EMULATOR_HOST:-}" ]; then
  AUTH="http://$FIREBASE_AUTH_EMULATOR_HOST/identitytoolkit.googleapis.com/v1"
  TOKENS="http://$FIREBASE_AUTH_EMULATOR_HOST/securetoken.googleapis.com/v1"
  FS="http://$FIRESTORE_EMULATOR_HOST/v1"
  KEY="emulator"
  echo "Emuladores do Firebase · projeto $PROJECT"
else
  AUTH="https://identitytoolkit.googleapis.com/v1"
  TOKENS="https://securetoken.googleapis.com/v1"
  FS="https://firestore.googleapis.com/v1"
  echo "Projeto $PROJECT"
  if [ -n "${DEDO_FIREBASE_API_KEY:-}" ]; then
    KEY="$DEDO_FIREBASE_API_KEY"
    echo "OK      chave de API lida de DEDO_FIREBASE_API_KEY (não é exibida)"
  else
    out="$($FIREBASE firestore:databases:get "(default)" --project "$PROJECT" --json 2>/dev/null)"
    location="$(printf '%s' "$out" | field result.locationId)"
    if [ -z "$location" ]; then
      echo "FALHOU  banco Firestore (default): $(printf '%s' "$out" | field error)"
      echo "        → Crie o banco (passo 3) e confira o login do Firebase CLI."
      exit 1
    fi
    echo "OK      banco Firestore (default) · região $location"
    out="$($FIREBASE apps:list WEB --project "$PROJECT" --json 2>/dev/null)"
    web="$(printf '%s' "$out" | field result.0.appId)"
    if [ -z "$web" ]; then
      echo "FALHOU  nenhum app Web registrado no projeto $(printf '%s' "$out" | field error)"
      exit 1
    fi
    KEY="$($FIREBASE apps:sdkconfig WEB "$web" --project "$PROJECT" --json 2>/dev/null | field result.sdkConfig.apiKey)"
    if [ -z "$KEY" ]; then
      echo "FALHOU  não foi possível ler a chave de API do app Web $web"
      exit 1
    fi
    echo "OK      app Web $web · chave de API lida pelo Firebase CLI (não é exibida)"
  fi
fi
DOCS="$FS/projects/$PROJECT/databases/(default)/documents"
NAME="projects/$PROJECT/databases/(default)/documents"

# 1. Anonymous sign-in, as the game does the first time online play is switched on.
req POST "$AUTH/accounts:signUp?key=$KEY" '{"returnSecureToken":true}'
if [ "$STATUS" != 200 ]; then
  message="$(printf '%s' "$BODY" | field error.message)"
  echo "FALHOU  login anônimo: HTTP $STATUS ${message:-(sem resposta)}"
  case "$message" in
    CONFIGURATION_NOT_FOUND*) echo "        → Authentication não foi iniciado: console → Authentication → Vamos começar → Anônimo → Ativar." ;;
    ADMIN_ONLY_OPERATION*|OPERATION_NOT_ALLOWED*) echo "        → O login Anônimo está desativado: console → Authentication → Método de login → Anônimo → Ativar." ;;
    API_KEY_INVALID*|*"API key not valid"*) echo "        → A chave de API não é deste projeto (ou foi digitada errada)." ;;
  esac
  exit 1
fi
TOKEN="$(printf '%s' "$BODY" | field idToken)"
REFRESH="$(printf '%s' "$BODY" | field refreshToken)"
USER_ID="$(printf '%s' "$BODY" | field localId)"
echo "OK      login anônimo (conta de teste criada)"

# 2. Session renewal, as the game does at every start.
req POST "$TOKENS/token?key=$KEY" "grant_type=refresh_token&refresh_token=$(node -p 'encodeURIComponent(process.argv[1])' "$REFRESH")" "" \
  application/x-www-form-urlencoded
check "renovação da sessão" 200
[ "$STATUS" = 200 ] && TOKEN="$(printf '%s' "$BODY" | field id_token)"

# 3. The rules: nothing is readable without signing in, signed-in players can read (the profile
#    doesn't exist yet, hence 404), and nobody writes someone else's profile.
req GET "$DOCS/players/$USER_ID"
check "leitura sem login é negada" 403
req GET "$DOCS/players/$USER_ID" "" "$TOKEN"
check "leitura com login é permitida" 404
req PATCH "$DOCS/players/outra-pessoa" '{"fields":{"nick":{"stringValue":"XX"}}}' "$TOKEN"
check "escrever o perfil de outra pessoa é negado" 403
[ "$STATUS" = 200 ] && req DELETE "$DOCS/players/outra-pessoa" "" "$TOKEN"

# 4. A new player, created like the game does: friend code and profile in one commit.
alphabet=ABCDEFGHJKLMNPQRSTUVWXYZ23456789
for _ in 1 2 3 4 5 6; do CODE+="${alphabet:RANDOM%32:1}"; done
read -r -d '' body <<EOF
{"writes": [
  {"update": {"name": "$NAME/codes/$CODE", "fields": {"uid": {"stringValue": "$USER_ID"}}},
   "currentDocument": {"exists": false}},
  {"update": {"name": "$NAME/players/$USER_ID", "fields": {
     "nick": {"stringValue": "TESTE_SETUP"}, "code": {"stringValue": "$CODE"}, "avatar": {"integerValue": "0"},
     "bestScore": {"integerValue": "0"}, "bestTaps": {"integerValue": "0"}, "bestTps10": {"integerValue": "0"},
     "bestStage": {"integerValue": "0"}, "level": {"integerValue": "1"}, "appVersion": {"integerValue": "2"},
     "friends": {"arrayValue": {}}}},
   "updateTransforms": [{"fieldPath": "updatedAt", "setToServerValue": "REQUEST_TIME"}],
   "currentDocument": {"exists": false}}
]}
EOF
req POST "$DOCS:commit" "$body" "$TOKEN"
[ "$STATUS" = 200 ] && CREATED=1
check "criar jogador de teste (código de amigo + perfil)" 200

# 5. The boards: friends (a batch read), global and weekly (ordered queries).
req POST "$DOCS:batchGet" "{\"documents\":[\"$NAME/players/$USER_ID\"]}" "$TOKEN"
check "ranking de amigos (leitura em lote)" 200
req POST "$DOCS:runQuery" '{"structuredQuery":{"from":[{"collectionId":"players"}],"orderBy":[{"field":{"fieldPath":"bestScore"},"direction":"DESCENDING"}],"limit":50}}' "$TOKEN"
check "ranking global (consulta ordenada)" 200
read -r -d '' body <<EOF
{"writes": [
  {"update": {"name": "$NAME/weeks/$WEEK/scores/$USER_ID", "fields": {
     "nick": {"stringValue": "TESTE_SETUP"}, "avatar": {"integerValue": "0"}, "score": {"integerValue": "0"},
     "stage": {"integerValue": "1"}, "taps": {"integerValue": "0"}, "tps10": {"integerValue": "0"},
     "appVersion": {"integerValue": "2"}}},
   "updateTransforms": [{"fieldPath": "updatedAt", "setToServerValue": "REQUEST_TIME"}]}
]}
EOF
req POST "$DOCS:commit" "$body" "$TOKEN"
check "placar da semana $WEEK (gravação)" 200
req POST "$DOCS/weeks/$WEEK:runQuery" '{"structuredQuery":{"from":[{"collectionId":"scores"}],"orderBy":[{"field":{"fieldPath":"score"},"direction":"DESCENDING"}],"limit":50}}' "$TOKEN"
check "ranking da semana (consulta ordenada)" 200

# 6. Invalid data is refused even on one's own profile (a one-letter nickname).
read -r -d '' body <<EOF
{"writes": [
  {"update": {"name": "$NAME/players/$USER_ID", "fields": {"nick": {"stringValue": "X"}}},
   "updateMask": {"fieldPaths": ["nick"]},
   "updateTransforms": [{"fieldPath": "updatedAt", "setToServerValue": "REQUEST_TIME"}],
   "currentDocument": {"exists": true}}
]}
EOF
req POST "$DOCS:commit" "$body" "$TOKEN"
check "dados inválidos são recusados" 403

# 7. Clean up: the test player (profile, friend code, weekly score) and its sign-in account.
if [ "$CREATED" = 1 ]; then
  delete_player
  [ "$STATUS" = 200 ] && CREATED=0
  check "apagar jogador de teste" 200
fi
req POST "$AUTH/accounts:delete?key=$KEY" "{\"idToken\":\"$TOKEN\"}"
[ "$STATUS" = 200 ] && TOKEN=""
check "apagar conta de teste" 200

if [ "$FAILED" = 0 ]; then
  echo "TUDO CERTO: o projeto $PROJECT está pronto para o online do Dedo Nervoso."
else
  echo "HÁ FALHAS: veja as linhas FALHOU acima."
fi
exit "$FAILED"
