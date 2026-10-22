FROM node:22-alpine

WORKDIR /app
COPY package.json ./
COPY hub ./hub
COPY tools ./tools

# The hub has no runtime dependencies; nothing to npm install.
USER node
EXPOSE 8097
CMD ["node", "hub/server.js"]
