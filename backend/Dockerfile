# Stage 1: Build frontend
FROM node:20-slim AS frontend-builder
WORKDIR /frontend
COPY frontend/package.json frontend/vite.config.js frontend/index.html ./
COPY frontend/src ./src
COPY frontend/public ./public
RUN npm install && npm run build

# Stage 2: Python backend
FROM python:3.12-slim
WORKDIR /code

COPY backend/pyproject.toml .
RUN pip install --no-cache-dir hatchling && pip install --no-cache-dir .

COPY backend/ ./backend/
COPY --from=frontend-builder /frontend/dist ./backend/app/static/

CMD ["uvicorn", "backend.app.main:app", "--host", "0.0.0.0", "--port", "2200"]
