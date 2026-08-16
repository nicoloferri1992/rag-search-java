# rag-search

RAG pipeline with hybrid search (dense vector + sparse full-text, fused via Reciprocal
Rank Fusion) on Java 21 / Spring Boot / PostgreSQL + pgvector. All dependencies come
from Maven Central — no internal/ISP repositories required.

## Architecture

The service follows a **hexagonal architecture** (ports & adapters): the domain layer
defines *what* the system needs (persistence, embeddings, text generation) through
plain interfaces, and never depends on Spring, JDBC, or any external HTTP client.
Adapters implement those interfaces and are the only place that knows about
PostgreSQL, pgvector, or the OpenAI wire format.

```
domain/
  model/            DocumentChunk, RetrievedChunk, RagAnswer — immutable records
  port/              ChunkRepository, EmbeddingClient, CompletionClient (interfaces)

application/
  ChunkingService     splits raw text into overlapping word chunks (pure logic)
  IngestionService     orchestrates ingest: chunk -> embed -> persist
  RagOrchestrator      orchestrates query: embed -> hybrid search -> prompt -> answer

adapter/
  persistence/JdbcChunkRepository   implements ChunkRepository — pgvector + tsvector,
                                     dense/sparse fusion (RRF) in one SQL query
  embedding/OpenAiEmbeddingClient   implements EmbeddingClient — calls /v1/embeddings
  llm/OpenAiCompletionClient        implements CompletionClient — calls /v1/chat/completions
  web/IngestController,
      QueryController               inbound REST adapters (DTOs, validation, mapping)

config/              Spring wiring: RagProperties (typed, validated config), RestClientConfig
```

Only the `adapter` and `config` packages import framework or infrastructure code
(Spring, JDBC, `RestClient`). `domain` and `application` are plain Java and could be
unit-tested or reused without Spring Boot at all. Dependencies are wired through
constructor injection everywhere — no field injection, no service locators.

### How ingestion works

```
POST /api/v1/documents  { documentId, text }
        │
        ▼
IngestController          validates the request, maps it to a plain (documentId, text) pair
        │
        ▼
IngestionService.ingest()
        │
        ├─► ChunkingService.chunk()        splits text into overlapping word windows
        │                                   (default: 250 words, 40 overlap)
        │
        ├─► EmbeddingClient.embedAll()     one dense vector per chunk (Ollama/OpenAI)
        │
        └─► ChunkRepository.saveAll()      batch INSERT: each row stores the chunk text,
                                            its VECTOR embedding, and a generated TSVECTOR
                                            for full-text search — both indexed
```

### How a query works

```
POST /api/v1/query  { question }
        │
        ▼
QueryController            validates the request
        │
        ▼
RagOrchestrator.answer()
        │
        ├─► EmbeddingClient.embed(question)     dense embedding of the question
        │
        ├─► ChunkRepository.hybridSearch()      ONE SQL query that:
        │                                        1) ranks chunks by cosine distance
        │                                           on the embedding (dense/semantic)
        │                                        2) ranks chunks by ts_rank on the
        │                                           tsvector (sparse/keyword)
        │                                        3) fuses both rankings with
        │                                           Reciprocal Rank Fusion (RRF):
        │                                           score = Σ 1 / (k + rank_i)
        │                                        4) returns the top-K fused results
        │
        ├─► builds a prompt: system instructions ("answer only from context")
        │   + the retrieved chunks + the question
        │
        └─► CompletionClient.complete()          sends the prompt to the chat model,
                                                   returns the grounded answer + sources
```

Hybrid search matters because dense (vector) search alone can miss exact terms —
codes, names, acronyms — while keyword search alone misses paraphrases and synonyms.
RRF combines both rankings without needing to normalize or tune their raw scores,
which is what makes it a safe default over a hand-tuned weighted sum.

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
