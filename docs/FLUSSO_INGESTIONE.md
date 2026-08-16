# Flusso dettagliato: ingestione di un documento

Questo documento traccia, passo per passo, esattamente cosa accade nel sistema
quando si esegue:

```bash
curl -s -X POST http://localhost:8080/api/v1/documents \
  -H "Content-Type: application/json" \
  --data-binary @payload.json
```

con `payload.json`:

```json
{"documentId": "doc-2", "text": "Il gatto è un animale domestico felino. I cani sono animali domestici canini molto fedeli. Roma è la capitale d'Italia."}
```

Per la visione d'insieme dell'architettura vedi [ARCHITETTURA.md](ARCHITETTURA.md);
qui invece si segue **una singola richiesta reale**, riga di codice per riga di codice.

## Panoramica del percorso

```
curl → Tomcat → IngestController (deserializza + valida)
             → IngestionService.ingest()
                  → ChunkingService.chunk()          [split in chunk]
                  → OpenAiEmbeddingClient.embedAll()  → HTTP → Ollama → vettori
                  → new DocumentChunk(...)             [per ogni chunk]
                  → JdbcChunkRepository.saveAll()      → INSERT (+ to_tsvector) → Postgres/pgvector
             ← IngestResponse{documentId, chunksIndexed}
curl ← 200 OK {"documentId":"doc-2","chunksIndexed":1}
```

## Passo 0 — Il payload

```json
{"documentId": "doc-2", "text": "Il gatto è un animale domestico felino. I cani sono animali domestici canini molto fedeli. Roma è la capitale d'Italia."}
```

`--data-binary @payload.json` dice a curl di inviare il contenuto del file **così
com'è**, byte per byte, senza le trasformazioni che curl applicherebbe con `-d`
(utile qui perché il testo contiene apostrofi e caratteri accentati UTF-8 che, se
passati come stringa inline nella shell di Windows, spesso rompono il JSON).

## Passo 1 — Rete: da curl a Tomcat

`curl` apre una connessione TCP verso `localhost:8080` e invia una richiesta HTTP:

```
POST /api/v1/documents HTTP/1.1
Host: localhost:8080
Content-Type: application/json
Content-Length: <n>

{"documentId": "doc-2", "text": "..."}
```

Tomcat, il servlet container **embedded** avviato da Spring Boot (visibile nei log
di avvio: `Tomcat started on port 8080`), accetta la connessione sul suo thread pool
e passa la richiesta al `DispatcherServlet` di Spring MVC, che è il front controller
di tutta l'applicazione web.

## Passo 2 — Routing e deserializzazione

**File:** [`IngestController.java`](../src/main/java/com/ragsearch/adapter/web/IngestController.java)

Spring MVC ha registrato all'avvio (scansionando le annotazioni) che
`@RequestMapping("/api/v1/documents")` + `@PostMapping` sul metodo `ingest(...)`
gestisce questa combinazione verbo+path. Il `DispatcherServlet` instrada la richiesta
a quel metodo.

Il parametro `@RequestBody IngestRequest request` fa scattare **Jackson**
(`HttpMessageConverter` di Spring), che legge il body JSON e lo mappa sul record:

```java
record IngestRequest(@NotBlank String documentId, @NotBlank String text) {}
```

Jackson usa il constructor canonico del record (introspezione via reflection) per
istanziarlo. Risultato:

- `request.documentId()` → `"doc-2"`
- `request.text()` → `"Il gatto è un animale domestico felino. I cani sono animali domestici canini molto fedeli. Roma è la capitale d'Italia."`

Se il JSON fosse malformato (es. apostrofi non correttamente escapati che rompono la
sintassi JSON), Jackson lancerebbe un'eccezione di parsing **prima ancora** che il
metodo del controller venga invocato, e Spring risponderebbe con `400 Bad Request` —
è esattamente l'errore che si vedeva tentando di passare il testo con apostrofi come
stringa inline in bash su Windows.

## Passo 3 — Validazione (Bean Validation / JSR-380)

