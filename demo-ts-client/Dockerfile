
FROM grpcweb/prereqs:1.3.0

RUN npm install -g typescript

WORKDIR /github/grpc-web/net/grpc/gateway/examples/echo

RUN protoc -I=. echo.proto \
--js_out=import_style=commonjs:./ts-example \
--grpc-web_out=import_style=commonjs+dts,mode=grpcwebtext:./ts-example

WORKDIR /github/grpc-web/net/grpc/gateway/examples/echo/ts-example

RUN npm install && \
  npm link grpc-web && \
  tsc && \
  npx webpack && \
  cp echotest.html /var/www/html && \
  cp dist/main.js /var/www/html/dist

WORKDIR /var/www/html

EXPOSE 8081
CMD ["python", "-m", "SimpleHTTPServer", "8081"]