# Architettura e funzionamento di rag-search

Questo documento descrive in dettaglio come è costruito e come funziona il progetto,
componente per componente. È pensato per chi deve capire, estendere o modificare il
codice, non solo per usarlo (per l'uso rapido vedi il [README](../README.md)).

## 1. Cos'è il progetto

`rag-search` è un servizio Java/Spring Boot che implementa una pipeline **RAG**
(Retrieval-Augmented Generation) con **ricerca ibrida**: prima di rispondere a una
domanda, il sistema recupera i frammenti di testo più pertinenti da un database
documentale, li inserisce nel prompt come contesto, e chiede a un modello linguistico
di rispondere basandosi solo su quel contesto.

"Ricerca ibrida" significa che il recupero dei frammenti combina due tecniche
complementari:

- **Ricerca densa (semantica/vettoriale)**: trova testi con significato simile, anche
  se non condividono le stesse parole (es. "auto" trova anche "veicolo"). Si basa su
  *embedding*, cioè rappresentazioni numeriche del significato del testo.
- **Ricerca sparsa (keyword/full-text)**: trova testi che contengono esattamente le
  parole della domanda. È più precisa su termini esatti, codici, nomi propri, acronimi.

I risultati delle due ricerche vengono fusi con un algoritmo chiamato **Reciprocal
Rank Fusion (RRF)**, descritto nel dettaglio più avanti.

## 2. Stack tecnologico

| Componente | Scelta | Perché |
|---|---|---|
| Linguaggio/runtime | Java 21, Spring Boot 3.3.4 | LTS, ecosistema maturo, DI a costruttore |
| Database | PostgreSQL + estensione `pgvector` | un solo DB per vettori (ricerca densa) e full-text (ricerca sparsa) — niente bisogno di Elasticsearch o un vector DB separato |
| Migrazioni schema | Flyway | versiona lo schema SQL, applicato automaticamente all'avvio |
| Provider LLM/embedding | Qualsiasi endpoint compatibile OpenAI (`/v1/embeddings`, `/v1/chat/completions`) | di default punta a **Ollama locale** (gratuito, nessuna API key), ma basta cambiare `OPENAI_BASE_URL` per usare OpenAI, Groq, ecc. |
| Dipendenze | Tutte da Maven Central | nessuna dipendenza dai repository interni ISP: il progetto gira anche senza accesso ai portali aziendali |

## 3. Architettura a livelli (hexagonal architecture)

Il codice è organizzato secondo l'architettura esagonale (ports & adapters):
il **dominio** definisce *cosa* serve al sistema tramite interfacce (le "porte"),
senza sapere *come* viene implementato. Gli **adapter** implementano quelle interfacce
e sono gli unici punti del codice che conoscono i dettagli tecnici (SQL, HTTP, JSON).

```
com.ragsearch
├── domain
│   ├── model      → oggetti di dominio immutabili (record Java)
│   └── port       → interfacce che il dominio richiede dall'esterno
├── application    → casi d'uso: orchestrano dominio e porte
├── adapter
│   ├── persistence → implementazione ChunkRepository su PostgreSQL/pgvector
│   ├── embedding   → implementazione EmbeddingClient via HTTP (OpenAI-style)
│   ├── llm         → implementazione CompletionClient via HTTP (OpenAI-style)
│   └── web         → controller REST (porta in ingresso)
└── config          → configurazione Spring (bean, proprietà tipizzate)
```

**Regola chiave**: solo `adapter` e `config` importano codice framework (Spring, JDBC,
`RestClient`). I package `domain` e `application` sono Java puro: non sanno nulla di
HTTP, SQL o Spring, e in teoria potrebbero essere testati o riusati anche fuori da
Spring Boot.

Tutte le dipendenze sono iniettate via **costruttore** (mai field injection): questo
rende esplicito da cosa dipende ogni classe e permette di sostituire facilmente un
adapter con un altro (es. cambiare provider embedding) senza toccare la logica
applicativa.

### 3.1 Perché questa architettura

- **Sostituibilità**: per cambiare provider di embedding (da Ollama a OpenAI, o a un
  modello locale diverso) basta scrivere una nuova classe che implementa
  `EmbeddingClient` — nessun altro codice cambia.
- **Testabilità**: la logica applicativa (`ChunkingService`, `IngestionService`,
  `RagOrchestrator`) può essere testata con mock delle porte, senza bisogno di un
  database o di chiamate HTTP reali.
- **Confini chiari**: i controller REST non passano mai oggetti di dominio
  direttamente all'esterno (e viceversa) — c'è sempre una mappatura esplicita tra DTO
  (record annidati nei controller) e modello di dominio.

## 4. Il modello di dominio (`domain/model`)

Tutti gli oggetti di dominio sono **record Java immutabili**: una volta creati non
possono essere modificati, il che li rende thread-safe e più facili da ragionare.

- **`DocumentChunk`**: un frammento di documento, con `id`, `documentId` (a quale
  documento appartiene), `chunkIndex` (posizione nel documento), `content` (il testo)
  e `embedding` (il vettore denso, `null` finché non è stato calcolato).
- **`RetrievedChunk`**: un chunk restituito dalla ricerca ibrida, con il punteggio
  di rilevanza fuso (`score`) calcolato da RRF.
- **`RagAnswer`**: la risposta finale generata dall'LLM, insieme ai chunk (`sources`)
  usati come contesto — utile per mostrare all'utente da dove viene l'informazione
  (tracciabilità/citazioni).

## 5. Le porte (`domain/port`)

Interfacce che il dominio richiede dall'esterno, implementate dagli adapter:

- **`ChunkRepository`**: persistenza dei chunk.
  - `saveAll(List<DocumentChunk>)`: salva chunk con embedding già calcolato.
  - `hybridSearch(queryText, queryEmbedding, topK)`: esegue la ricerca ibrida e
    restituisce i `topK` chunk più rilevanti.
- **`EmbeddingClient`**: calcolo di embedding densi.
  - `embed(text)`: embedding di un singolo testo (usato per la domanda dell'utente).
  - `embedAll(texts)`: embedding in batch (usato in fase di ingestione, più efficiente
    di N chiamate singole).
- **`CompletionClient`**: generazione testuale.
  - `complete(systemPrompt, userPrompt)`: invia i due prompt al modello di chat e
    restituisce la risposta generata.

## 6. Il livello applicativo (`application`)

Contiene i casi d'uso, ognuno con una responsabilità precisa (Single Responsibility).

### 6.1 `ChunkingService` — suddivisione del testo

Logica pura, senza dipendenze esterne. Divide un testo in chunk sovrapposti basati
sul conteggio di parole:

```
chunk(text, chunkSizeWords, overlapWords)
```

Algoritmo: il testo viene diviso in parole (split su spazi), poi si scorre con una
finestra di `chunkSizeWords` parole, avanzando di `chunkSizeWords - overlapWords`
parole a ogni passo, finché non si raggiunge la fine del testo.

**Perché l'overlap**: senza sovrapposizione, un'informazione che si trova a cavallo
tra due chunk (es. una frase spezzata proprio nel punto di taglio) rischia di perdere
contesto in entrambi i chunk. Con l'overlap (default: 40 parole su 250), l'informazione
al confine compare per intero in almeno un chunk.

Esempio con `chunkSizeWords=4`, `overlapWords=1` sul testo `"a b c d e f g h"`:

```
chunk 1: a b c d
chunk 2:       d e f g   (riparte da "d", l'ultima parola del chunk precedente)
chunk 3:             g h
```

Validazioni: lancia `IllegalArgumentException` se `chunkSizeWords <= 0` o se
`overlapWords` non è in `[0, chunkSizeWords)`. Su testo vuoto/blank restituisce lista
vuota (nessun errore, semplicemente "niente da fare").

### 6.2 `IngestionService` — orchestrazione dell'ingestione

Coordina l'intero processo di indicizzazione di un documento:

1. `ChunkingService.chunk()` → divide il testo in chunk (dimensioni configurabili
   via `rag.chunking.size-words` / `rag.chunking.overlap-words`).
2. `EmbeddingClient.embedAll()` → calcola l'embedding denso di **tutti i chunk in
   una sola chiamata batch** (più efficiente di una chiamata per chunk).
3. Costruisce gli oggetti `DocumentChunk` (con `UUID` generato per ciascuno).
4. `ChunkRepository.saveAll()` → persiste tutto in una singola operazione batch.

Se il testo non produce chunk (es. testo vuoto), l'ingestione si ferma subito e
restituisce `0` senza chiamare embedding o repository.

### 6.3 `RagOrchestrator` — orchestrazione della query

Coordina il processo di risposta a una domanda:

1. `EmbeddingClient.embed(question)` → embedding denso della domanda.
2. `ChunkRepository.hybridSearch(question, embedding, topK)` → recupera i chunk più
   rilevanti combinando ricerca densa e sparsa (dettagli nella sezione 8).
3. Costruisce il prompt: un **system prompt** fisso che istruisce il modello a
   rispondere *solo* usando il contesto fornito (per limitare le allucinazioni), più
   un **user prompt** che concatena i chunk recuperati e la domanda.
4. `CompletionClient.complete()` → invia i prompt al modello di chat.
5. Restituisce un `RagAnswer` con la risposta testuale e i chunk sorgente, per
   permettere tracciabilità di cosa ha usato il modello per rispondere.

Il system prompt usato è:

> *"You are a helpful assistant. Answer the user's question using ONLY the context
> provided below. If the answer is not contained in the context, say you don't know
> instead of guessing."*

Questo è il meccanismo che rende la risposta "grounded" (ancorata ai dati reali)
invece che basata sulla conoscenza generica del modello.

## 7. Gli adapter (`adapter/*`)

### 7.1 `JdbcChunkRepository` (persistence)

Implementa `ChunkRepository` usando `JdbcTemplate`/`NamedParameterJdbcTemplate` di
Spring, senza ORM (niente JPA/Hibernate): per query fortemente basate su SQL nativo
(vettori, RRF), l'accesso diretto è più semplice e trasparente di un ORM.

- **`saveAll`**: esegue un `batchUpdate` con una singola `INSERT` parametrizzata,
  che scrive contemporaneamente il testo, il vettore (tipo `PGvector` della libreria
  `com.pgvector:pgvector`) e il `tsvector` per la ricerca full-text (calcolato lato
  database con `to_tsvector('english', ...)`).
- **`hybridSearch`**: esegue la query SQL di fusione RRF descritta nella sezione 8.

### 7.2 `OpenAiEmbeddingClient` (embedding)

Chiama l'endpoint `/embeddings` di un provider compatibile OpenAI usando
`RestClient` (client HTTP sincrono di Spring). Il body della richiesta è
`{"model": ..., "input": [...]}` e la risposta viene deserializzata in una lista di
vettori (`float[]`). Funziona sia per `embed` (una domanda) sia per `embedAll`
(più chunk in batch, inviati come lista in un'unica chiamata HTTP).

### 7.3 `OpenAiCompletionClient` (llm)

Chiama l'endpoint `/chat/completions` con lo schema standard OpenAI
(`{"model": ..., "messages": [{"role": "system", ...}, {"role": "user", ...}]}`) ed
estrae il testo della prima risposta (`choices[0].message.content`).

Entrambi i client leggono `base-url` e `api-key` da `RagProperties` e li usano per
configurare un `RestClient` con header `Authorization: Bearer <key>`. Poiché **Ollama
espone la stessa interfaccia** sotto `http://localhost:11434/v1`, questi due adapter
funzionano senza modifiche sia con Ollama sia con OpenAI/Groq/qualsiasi provider
OpenAI-compatibile — cambia solo la configurazione, non il codice.

### 7.4 `IngestController` e `QueryController` (web)

Controller REST, unico punto di ingresso HTTP dell'applicazione:

- **`POST /api/v1/documents`**: riceve `{documentId, text}`, valida con Bean
  Validation (`@NotBlank`), chiama `IngestionService.ingest()`, risponde con
  `{documentId, chunksIndexed}`.
- **`POST /api/v1/query`**: riceve `{question}`, valida, chiama
  `RagOrchestrator.answer()`, risponde con `{answer, sources: [...]}`.

I DTO di richiesta/risposta sono `record` annidati nei controller stessi: sono
immutabili e non vengono mai passati al dominio — c'è sempre una mappatura esplicita
(vedi `QueryController.QueryResponse.from(RagAnswer)`).

## 8. Il cuore tecnico: la query di ricerca ibrida

Questa è la parte più importante del progetto. La ricerca ibrida è implementata
in **un'unica query SQL** (`JdbcChunkRepository.HYBRID_SEARCH_SQL`), non con due
query separate combinate in Java — questo evita di trasferire dati inutili
dall'applicazione al database e viceversa, e sfrutta il query planner di Postgres.

La query è composta da tre CTE (Common Table Expression, i blocchi `WITH ... AS`):

```sql
WITH dense AS (
    -- Ranking per similarità semantica (distanza coseno sull'embedding)
    SELECT id, RANK() OVER (ORDER BY embedding <=> CAST(:queryEmbedding AS vector)) AS rnk
    FROM document_chunks
    ORDER BY embedding <=> CAST(:queryEmbedding AS vector)
    LIMIT :candidatePoolSize
),
sparse AS (
    -- Ranking per rilevanza testuale (full-text search)
    SELECT id, RANK() OVER (ORDER BY ts_rank(content_tsv, plainto_tsquery('english', :queryText)) DESC) AS rnk
    FROM document_chunks
    WHERE content_tsv @@ plainto_tsquery('english', :queryText)
    LIMIT :candidatePoolSize
),
fused AS (
    -- Fusione dei due ranking con Reciprocal Rank Fusion
    SELECT COALESCE(d.id, s.id) AS id,
           COALESCE(1.0 / (:rrfK + d.rnk), 0.0) + COALESCE(1.0 / (:rrfK + s.rnk), 0.0) AS rrf_score
    FROM dense d
    FULL OUTER JOIN sparse s ON d.id = s.id
)
SELECT c.id, c.document_id, c.content, f.rrf_score
FROM fused f
JOIN document_chunks c ON c.id = f.id
ORDER BY f.rrf_score DESC
LIMIT :topK
```

### 8.1 Come funziona passo per passo

1. **`dense`**: ordina tutti i chunk per distanza coseno (`<=>`, operatore pgvector)
   tra il loro embedding e l'embedding della domanda. Distanza minore = più simile
   semanticamente. Prende i primi `candidatePoolSize` (default: `max(topK * 5, 50)`,
   un pool più ampio del `topK` finale, per non perdere candidati validi nella fase
   di fusione).
2. **`sparse`**: ordina i chunk che contengono almeno una parola della query
   (`content_tsv @@ plainto_tsquery(...)`) per `ts_rank`, la metrica standard
   PostgreSQL di rilevanza testuale (simile concettualmente a BM25/TF-IDF).
3. **`fused`**: unisce le due liste con un **FULL OUTER JOIN** (un chunk può comparire
   in una sola delle due liste, in entrambe, o in nessuna delle due — nel qual caso
   non compare affatto nel risultato finale) e calcola il punteggio RRF:

   ```
   rrf_score = 1 / (k + rank_dense) + 1 / (k + rank_sparse)
   ```

   dove `k` (`rrfK`, default `60`) è una costante di smorzamento: rende il punteggio
   meno sensibile a piccole variazioni di ranking nelle posizioni più basse, ed è il
   valore comunemente usato in letteratura per RRF. Se un chunk appare in una sola
   lista, il contributo dell'altra è `0` (grazie a `COALESCE`).
4. La query finale ordina per `rrf_score` decrescente e restituisce i primi `topK`
   chunk (default `5`, configurabile).

### 8.2 Perché RRF invece di una media pesata

Un'alternativa sarebbe normalizzare e sommare i punteggi grezzi (`score = α·score_dense
+ (1-α)·score_sparse`), ma i punteggi di similarità coseno e di `ts_rank` vivono su
scale completamente diverse e richiedono normalizzazione e taratura manuale di `α`.
RRF lavora solo sulle **posizioni** (rank) nelle due liste, non sui valori grezzi dei
punteggi: è quindi robusto "out of the box", senza bisogno di tuning, ed è per questo
la scelta di default in questo progetto.

## 9. Schema del database

Migrazione Flyway (`V1__init_document_chunks.sql`), applicata automaticamente
all'avvio dell'applicazione:

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE document_chunks (
    id UUID PRIMARY KEY,
    document_id VARCHAR(255) NOT NULL,
    chunk_index INTEGER NOT NULL,
    content TEXT NOT NULL,
    embedding VECTOR(768) NOT NULL,      -- dimensione = output del modello embedding
    content_tsv TSVECTOR NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_document_chunks_embedding ON document_chunks USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_document_chunks_content_tsv ON document_chunks USING GIN (content_tsv);
CREATE INDEX idx_document_chunks_document_id ON document_chunks (document_id);
```

- **`VECTOR(768)`**: dimensione dei vettori prodotti da `nomic-embed-text` (il modello
  di embedding di default via Ollama). Va cambiata (es. `1536` per
  `text-embedding-3-small` di OpenAI) se si usa un modello diverso — pgvector richiede
  che tutti i vettori nella colonna abbiano dimensione fissa e coerente.
- **Indice HNSW** (`hnsw`, Hierarchical Navigable Small World): struttura dati per la
  ricerca approssimata del vicino più prossimo (ANN) sui vettori — permette di
  cercare per similarità senza scandire l'intera tabella, fondamentale quando i chunk
  crescono in numero.
- **Indice GIN** sul `tsvector`: struttura standard PostgreSQL per accelerare le query
  full-text.
- **`document_id` non è chiave univoca**: un documento produce più righe (una per
  chunk); l'indice su `document_id` serve solo per query di lookup/filtro, non per
  vincoli di unicità. Nota: l'endpoint di ingest **non fa upsert** — ingerire due
  volte lo stesso `documentId` crea chunk duplicati.

## 10. Configurazione (`config/RagProperties`)

Le proprietà applicative sono centralizzate in un unico record annidato
(`@ConfigurationProperties(prefix = "rag")`), validato con Bean Validation
(`@Validated`, `@NotBlank`, `@Positive`, ecc.) — se manca un valore obbligatorio o
è fuori range, l'applicazione **non si avvia**, invece di fallire più tardi a runtime
con un errore meno chiaro.

| Sezione | Proprietà | Env var | Default | Significato |
|---|---|---|---|---|
| `chunking` | `sizeWords` | `CHUNK_SIZE_WORDS` | `250` | parole per chunk |
| | `overlapWords` | `CHUNK_OVERLAP_WORDS` | `40` | parole di sovrapposizione tra chunk consecutivi |
| `retrieval` | `topK` | `RETRIEVAL_TOP_K` | `5` | numero di chunk restituiti dalla ricerca ibrida |
| | `rrfK` | `RETRIEVAL_RRF_K` | `60` | costante di smorzamento RRF |
| `openai` | `apiKey` | `OPENAI_API_KEY` | `ollama` | chiave API (ignorata da Ollama) |
| | `baseUrl` | `OPENAI_BASE_URL` | `http://localhost:11434/v1` | endpoint del provider |
| | `embeddingModel` | `OPENAI_EMBEDDING_MODEL` | `nomic-embed-text` | modello di embedding |
| | `chatModel` | `OPENAI_CHAT_MODEL` | `llama3.2` | modello di chat/completion |

Tutte le proprietà sono lette da variabili d'ambiente con valori di default in
`application.yml`, secondo il pattern `${ENV_VAR:default}` — questo permette di far
girare l'app "così com'è" in locale (con Ollama) senza configurare nulla, e di
sovrascrivere solo ciò che serve in altri ambienti.

## 11. Flusso end-to-end riassuntivo

```
                    ┌─────────────────────────────────────────┐
                    │              INGESTIONE                  │
                    └─────────────────────────────────────────┘
Documento (testo) → ChunkingService (split + overlap)
                  → EmbeddingClient.embedAll (batch, Ollama/OpenAI)
                  → ChunkRepository.saveAll (INSERT batch: testo + vettore + tsvector)


                    ┌─────────────────────────────────────────┐
                    │                QUERY                      │
                    └─────────────────────────────────────────┘
Domanda utente → EmbeddingClient.embed (vettore della domanda)
              → ChunkRepository.hybridSearch
                    ├─ ranking denso (distanza coseno sull'embedding)
                    ├─ ranking sparso (ts_rank sul full-text)
                    └─ fusione RRF → top-K chunk
              → prompt = system instructions + chunk recuperati + domanda
              → CompletionClient.complete (Ollama/OpenAI)
              → RagAnswer { answer, sources }
```

## 12. Test

Il progetto include test unitari per `ChunkingService`
(`src/test/java/com/ragsearch/application/ChunkingServiceTest.java`), che copre:
testo più corto del chunk (un solo chunk), split con overlap su testo più lungo,
testo vuoto/blank (lista vuota), e la guard clause su `overlapWords` non valido.
Segue le convenzioni JUnit 5 + AssertJ del team: classi/metodi package-private,
naming `should_<comportamento>_when_<condizione>`, assert fluenti con `assertThat`.

Non essendoci dipendenze esterne da mockare (`ChunkingService` non ha collaboratori),
non è necessario Mockito per questo test: è puro test di logica.

## 13. Punti di estensione

- **Cambiare provider LLM/embedding**: scrivere una nuova classe che implementa
  `EmbeddingClient` e/o `CompletionClient` (es. `AnthropicCompletionClient` per Claude,
  che usa uno schema `/v1/messages` diverso da quello OpenAI) e registrarla come bean
  Spring al posto di quella OpenAI-style.
- **Reranking**: si potrebbe aggiungere un cross-encoder (es. `bge-reranker`) come
  step aggiuntivo in `RagOrchestrator`, tra `hybridSearch` e la costruzione del
  prompt, per raffinare ulteriormente i `topK` chunk prima di passarli al modello.
- **Upsert in ingestione**: `IngestionService`/`JdbcChunkRepository` potrebbero essere
  estesi per cancellare i chunk esistenti di un `documentId` prima di reinserirli,
  evitando duplicati quando lo stesso documento viene ri-ingerito.
- **Chunking più sofisticato**: `ChunkingService` fa uno split "a parole"; si
  potrebbe sostituirlo con uno split basato su token del modello, o su struttura
  semantica (paragrafi, sezioni), senza toccare nient'altro nella pipeline.