L'annotazione `@Valid` sul parametro attiva la validazione **prima** che il corpo del
metodo `ingest(...)` venga eseguito. Il validatore controlla i vincoli dichiarati sul
record:

- `documentId` → `@NotBlank` → `"doc-2"` non è vuoto/blank → **passa**
- `text` → `@NotBlank` → il testo non è vuoto/blank → **passa**

Se un vincolo fallisse, Spring lancerebbe `MethodArgumentNotValidException`, gestita
dal meccanismo di default di Spring Boot con una risposta `400 Bad Request` — il
metodo del controller non verrebbe mai chiamato.

Con questo payload, la validazione passa e si procede.

## Passo 4 — Delega al caso d'uso applicativo

Il controller non contiene logica di business: si limita a delegare.

```java
int chunkCount = ingestionService.ingest(request.documentId(), request.text());
```

`ingestionService` è stato iniettato nel costruttore di `IngestController` da Spring
all'avvio dell'applicazione (dependency injection). Da qui entriamo nel package
`application`, che non conosce nulla di HTTP.

**File:** [`IngestionService.java`](../src/main/java/com/ragsearch/application/IngestionService.java)

## Passo 5 — Chunking del testo

```java
List<String> chunkTexts = chunkingService.chunk(
        text, properties.chunking().sizeWords(), properties.chunking().overlapWords());
```

`properties` è `RagProperties`, popolata all'avvio da `application.yml` +
variabili d'ambiente. Con i default: `sizeWords = 250`, `overlapWords = 40`.

**File:** [`ChunkingService.java`](../src/main/java/com/ragsearch/application/ChunkingService.java)

Dentro `chunk(...)`:

1. `text.trim().split("\\s+")` spezza il testo su sequenze di spazi bianchi. Il testo
   dell'esempio produce un array di **20 parole**:
   `["Il","gatto","è","un","animale","domestico","felino.","I","cani","sono",
   "animali","domestici","canini","molto","fedeli.","Roma","è","la","capitale",
   "d'Italia."]`
2. Il ciclo parte con `start = 0`. Poiché `chunkSizeWords = 250` e le parole totali
   sono solo 20, `end = Math.min(0 + 250, 20) = 20` — cioè il chunk copre **tutte**
   le parole disponibili in un colpo solo.
3. Viene aggiunto un solo elemento alla lista: le 20 parole ri-unite con `String.join(" ", ...)`,
   che ricostruisce (quasi) il testo originale.
4. Poiché `end == words.length` (20 == 20), il ciclo si interrompe con un `break` —
   non c'è un secondo giro.

