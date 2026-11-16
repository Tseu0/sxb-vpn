FROM node:22-alpine

WORKDIR /app

COPY server.mjs ./
COPY public ./public

RUN mkdir -p /data

ENV PORT=3847
ENV SXB_DB_PATH=/data/sxb.db

EXPOSE 3847

CMD ["node", "server.mjs"]
