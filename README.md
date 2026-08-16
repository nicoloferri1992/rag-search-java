# rag-search

RAG pipeline with hybrid search (dense vector + sparse full-text, fused via Reciprocal
Rank Fusion) on Java 21 / Spring Boot / PostgreSQL + pgvector. All dependencies come
from Maven Central — no internal/ISP repositories required.

## Architecture

```
domain/            business model + ports (ChunkRepository, EmbeddingClient, CompletionClient)
application/        ChunkingService, IngestionService, RagOrchestrator
adapter/persistence  JdbcChunkRepository — pgvector + tsvector, RRF fused in one SQL query
adapter/embedding    OpenAiEmbeddingClient — OpenAI-compatible /v1/embeddings
adapter/llm          OpenAiCompletionClient — OpenAI-compatible /v1/chat/completions
adapter/web          IngestController, QueryController
```

## Prerequisites

- Java 21
- Docker (for local PostgreSQL + pgvector)
- [Ollama](https://ollama.com/download) running locally — the project defaults to it,
  so no API key or spending is required. Any other OpenAI-compatible provider
  (OpenAI, Groq, a self-hosted vLLM, ...) also works by overriding `OPENAI_BASE_URL`
  and `OPENAI_API_KEY`.

## Run locally

```bash
# 1. Pull the local models used by default (one-off)
ollama pull llama3.2
ollama pull nomic-embed-text

# 2. Start PostgreSQL + pgvector
docker compose up -d

# 3. Run the app — no env vars needed, Ollama is the default provider
mvn spring-boot:run
```

To use a paid provider instead (e.g. OpenAI), override the defaults:

```bash
export OPENAI_BASE_URL=https://api.openai.com/v1
export OPENAI_API_KEY=sk-...
export OPENAI_EMBEDDING_MODEL=text-embedding-3-small
export OPENAI_CHAT_MODEL=gpt-4o-mini
```

⚠️ If you switch embedding model, update `VECTOR(768)` in
`V1__init_document_chunks.sql` to match its output dimension (e.g. `1536` for
`text-embedding-3-small`) **before** the first run — pgvector requires a fixed,
matching dimension.

Flyway creates the `document_chunks` table and the `vector`/GIN indexes automatically
on startup (`src/main/resources/db/migration/V1__init_document_chunks.sql`).

## API

Ingest a document (it is chunked, embedded, and indexed):

```bash
curl -X POST http://localhost:8080/api/v1/documents \
  -H "Content-Type: application/json" \
  -d '{"documentId": "doc-1", "text": "..."}'
```

Ask a question (hybrid retrieval + grounded answer):

```bash
curl -X POST http://localhost:8080/api/v1/query \
  -H "Content-Type: application/json" \
  -d '{"question": "..."}'
```

## Configuration

All settings are environment-variable driven (see `application.yml`):

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | local docker-compose values | PostgreSQL connection |
| `OPENAI_API_KEY` | `ollama` (dummy — Ollama ignores it) | API key |
| `OPENAI_BASE_URL` | `http://localhost:11434/v1` (local Ollama) | Provider base URL |
| `OPENAI_EMBEDDING_MODEL` | `nomic-embed-text` (768 dims) | Embedding model |
| `OPENAI_CHAT_MODEL` | `llama3.2` | Chat/completion model |
| `CHUNK_SIZE_WORDS` / `CHUNK_OVERLAP_WORDS` | `250` / `40` | Chunking |
| `RETRIEVAL_TOP_K` / `RETRIEVAL_RRF_K` | `5` / `60` | Hybrid retrieval tuning |

If a different embedding model/dimension is used, update `VECTOR(768)` in
`V1__init_document_chunks.sql` accordingly before the first run.
