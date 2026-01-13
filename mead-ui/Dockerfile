FROM node:20-alpine AS build

WORKDIR /app

COPY package.json package-lock.json* ./

RUN if [ -f package-lock.json ]; then npm ci; else npm install; fi

ARG VITE_CONDITIONS_API_BASE_URL
ARG VITE_GEOGRAPHY_API_BASE_URL

ENV VITE_CONDITIONS_API_BASE_URL=$VITE_CONDITIONS_API_BASE_URL \
    VITE_GEOGRAPHY_API_BASE_URL=$VITE_GEOGRAPHY_API_BASE_URL

COPY . .
RUN npm run build

FROM nginx:alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 10000
