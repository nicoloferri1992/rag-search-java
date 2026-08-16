package com.ragsearch.adapter.persistence;

import com.pgvector.PGvector;
import com.ragsearch.config.RagProperties;
import com.ragsearch.domain.model.DocumentChunk;
import com.ragsearch.domain.model.RetrievedChunk;
import com.ragsearch.domain.port.ChunkRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.util.List;
import java.util.UUID;

/**
 * Persistence adapter for {@link ChunkRepository} backed by PostgreSQL + pgvector.
 * Hybrid search fuses dense (vector cosine distance) and sparse (tsvector full-text)
 * rankings with Reciprocal Rank Fusion, computed entirely in a single SQL query.
 */
@Repository
class JdbcChunkRepository implements ChunkRepository {

    private static final String INSERT_SQL = """
            INSERT INTO document_chunks (id, document_id, chunk_index, content, embedding, content_tsv)
            VALUES (?, ?, ?, ?, ?, to_tsvector('english', ?))""";

    private static final String HYBRID_SEARCH_SQL = """
            WITH dense AS (
                SELECT id, RANK() OVER (ORDER BY embedding <=> CAST(:queryEmbedding AS vector)) AS rnk
                FROM document_chunks
                ORDER BY embedding <=> CAST(:queryEmbedding AS vector)
                LIMIT :candidatePoolSize
            ),
            sparse AS (
                SELECT id, RANK() OVER (ORDER BY ts_rank(content_tsv, plainto_tsquery('english', :queryText)) DESC) AS rnk
                FROM document_chunks
                WHERE content_tsv @@ plainto_tsquery('english', :queryText)
                LIMIT :candidatePoolSize
            ),
            fused AS (
                SELECT COALESCE(d.id, s.id) AS id,
                       COALESCE(1.0 / (:rrfK + d.rnk), 0.0) + COALESCE(1.0 / (:rrfK + s.rnk), 0.0) AS rrf_score
                FROM dense d
                FULL OUTER JOIN sparse s ON d.id = s.id
            )
            SELECT c.id, c.document_id, c.content, f.rrf_score
            FROM fused f
            JOIN document_chunks c ON c.id = f.id
            ORDER BY f.rrf_score DESC
            LIMIT :topK""";

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;
    private final RagProperties properties;

    JdbcChunkRepository(
            JdbcTemplate jdbcTemplate, NamedParameterJdbcTemplate namedJdbcTemplate, RagProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbcTemplate = namedJdbcTemplate;
        this.properties = properties;
    }

    @Override
    public void saveAll(List<DocumentChunk> chunks) {
        jdbcTemplate.batchUpdate(INSERT_SQL, chunks, chunks.size(), (ps, chunk) -> {
            ps.setObject(1, chunk.id());
            ps.setString(2, chunk.documentId());
            ps.setInt(3, chunk.chunkIndex());
            ps.setString(4, chunk.content());
            ps.setObject(5, new PGvector(chunk.embedding()), Types.OTHER);
            ps.setString(6, chunk.content());
        });
    }

    @Override
    public List<RetrievedChunk> hybridSearch(String queryText, float[] queryEmbedding, int topK) {
        int candidatePoolSize = Math.max(topK * 5, 50);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("queryEmbedding", new PGvector(queryEmbedding).toString())
                .addValue("queryText", queryText)
                .addValue("candidatePoolSize", candidatePoolSize)
                .addValue("rrfK", properties.retrieval().rrfK())
                .addValue("topK", topK);

        return namedJdbcTemplate.query(HYBRID_SEARCH_SQL, params, (rs, rowNum) -> new RetrievedChunk(
                (UUID) rs.getObject("id"),
                rs.getString("document_id"),
                rs.getString("content"),
                rs.getDouble("rrf_score")));
    }
}