Risultato: `chunkTexts` contiene **un solo elemento**, l'intero testo originale.
(Se il testo fosse stato più lungo di 250 parole, qui sarebbero stati prodotti più
chunk, ciascuno sovrapposto al successivo per 40 parole — vedi
[ARCHITETTURA.md § 6.1](ARCHITETTURA.md#61-chunkingservice--suddivisione-del-testo)
per l'esempio con numeri più piccoli.)

Guard clause: se `chunkTexts` fosse vuota (testo blank), `IngestionService` si
fermerebbe qui restituendo `0`, senza fare alcuna chiamata di embedding o al database.
Non è questo il caso.

## Passo 6 — Calcolo dell'embedding

```java
List<float[]> embeddings = embeddingClient.embedAll(chunkTexts);
```

**File:** [`OpenAiEmbeddingClient.java`](../src/main/java/com/ragsearch/adapter/embedding/OpenAiEmbeddingClient.java)

Dentro `embedAll(List.of(quel_unico_testo))`:

1. Viene costruito il record di richiesta:
   ```java
   new EmbeddingRequest("nomic-embed-text", List.of("Il gatto è un animale domestico felino. ..."))
   ```
   (`"nomic-embed-text"` viene da `properties.openai().embeddingModel()`, default
   configurato in `application.yml`).

2. `restClient.post().uri("/embeddings")...` esegue una vera chiamata HTTP:
   ```
   POST http://localhost:11434/v1/embeddings
   Authorization: Bearer ollama
   Content-Type: application/json

   {"model":"nomic-embed-text","input":["Il gatto è un animale domestico felino. ..."]}
   ```
   Il `RestClient` è stato configurato nel costruttore con `baseUrl` = `OPENAI_BASE_URL`
   (default `http://localhost:11434/v1`, cioè **Ollama in locale**) e l'header
   `Authorization` con la (finta) API key.

3. **Ollama**, il processo in esecuzione in background sulla macchina locale, riceve
   la richiesta sulla sua porta 11434. Espone un'API compatibile con lo schema
   `/v1/embeddings` di OpenAI. Carica (o riusa se già caricato in memoria) il modello
   `nomic-embed-text` e lo esegue sul testo in input: il modello trasforma il testo in
   un **vettore denso di 768 numeri in virgola mobile**, una rappresentazione numerica
   che cattura il significato semantico della frase in uno spazio vettoriale — frasi
   con significato simile produrranno vettori "vicini" secondo la distanza coseno.

4. Ollama risponde con un JSON del tipo:
   ```json
   {"data": [{"embedding": [0.0123, -0.0456, ...]}]}
   ```
   che `RestClient` deserializza automaticamente (via Jackson) nel record
   `EmbeddingResponse(List<Item> data)`.

5. Il metodo estrae `response.data().stream().map(EmbeddingResponse.Item::embedding).toList()`
   → restituisce `List<float[]>` con un solo elemento: l'array di 768 float.

Se Ollama non fosse raggiungibile (processo non avviato), questa chiamata fallirebbe
con un'eccezione di connessione, che risalirebbe fino al controller e produrrebbe un
errore `500` (non gestito esplicitamente da un `@ExceptionHandler` in questo
progetto).

## Passo 7 — Costruzione degli oggetti di dominio

Tornati in `IngestionService.ingest(...)`:

```java
List<DocumentChunk> chunks = new ArrayList<>(chunkTexts.size());
for (int i = 0; i < chunkTexts.size(); i++) {
    chunks.add(new DocumentChunk(UUID.randomUUID(), documentId, i, chunkTexts.get(i), embeddings.get(i)));
}
```

Per l'unico chunk (`i = 0`):

```java
new DocumentChunk(
    UUID.randomUUID(),   // es. 61127dd3-923d-4048-98fc-90e4de4247cf — generato ora, casuale
    "doc-2",              // documentId, dal payload originale
    0,                     // chunkIndex — primo (e unico) chunk del documento
    "Il gatto è...",       // content — il testo del chunk
    float[768]             // embedding calcolato al passo 6
)
```

**File:** [`DocumentChunk.java`](../src/main/java/com/ragsearch/domain/model/DocumentChunk.java)

Il constructor compatto del record esegue la sua validazione: `content` non deve
essere blank. Essendo un record, l'oggetto è **immutabile** una volta creato.

## Passo 8 — Persistenza su PostgreSQL

```java
chunkRepository.saveAll(chunks);
```

**File:** [`JdbcChunkRepository.java`](../src/main/java/com/ragsearch/adapter/persistence/JdbcChunkRepository.java)

```java
jdbcTemplate.batchUpdate(INSERT_SQL, chunks, chunks.size(), (ps, chunk) -> {
    ps.setObject(1, chunk.id());
    ps.setString(2, chunk.documentId());
    ps.setInt(3, chunk.chunkIndex());
    ps.setString(4, chunk.content());
    ps.setObject(5, new PGvector(chunk.embedding()), Types.OTHER);
    ps.setString(6, chunk.content());
});
```

Con la query:

```sql
INSERT INTO document_chunks (id, document_id, chunk_index, content, embedding, content_tsv)
VALUES (?, ?, ?, ?, ?, to_tsvector('english', ?))
```

Cosa succede tecnicamente:

1. `JdbcTemplate` ottiene una connessione JDBC dal **pool HikariCP** (già aperta e
   pronta, non ne apre una nuova ogni volta — visibile nei log all'avvio:
   `HikariPool-1 - Added connection`).
2. Prepara uno `PreparedStatement` con i 6 placeholder `?`.
3. `new PGvector(chunk.embedding())` avvolge l'array di 768 float in un oggetto della
   libreria `com.pgvector:pgvector`, che sa serializzarlo nel formato testuale che il
   tipo colonna `vector` di Postgres si aspetta (es. `[0.0123,-0.0456,...]`).
   `ps.setObject(5, ..., Types.OTHER)` lo lega al placeholder usando il tipo SQL
   generico `OTHER`, necessario perché `vector` non è un tipo JDBC standard.
4. Il sesto parametro (`content` di nuovo) viene passato **non a un placeholder di
   valore diretto**, ma dentro `to_tsvector('english', ?)`: è **Postgres stesso**,
   in fase di esecuzione della query, a tokenizzare il testo, applicare stemming,
   rimuovere le stop-word della lingua inglese (impostata qui come lingua di analisi)
   e produrre il valore `TSVECTOR` da scrivere nella colonna `content_tsv`.
5. `batchUpdate` esegue lo statement per ogni chunk della lista (qui: uno solo) e lo
   invia al database — con più chunk, questo evita una round-trip di rete separata
   per ogni riga.
6. Il driver `org.postgresql` (dipendenza `postgresql`) serializza la richiesta nel
   protocollo di rete PostgreSQL e la invia al container Docker
   (`pgvector/pgvector:pg16`, avviato da `docker compose up -d`) in ascolto su
   `localhost:5432`.
7. **Dentro PostgreSQL**: la riga viene scritta fisicamente nella tabella
   `document_chunks`. Gli indici della tabella vengono aggiornati in modo coerente
   con l'inserimento:
   - l'indice **HNSW** (`idx_document_chunks_embedding`) su `embedding`, che mantiene
     una struttura a grafo per ricerche approssimate di similarità vettoriale
     efficienti anche su molte righe;
   - l'indice **GIN** (`idx_document_chunks_content_tsv`) su `content_tsv`, per
     accelerare le future ricerche full-text;
   - l'indice B-tree su `document_id`.
8. L'operazione avviene in **auto-commit** (comportamento di default di
   `JdbcTemplate`/HikariCP quando non c'è una `@Transactional` esplicita che apra una
   transazione più ampia): la riga è committata e visibile ad altre connessioni non
   appena l'`INSERT` termina.

## Passo 9 — Ritorno del conteggio

`saveAll` non restituisce nulla di significativo (`void`); `IngestionService.ingest(...)`
restituisce semplicemente `chunks.size()` → **`1`**.

## Passo 10 — Costruzione e serializzazione della risposta

Tornati in `IngestController`:

```java
return ResponseEntity.ok(new IngestResponse(request.documentId(), chunkCount));
```

`IngestResponse("doc-2", 1)` è un record — Jackson lo serializza in:

```json
{"documentId":"doc-2","chunksIndexed":1}
```

`ResponseEntity.ok(...)` imposta lo status HTTP a `200`. Spring MVC scrive il body
JSON nella risposta HTTP, Tomcat la invia sul socket TCP verso curl.

## Passo 11 — curl stampa il risultato

```bash
$ curl -s -X POST http://localhost:8080/api/v1/documents -H "Content-Type: application/json" --data-binary @payload.json
{"documentId":"doc-2","chunksIndexed":1}
```

## Nota sui re-invii ripetuti

L'endpoint **non fa upsert**: ogni chiamata a questo comando, anche con lo stesso
`payload.json`, genera un nuovo `UUID.randomUUID()` (passo 7) e inserisce **un nuovo
chunk duplicato**, senza sovrascrivere o rimuovere quelli già presenti per lo stesso
`documentId`. Rilanciare il comando più volte non è idempotente — è un comportamento
noto e voluto nella versione attuale (vedi
[ARCHITETTURA.md § 13](ARCHITETTURA.md#13-punti-di-estensione) per una possibile
estensione futura con logica di upsert).
