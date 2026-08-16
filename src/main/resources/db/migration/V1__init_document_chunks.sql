-- Requires the pgvector extension (https://github.com/pgvector/pgvector).
CREATE EXTENSION IF NOT EXISTS vector;

-- Vector dimension (768) matches Ollama's nomic-embed-text.
-- Change this and re-create the table/index if a different embedding model is used
-- (e.g. VECTOR(1536) for OpenAI text-embedding-3-small).
CREATE TABLE document_chunks (
    id UUID PRIMARY KEY,
    document_id VARCHAR(255) NOT NULL,
    chunk_index INTEGER NOT NULL,
    content TEXT NOT NULL,
    embedding VECTOR(768) NOT NULL,
    content_tsv TSVECTOR NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_document_chunks_embedding
    ON document_chunks USING hnsw (embedding vector_cosine_ops);

CREATE INDEX idx_document_chunks_content_tsv
    ON document_chunks USING GIN (content_tsv);

CREATE INDEX idx_document_chunks_document_id
    ON document_chunks (document_id);
