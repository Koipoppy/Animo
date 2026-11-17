# Animo API image. Build from the repository root:
#   docker build -f Backend/Dockerfile -t animo-api .
FROM node:22-bookworm-slim AS deps
WORKDIR /app/Backend
COPY Backend/package.json Backend/package-lock.json ./
RUN npm ci --omit=dev

FROM node:22-bookworm-slim
ENV NODE_ENV=production \
    HOST=0.0.0.0 \
    PORT=8787 \
    DATA_DIR=/app/Backend/data
WORKDIR /app/Backend
COPY --from=deps /app/Backend/node_modules ./node_modules
COPY Backend/package.json ./
COPY Backend/src ./src
# rig.mjs resolves ../../tools/blender from Backend/src. Blender is not bundled,
# so humanoid rigging goes through Lux3D and this script only runs if BLENDER_PATH is set.
COPY tools/blender /app/tools/blender
RUN mkdir -p /app/Backend/data \
    && chown -R node:node /app
USER node
EXPOSE 8787
VOLUME ["/app/Backend/data"]
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:' + (process.env.PORT || 8787) + '/health').then(r => process.exit(r.ok ? 0 : 1)).catch(() => process.exit(1))"
CMD ["node", "src/server.mjs"]
