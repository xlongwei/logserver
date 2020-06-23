hostPort=localhost:9880
clients=2
seconds=3
serviceUrl=http://$hostPort

echo "$serviceUrl/log?type=https" > /tmp/urls.txt
http_load -parallel $clients -seconds $seconds /tmp/urls.txt
