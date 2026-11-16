#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p certs
umask 077

# Git Bash(MSYS)는 /CN=...처럼 슬래시로 시작하는 -subj 값을 Windows 경로로
# 변환합니다. 이 변환을 끄지 않으면 OpenSSL이 DN 형식을 해석하지 못합니다.
openssl_req() {
  MSYS_NO_PATHCONV=1 openssl req "$@"
}

openssl_req -x509 -newkey rsa:2048 -noenc \
  -keyout certs/ca.key -out certs/ca.crt -days 30 \
  -subj '/CN=Netty Book Local CA' \
  -addext 'basicConstraints=critical,CA:TRUE' \
  -addext 'keyUsage=critical,keyCertSign,cRLSign'

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out certs/server.key
openssl_req -new -key certs/server.key -out certs/server.csr -subj '/CN=localhost'
cat > certs/server.ext <<'EXT'
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:localhost
EXT
openssl x509 -req -in certs/server.csr \
  -CA certs/ca.crt -CAkey certs/ca.key -set_serial 1001 \
  -out certs/server.crt -days 7 -sha256 -extfile certs/server.ext

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out certs/client.key
openssl_req -new -key certs/client.key -out certs/client.csr -subj '/CN=book-client'
cat > certs/client.ext <<'EXT'
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature
extendedKeyUsage=clientAuth
EXT
openssl x509 -req -in certs/client.csr \
  -CA certs/ca.crt -CAkey certs/ca.key -set_serial 1002 \
  -out certs/client.crt -days 7 -sha256 -extfile certs/client.ext

openssl verify -CAfile certs/ca.crt certs/server.crt certs/client.crt
echo "인증서를 certs/ 에 만들었습니다. 개인키는 저장소에 커밋하지 마시기 바랍니다."
