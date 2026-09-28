#!/usr/bin/env bash
# Dedo Nervoso — checks a Firebase project the way the game uses it: anonymous sign-in, the
# published Firestore rules and the leaderboard query. The test player it creates (and its
# sign-in account) is deleted at the end. The Web API key is read with the Firebase CLI and is
# never printed.
#
#   bash verify-online.sh <projectId>        (after `npx --yes firebase-tools@13 login`)
#
# With FIREBASE_AUTH_EMULATOR_HOST and FIRESTORE_EMULATOR_HOST set, it checks the emulators.
set -uo pipefail

PROJECT="${1:?uso: bash verify-online.sh <projectId>}"
FIREBASE="${FIREBASE:-npx --yes firebase-tools@13}"
command -v node >/dev/null || { echo "FALHOU  precisa do Node.js 18+"; exit 1; }
command -v curl >/dev/null || { echo "FALHOU  precisa do curl"; exit 1; }

TMP="$(mktemp -d)"
FAILED=0
KEY="" TOKEN="" USER_ID="" CODE="" CREATED=0

# Prints one field ("a.b.0.c") of the JSON read from stdin; empty when missing or not JSON.
field() {
  node -e 'let s = ""; process.stdin.on("data", d => s += d).on("end", () => {
    let v; try { v = JSON.parse(s) } catch (e) {}
    for (const k of process.argv[1].split(".")) v = v == null ? undefined : v[k];
    process.stdout.write(v == null ? "" : String(v)) })' "$1"
}

# req METHOD URL [BODY] [ID_TOKEN]: the response body goes to $TMP/body, the HTTP status to stdout.
req() {
  rm -f "$TMP/body"
  local args=(-s --max-time 30 -o "$TMP/body" -w '%{http_code}' -X "$1" "$2")
  [ -n "${3:-}" ] && args+=(-H 'Content-Type: application/json' --data "$3")
  [ -n "${4:-}" ] && args+=(-H "Authorization: Bearer $4")
  curl "${args[@]}" || true
}

check() {  # check DESCRIPTION EXPECTED_STATUS STATUS
  if [ "$3" = "$2" ]; then
    printf 'OK      %s (HTTP %s)\n' "$1" "$3"
  else
    printf 'FALHOU  %s: esperado HTTP %s, veio %s %s\n' "$1" "$2" "$3" "$(field error.message < "$TMP/body" 2>/dev/null | tr '\n' ' ')"
    FAILED=1
  fi
}

# Whatever happens, leave nothing behind.
cleanup() {
  if [ "$CREATED" = 1 ]; then
    req POST "$DOCS:commit" "{\"writes\":[{\"delete\":\"$NAME/players/$USER_ID\"},{\"delete\":\"$NAME/codes/$CODE\"}]}" "$TOKEN" >/dev/null
  fi
  if [ -n "$TOKEN" ]; then
    req POST "$AUTH/accounts:delete?key=$KEY" "{\"idToken\":\"$TOKEN\"}" >/dev/null
  fi
  rm -rf "$TMP"
}
trap cleanup EXIT

if [ -n "${FIREBASE_AUTH_EMULATOR_HOST:-}" ] && [ -n "${FIRESTORE_EMULATOR_HOST:-}" ]; then
  AUTH="http://$FIREBASE_AUTH_EMULATOR_HOST/identitytoolkit.googleapis.com/v1"
  FS="http://$FIRESTORE_EMULATOR_HOST/v1"
  KEY="emulator"
  echo "Emuladores do Firebase · projeto $PROJECT"
else
  AUTH="https://identitytoolkit.googleapis.com/v1"
  FS="https://firestore.googleapis.com/v1"
  echo "Projeto $PROJECT"
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
DOCS="$FS/projects/$PROJECT/databases/(default)/documents"
NAME="projects/$PROJECT/databases/(default)/documents"

