-- Enable pgvector and UUID extensions
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Table: documents
CREATE TABLE IF NOT EXISTS documents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    file_size BIGINT NOT NULL,
    total_chunks INT NOT NULL DEFAULT 0,
    status VARCHAR(50) NOT NULL DEFAULT 'PROCESSED',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Table: document_chunks
CREATE TABLE IF NOT EXISTS document_chunks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    token_count INT NOT NULL DEFAULT 0,
    embedding vector(768) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- HNSW Vector Index for ultra-fast approximate cosine similarity search
CREATE INDEX IF NOT EXISTS idx_document_chunks_embedding_hnsw 
    ON document_chunks 
    USING hnsw (embedding vector_cosine_ops) 
    WITH (m = 16, ef_construction = 64);

-- Foreign key lookup index
CREATE INDEX IF NOT EXISTS idx_document_chunks_document_id 
    ON document_chunks (document_id);

-- Optional Full-Text Search GIN index for hybrid retrieval
CREATE INDEX IF NOT EXISTS idx_document_chunks_content_tsv 
    ON document_chunks 
    USING gin (to_tsvector('english', content));
