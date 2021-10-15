FROM envoyproxy/envoy:v1.17.0

COPY envoy.yaml /etc/envoy/envoy.yaml

CMD /usr/local/bin/envoy -c /etc/envoy/envoy.yaml -l trace
 # --log-path /tmp/envoy_info.log

# docker build -t grpcweb/envoy  -f envoy.Dockerfile .