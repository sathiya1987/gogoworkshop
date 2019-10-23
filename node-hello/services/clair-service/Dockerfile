FROM quay.io/coreos/clair:v2.0.8
COPY config.yml /config/config.yml

ENTRYPOINT []
CMD sed -i "s/localhost/$DB_HOST/" /config/config.yml && exec /clair -config=/config/config.yml
