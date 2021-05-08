#! /bin/bash
openssl req -new -sha256 -key domain.key -subj "/" -reqexts SAN -config <(cat /etc/pki/tls/openssl.cnf <(printf "\n[SAN]\nsubjectAltName=DNS:caifuxiang.com,DNS:www.caifuxiang.com"))