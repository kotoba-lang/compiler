FROM node:24.12.0-bookworm-slim@sha256:7326fb2dbdce998edd72140946851be64ef4a643e8715e138ca467e8e9d92c99
WORKDIR /app
COPY --chown=1000:1000 browser-host.mjs wasi-host.mjs wasi-service.mjs program.wasm ./
USER 1000:1000
ENV KOTOBA_WASM_PATH=/app/program.wasm PORT=8080
EXPOSE 8080
CMD ["node", "wasi-service.mjs"]
