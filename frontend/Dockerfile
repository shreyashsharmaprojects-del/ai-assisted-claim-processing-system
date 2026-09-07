# Production frontend image: builds the Angular SPA, then serves the static bundle
# through nginx (SPA fallback + /api proxy to the backend). The runtime config
# (/usr/share/nginx/html/assets/config.json) is mounted per environment so the same
# image runs everywhere — see docker-compose.prod.yml and docs/operations.md.
FROM node:22-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist/frontend/browser /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
