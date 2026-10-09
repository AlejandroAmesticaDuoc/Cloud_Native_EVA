FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm install
COPY . .
RUN NODE_OPTIONS=--max-old-space-size=1024 npm run build -- --configuration=production

FROM nginx:alpine
RUN rm -rf /usr/share/nginx/html/*
RUN mkdir -p /etc/nginx/certs
COPY --from=build /app/dist/angular-front360/browser/ /usr/share/nginx/html/
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY nginx.crt /etc/nginx/certs/nginx.crt
COPY nginx.key /etc/nginx/certs/nginx.key

EXPOSE 80 443
CMD ["nginx", "-g", "daemon off;"]
