# PapaZone RAG Integration (Local Development)

This change adds menu-item Retrieval-Augmented Generation (RAG) to the existing `ai-service`.
It keeps MySQL as the transactional database and adds PostgreSQL + pgvector as a separate vector store.
The existing Gemini chat client and its recommendation tool-calling flow are retained.

## What was added

- Spring AI 1.1.1 dependency management in `backend/pom.xml`.
- Spring AI Google GenAI embedding starter and PGvector store dependency in `backend/ai-service/pom.xml`.
- Separate `rag.datasource` PostgreSQL connection and `PgVectorStore` bean. The AI service's existing MySQL-backed business services are not redirected to PostgreSQL.
- `RagMenuService` to index Menu Service items and perform similarity search.
- `POST /api/ai/rag/index-menu` admin-only endpoint (API Gateway strips `/api`, so AI service receives `/ai/rag/index-menu`).
- `GET /api/ai/rag/search?q=...` semantic-search diagnostic endpoint.
- Chat retrieves semantically relevant menu documents and adds them as supporting context. Current live menu data remains authoritative for price and availability.
- `vector-db` service and persistent `vector_data` volume in Docker Compose.

## Requirements

- Docker Desktop / Docker Engine with Compose.
- Java 25 and Maven if building services outside Docker.
- A valid Gemini API key with access to the `gemini-embedding-001` embedding model and your configured Gemini chat model.

## Configure secrets

Copy `.env.example` to `.env` if you do not already have a local `.env`, then fill in your own values. Never commit `.env`.

Set at least:

```dotenv
GEMINI_API_KEY=your_google_ai_studio_key
MYSQL_ROOT_PASSWORD=your_mysql_root_password
MYSQL_DATABASE_PASSWORD=your_mysql_app_password
JWT_SECRET=your_existing_base64_jwt_secret
INTERNAL_API_KEY=your_existing_internal_service_key
RAG_DB_PASSWORD=choose_a_strong_local_password
RAG_ENABLED=true
RAG_EMBEDDING_MODEL=gemini-embedding-001
RAG_EMBEDDING_DIMENSIONS=768
```

Keep `JWT_SECRET` and `INTERNAL_API_KEY` consistent with the values already used by your other local services.

## Start locally

From the project root:

```bash
docker compose up -d --build vector-db ai-service
```

If the other services are not already running, start the full local stack:

```bash
docker compose up -d --build
```

Check the vector database and AI service:

```bash
docker compose ps vector-db ai-service
docker compose logs -f vector-db ai-service
```

The vector database is exposed on `localhost:5433`; inside Compose the AI service connects to `vector-db:5432`.

## Index menu data

Sign in as an admin in the PapaZone frontend and obtain the normal admin JWT from the app. Then run:

```bash
curl -X POST http://localhost:8080/api/ai/rag/index-menu \
  -H "Authorization: Bearer YOUR_ADMIN_JWT"
```

Expected response:

```json
{"status":"indexed","documents":6}
```

The number depends on how many records Menu Service returns. Re-running the endpoint refreshes vectors for menu IDs currently returned by Menu Service.

## Test semantic search

```bash
curl --get "http://localhost:8080/api/ai/rag/search" \
  --data-urlencode "q=something spicy and cheesy"
```

The endpoint returns the closest menu documents and their metadata. The first request may take longer while the embedding model is called.

## Test the existing chat

Use the existing frontend food assistant or existing `POST /api/ai/chat` flow. Ask a natural-language question such as "I want something spicy and cheesy". The AI service retrieves relevant menu documents and adds them to the existing Gemini prompt. The existing tool calling still constrains recommendations to live available menu items.

## Important operational notes

- RAG indexing requires a valid Gemini API key. Chat can still use the existing menu context if retrieval fails, but indexing/search needs the embedding provider and vector database.
- The embedding dimension is configured as 768. It must match the selected embedding model output. If you change embedding model or dimensions later, use a fresh vector table/volume or migrate/re-index the vector data; do not mix dimensions in one table.
- The `vector_data` Docker volume persists independently from MySQL's `mysqldata` volume.
- The search diagnostic endpoint currently accepts requests without a JWT; put it behind authentication/rate limiting before exposing it publicly. The indexing endpoint is admin-only.
