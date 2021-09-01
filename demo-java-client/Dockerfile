FROM docker-local.jfrog.io/cibuildslave/apt:lts


# COPY --from=build /project/build/*-runner /work/application
ARG TARGET=.
COPY ${TARGET}/build/*-runner ./application
# COPY ./application ./application
# RUN chmod 775 ./application
# set up permissions for user `1001`
# RUN chmod 775 /work /work/application \
#   && chown -R 1001 /work \
#   && chmod -R "g+rwX" /work \
#   && chown -R 1001:root /work
#
# EXPOSE 8080
# USER 1001

CMD ["./application"]
#, "-Dquarkus.http.host=0.0.0.0"]

