#!/bin/bash
# Build script - run from project root
echo "📦 Building frontend..."
cd frontend && npm install && npm run build
echo "✅ Frontend built to frontend/dist"
echo ""
echo "🐳 To run with Docker:"
echo "  cd docker && docker compose up --build -d"