# 1. Anonymous sign-in, as the game does the first time online play is switched on.
status="$(req POST "$AUTH/accounts:signUp?key=$KEY" '{"returnSecureToken":true}')"
if [ "$status" != 200 ]; then
  message="$(field error.message < "$TMP/body" 2>/dev/null)"
  echo "FALHOU  login anônimo: HTTP $status ${message:-(sem resposta)}"
  case "$message" in
    CONFIGURATION_NOT_FOUND*) echo "        → Authentication não foi iniciado: console → Authentication → Vamos começar → Anônimo → Ativar." ;;
    ADMIN_ONLY_OPERATION*|OPERATION_NOT_ALLOWED*) echo "        → O login Anônimo está desativado: console → Authentication → Método de login → Anônimo → Ativar." ;;
  esac
  exit 1
fi
TOKEN="$(field idToken < "$TMP/body")"
USER_ID="$(field localId < "$TMP/body")"
echo "OK      login anônimo (conta de teste criada)"

# 2. The rules: nothing is readable without signing in, signed-in players can read (the profile
#    doesn't exist yet, hence 404), and nobody writes someone else's profile.
check "leitura sem login é negada" 403 "$(req GET "$DOCS/players/$USER_ID")"
check "leitura com login é permitida" 404 "$(req GET "$DOCS/players/$USER_ID" "" "$TOKEN")"
status="$(req PATCH "$DOCS/players/outra-pessoa" '{"fields":{"nick":{"stringValue":"XX"}}}' "$TOKEN")"
check "escrever o perfil de outra pessoa é negado" 403 "$status"
[ "$status" = 200 ] && req DELETE "$DOCS/players/outra-pessoa" "" "$TOKEN" >/dev/null

# 3. A new player, created like the game does: friend code and profile in one commit.
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
status="$(req POST "$DOCS:commit" "$body" "$TOKEN")"
[ "$status" = 200 ] && CREATED=1
check "criar jogador de teste (código de amigo + perfil)" 200 "$status"

# 4. The global leaderboard query (highest scores first).
check "ranking global (consulta ordenada)" 200 "$(req POST "$DOCS:runQuery" \
  '{"structuredQuery":{"from":[{"collectionId":"players"}],"orderBy":[{"field":{"fieldPath":"bestScore"},"direction":"DESCENDING"}],"limit":50}}' "$TOKEN")"

# 5. Invalid data is refused even on one's own profile (a one-letter nickname).
read -r -d '' body <<EOF
{"writes": [
  {"update": {"name": "$NAME/players/$USER_ID", "fields": {"nick": {"stringValue": "X"}}},
   "updateMask": {"fieldPaths": ["nick"]},
   "updateTransforms": [{"fieldPath": "updatedAt", "setToServerValue": "REQUEST_TIME"}],
   "currentDocument": {"exists": true}}
]}
EOF
check "dados inválidos são recusados" 403 "$(req POST "$DOCS:commit" "$body" "$TOKEN")"

# 6. Clean up: the test player, its friend code and its sign-in account.
if [ "$CREATED" = 1 ]; then
  status="$(req POST "$DOCS:commit" "{\"writes\":[{\"delete\":\"$NAME/players/$USER_ID\"},{\"delete\":\"$NAME/codes/$CODE\"}]}" "$TOKEN")"
  [ "$status" = 200 ] && CREATED=0
  check "apagar jogador de teste" 200 "$status"
fi
status="$(req POST "$AUTH/accounts:delete?key=$KEY" "{\"idToken\":\"$TOKEN\"}")"
[ "$status" = 200 ] && TOKEN=""
check "apagar conta de teste" 200 "$status"

if [ "$FAILED" = 0 ]; then
  echo "TUDO CERTO: o projeto $PROJECT está pronto para o online do Dedo Nervoso."
else
  echo "HÁ FALHAS: veja as linhas FALHOU acima."
fi
exit "$FAILED"
