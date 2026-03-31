# Rate Limiter - Broker de Inversiones

## Instrucciones para Claude

> **IMPORTANTE:** Leé este documento completo antes de empezar. Es el plan maestro del proyecto.
>
> **Tu primera tarea:** Crear los archivos de spec listados abajo en la carpeta `specs/` del proyecto. Después, implementar uno por uno en orden, creando un branch y PR por cada spec.
>
> **Reglas de trabajo:**
> 1. NO avances al siguiente spec hasta que el actual compile y pase todos los tests
> 2. NO agregues código, features, javadocs, o abstracciones que no estén en el spec
> 3. Después de cada spec, explicame qué hiciste y por qué
> 4. Si algo no queda claro, preguntá antes de implementar
> 5. Seguí estrictamente los estándares de código definidos abajo

---

## Context

Challenge de entrevista técnica de System Design. Implementar un **Rate Limiter funcional** basado en "System Design Interview" de Alex Xu (Cap. 4), aplicado al dominio de un **broker de inversiones online**.

**La consigna exige:**
- Código que compile y pase tests (si no compila, no se revisa)
- Tests completos del core
- Diseño elegante (ref: "A Philosophy of Software Design")
- Buen manejo de errores
- Evitar overengineering y AI slop
- DESIGN.md explicando decisiones y uso de AI
- Cualquier código no documentado como "no entendido" se asume dominado por el candidato

---

## Estándares de Código

### Obligatorios - Seguir en TODO el proyecto:

1. **Código limpio y declarativo:**
   - Variables con nombres descriptivos. NUNCA letras sueltas (`i`, `e`, `r`). Usar `requestCount`, `elapsedSeconds`, `remainingTokens`
   - Métodos cortos con un solo nivel de abstracción

2. **Java moderno:**
   - Usar `Optional<T>` cuando un valor puede estar ausente. No retornar null
   - Usar Streams cuando se opera sobre colecciones
   - Usar Records para DTOs y value objects inmutables

3. **No obsesión por primitivos:**
   - Si un concepto de negocio merece un tipo, crear un value object (ej: `ClientIp`, `EndpointPath`)
   - No pasar Strings sueltos cuando representan algo semántico

4. **Patrones y Spring:**
   - Usar annotations de Spring Boot (`@Component`, `@Configuration`, `@ConfigurationProperties`, etc.)
   - Usar Lombok donde simplifique (`@Builder`, `@Getter`, `@RequiredArgsConstructor`, `@Slf4j`)
   - Records de Java para modelos inmutables
   - Builder pattern para objetos con múltiples campos
   - Interfaces con `.of()` factory methods cuando tenga sentido

5. **Interfaces:**
   - Si una interfaz define un contrato, usar modelos propios de la interfaz (no acoplar a implementaciones)

6. **Inyección de dependencias:**
   - Constructor injection (via `@RequiredArgsConstructor` de Lombok)
   - Nunca field injection (`@Autowired` en campos)

---

## Decisiones de Diseño (basadas en Xu)

### Algoritmo: Token Bucket

**Por qué Token Bucket y no otros:**

| Algoritmo | Descartado porque |
|-----------|------------------|
| Leaking Bucket | Requests viejos en cola bloquean nuevos. No ideal para broker donde la latencia importa |
| Fixed Window Counter | Problema de borde: permite 2x el límite en la unión de dos ventanas |
| Sliding Window Log | Guarda todos los timestamps, consume mucha memoria |
| Sliding Window Counter | Bueno pero es una aproximación. Token Bucket es más directo y usado en la industria |

**Token Bucket elegido porque:**
- Tras evaluar los 5 algoritmos, Token Bucket ofrece el mejor balance entre precisión, uso de memoria y complejidad de implementación para este contexto
- Permite bursts controlados, lo cual tiene sentido en un broker donde un trader puede hacer varias consultas rápidas legítimamente
- Baja complejidad: reduce riesgo de bugs y facilita testing exhaustivo en el tiempo disponible
- Poca memoria (1 counter + 1 timestamp por bucket)
- No hay un requisito de negocio que justifique un algoritmo más preciso (como Sliding Window Log) a costa de mayor consumo de memoria. Si existiera un contexto donde la precisión exacta fuera crítica (ej: facturación por request), otro algoritmo sería más adecuado
- Referencia: Amazon y Stripe lo usan en producción, lo cual valida su robustez en escenarios reales

**Cómo funciona el algoritmo:**
```
Parámetros:
  - bucketSize: máximo de tokens (ej: 5)
  - refillRate: tokens que se agregan por segundo (ej: 5 por 60 seg = 1 cada 12 seg)

Estado por cada key (IP/usuario):
  - tokens: cantidad actual de tokens (arranca en bucketSize)
  - lastRefillTimestamp: última vez que se recalcularon tokens

Por cada request:
  1. Calcular cuántos tokens se regeneraron desde lastRefillTimestamp
  2. Sumar tokens regenerados (sin superar bucketSize)
  3. Si tokens >= 1:
       - Restar 1 token
       - Permitir request
  4. Si tokens < 1:
       - Rechazar con HTTP 429
       - Setear header Retry-After
```

### Storage: Redis (una instancia local)

- Redis con Lua script atómico para evitar race conditions
- Una sola instancia
- Circuit breaker: si Redis cae → fallback a rate limiting in-memory (ConcurrentHashMap)
- Docker Compose para levantar Redis fácilmente

### Fallback Strategy (Circuit Breaker + In-Memory)

```
Redis vivo     → RedisRateLimiter (100% preciso, compartido entre instancias)
Redis caído    → Circuit Breaker se abre → Fallback a LocalRateLimiter (in-memory)
Redis vuelve   → Circuit Breaker se cierra → Vuelve a RedisRateLimiter
```

Configuración del Circuit Breaker (Resilience4j):
```yaml
resilience4j:
  circuitbreaker:
    instances:
      redisRateLimiter:
        failure-rate-threshold: 50
        minimum-number-of-calls: 3
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 2
        sliding-window-size: 10
```

> **Nota sobre la configuración del CB:** Estos valores son estándares razonables para este contexto de prueba, donde el CB protege la conexión a Redis local. En un entorno productivo con comunicación entre microservicios, estos valores deben calibrarse en base a métricas reales del servicio: tiempos de respuesta (p50, p95, p99), tasa de errores histórica, y tiempo de recovery observado. No definir estos valores arbitrariamente sino con datos.

### Ubicación: Middleware (Spring Filter)

- Se intercepta el request ANTES de llegar al controller
- Transparente para los endpoints

### Criterio de limitación: por IP del cliente

Se eligió IP como criterio porque:
- No requiere autenticación ni sesión (el rate limiter actúa antes de cualquier lógica de negocio)
- Es el criterio más simple y universal para un prototipo
- Alternativas válidas en producción: por User ID (requiere auth), por API Key (requiere un sistema de keys), por combinación de criterios (IP + endpoint + user). La elección depende del contexto de negocio

### HTTP Responses

**Rate limited (429):**
```
HTTP 429 Too Many Requests
Headers:
  X-Ratelimit-Limit: 5
  X-Ratelimit-Remaining: 0
  X-Ratelimit-Retry-After: 12
Body:
{
  "error": "rate_limit_exceeded",
  "message": "Too many requests. Please retry after 12 seconds.",
  "retry_after_seconds": 12
}
```

**Normal (200):**
```
HTTP 200 OK
Headers:
  X-Ratelimit-Limit: 5
  X-Ratelimit-Remaining: 3
```

---

## Stack Tecnológico

| Componente | Tecnología |
|-----------|-----------|
| Lenguaje | Java 17+ |
| Framework | Spring Boot 3.x |
| Build | Gradle (Kotlin DSL) |
| Rate Limiter Storage | Redis (via Spring Data Redis / Lettuce) |
| Circuit Breaker | Resilience4j |
| Metrics | Micrometer + Spring Actuator |
| Tests | JUnit 5 + Mockito + AssertJ |
| Tests de integración | Testcontainers (Redis) |
| Redis local | Docker Compose |

---

## Dominio: Broker de Inversiones

API REST simulada con un endpoint de un broker. Los datos son mock (no hay lógica de trading real). El foco es el rate limiter.

### Endpoint

```
GET  /api/quotes/{symbol}     → Consultar precio de un activo (ej: AAPL, GOOGL)
GET  /health                  → Health check (NO rate limited)
```

> **Nota para DESIGN.md:** Mencionar que `POST /api/orders` (crear orden de compra/venta) no se implementó por tiempos pero se diseñó como extensión con su propio rate limit más restrictivo (3 req/min vs 5 req/min de quotes).

### Reglas de Rate Limiting (números bajos para testear fácil)

```yaml
rate-limiter:
  rules:
    - endpoint: /api/quotes
      bucket-size: 5
      refill-rate-per-minute: 5
  default:
    bucket-size: 10
    refill-rate-per-minute: 10
```

Con estos números, haciendo 6 requests rápidos a `/api/quotes` el sexto da 429.

---

## Estructura del Proyecto

```
rate-limiter/
├── specs/                              # Specs por feature (crear primero)
│   ├── spec-01-setup.md
│   ├── spec-02-token-bucket.md
│   ├── spec-03-redis-rate-limiter.md
│   ├── spec-04-circuit-breaker.md
│   ├── spec-05-broker-api.md
│   ├── spec-06-filter-and-config.md
│   ├── spec-07-nice-to-haves.md
│   ├── spec-08-integration-tests.md
│   └── spec-09-design-doc.md
├── docker-compose.yml
├── build.gradle.kts
├── settings.gradle.kts
├── DESIGN.md
├── src/
│   ├── main/
│   │   ├── java/com/broker/ratelimiter/
│   │   │   ├── Application.java
│   │   │   ├── ratelimiter/
│   │   │   │   ├── RateLimiter.java            # Interfaz
│   │   │   │   ├── RateLimitResult.java         # Record resultado
│   │   │   │   ├── RateLimitConfig.java         # Value object config
│   │   │   │   ├── TokenBucket.java             # Algoritmo puro in-memory
│   │   │   │   └── RedisRateLimiter.java        # Implementación Redis + Lua
│   │   │   ├── circuitbreaker/
│   │   │   │   └── ResilientRateLimiter.java    # Decorator con CB + fallback
│   │   │   ├── filter/
│   │   │   │   └── RateLimitFilter.java         # OncePerRequestFilter
│   │   │   ├── config/
│   │   │   │   ├── RateLimiterProperties.java   # @ConfigurationProperties
│   │   │   │   ├── RedisConfig.java
│   │   │   │   └── RateLimiterConfig.java       # Beans assembly
│   │   │   ├── controller/
│   │   │   │   └── QuoteController.java         # GET /api/quotes/{symbol}
│   │   │   ├── service/
│   │   │   │   ├── QuoteService.java            # Interfaz
│   │   │   │   └── DefaultQuoteService.java     # Implementación mock
│   │   │   ├── model/
│   │   │   │   └── Quote.java                   # Record: symbol, price, timestamp
│   │   │   └── exception/
│   │   │       └── GlobalExceptionHandler.java  # @ControllerAdvice
│   │   └── resources/
│   │       ├── application.yml
│   │       └── scripts/
│   │           └── token-bucket.lua
│   └── test/
│       ├── java/com/broker/ratelimiter/
│       │   ├── ratelimiter/
│       │   │   ├── TokenBucketTest.java
│       │   │   ├── RedisRateLimiterTest.java
│       │   │   └── LocalRateLimiterTest.java
│       │   ├── circuitbreaker/
│       │   │   └── ResilientRateLimiterTest.java
│       │   ├── filter/
│       │   │   └── RateLimitFilterTest.java
│       │   └── integration/
│       │       └── RateLimitIntegrationTest.java
│       └── resources/
│           └── application-test.yml
```

---

## Flujo de trabajo: Git + PRs

```
Para cada spec:
  1. git checkout main
  2. git checkout -b feature/XX-nombre
  3. Implementar el spec
  4. Correr tests: ./gradlew test
  5. Commit + Push
  6. Crear PR → Merge a main
```

---

## SPEC 01: Project Setup

**Branch:** `feature/01-setup`

**Objetivo:** Proyecto Spring Boot vacío que compila y levanta.

**Tareas:**
1. Crear proyecto con Spring Initializr (o manualmente):
   - Group: `com.broker`
   - Artifact: `rate-limiter`
   - Java 17+
   - Dependencies: Spring Web, Spring Data Redis, Lombok, Spring Boot Actuator
2. Agregar dependencias en `build.gradle.kts`:
   - `io.github.resilience4j:resilience4j-spring-boot3`
   - `org.testcontainers:junit-jupiter`
   - `org.testcontainers:testcontainers`
   - `org.assertj:assertj-core` (test)
   - `io.micrometer:micrometer-registry-prometheus`
3. Crear `docker-compose.yml`:
   ```yaml
   services:
     redis:
       image: redis:7-alpine
       ports:
         - "6379:6379"
   ```
4. Crear `Application.java` con `@SpringBootApplication`
5. Crear `application.yml` básico (server port 8080)
6. Verificar: `./gradlew build` compila sin errores

**Tests:** Ninguno. Solo verificar que compila.

**Criterio de éxito:** `./gradlew build` pasa.

---

## SPEC 02: Token Bucket Algorithm

**Branch:** `feature/02-token-bucket`

**Objetivo:** Implementar el algoritmo Token Bucket como clase Java pura, sin dependencias de Spring ni Redis. Este es el CORE del proyecto.

**Archivos a crear:**

### `RateLimitResult.java`
Record inmutable:
- `boolean allowed` - si el request fue permitido
- `int remainingTokens` - tokens restantes
- `long retryAfterSeconds` - segundos hasta poder reintentar (0 si allowed)
- Factory methods: `RateLimitResult.allowed(remainingTokens)`, `RateLimitResult.rejected(retryAfterSeconds)`

### `RateLimitConfig.java`
Value object inmutable (Record o clase con Builder):
- `int bucketSize` - máximo de tokens
- `int refillRatePerMinute` - tokens que se regeneran por minuto
- Validación: bucketSize > 0, refillRate > 0

### `RateLimiter.java`
Interfaz:
```java
public interface RateLimiter {
    RateLimitResult isAllowed(String key, RateLimitConfig config);
}
```

### `TokenBucket.java`
Implementación in-memory de `RateLimiter`:
- Usa `ConcurrentHashMap<String, BucketState>` internamente
- `BucketState`: inner record con `double tokens` y `long lastRefillTimestamp`
- El método `isAllowed()`:
  1. Obtiene o crea el BucketState para la key
  2. Calcula tokens regenerados: `elapsedSeconds * refillRatePerMinute / 60.0`
  3. Suma tokens regenerados (capped a bucketSize)
  4. Si tokens >= 1: resta 1, retorna `RateLimitResult.allowed(remaining)`
  5. Si tokens < 1: retorna `RateLimitResult.rejected(retryAfterSeconds)`
- Thread-safe usando `compute()` de ConcurrentHashMap
- Nombres de variables descriptivos (no `t`, `ts`, `n`)

**Tests (`TokenBucketTest.java`):**
- Request permitido cuando hay tokens disponibles
- Request rechazado cuando se agotan los tokens
- Tokens se regeneran después del tiempo adecuado
- Tokens nunca superan bucketSize al regenerar
- Burst: gastar todos los tokens rápido, verificar rechazo
- `retryAfterSeconds` se calcula correctamente
- Distintas keys son independientes (un usuario no afecta a otro)
- Thread safety: múltiples threads concurrentes no rompen el estado

**Criterio de éxito:** `./gradlew test --tests TokenBucketTest` pasa todos los tests.

---

## SPEC 03: Redis Rate Limiter

**Branch:** `feature/03-redis-rate-limiter`

**Objetivo:** Implementar el mismo Token Bucket pero con estado en Redis usando un Lua script atómico.

**Archivos a crear:**

### `token-bucket.lua` (en `src/main/resources/scripts/`)
```lua
-- KEYS[1] = rate limit key (ej: "rl:192.168.1.1:/api/quotes")
-- ARGV[1] = bucket size
-- ARGV[2] = refill rate per second
-- ARGV[3] = current timestamp in milliseconds

-- 1. Leer tokens y lastRefill de Redis (HGETALL)
-- 2. Si no existe, inicializar con bucketSize tokens
-- 3. Calcular elapsed time desde lastRefill
-- 4. Calcular tokens regenerados
-- 5. Sumar (capped a bucketSize)
-- 6. Si tokens >= 1: restar 1, guardar, retornar {1, remaining, 0}
-- 7. Si tokens < 1: retornar {0, 0, retryAfterMs}
-- 8. Setear TTL al key (para auto-limpieza de keys viejas)
```

### `RedisRateLimiter.java`
- Implementa `RateLimiter`
- Recibe `StringRedisTemplate` via constructor injection (`@RequiredArgsConstructor`)
- Carga el Lua script al inicializar (`DefaultRedisScript`)
- `isAllowed()` ejecuta el script via `redisTemplate.execute(script, keys, args)`
- Parsea resultado a `RateLimitResult` usando los factory methods

### `RedisConfig.java`
- `@Configuration` con bean de `StringRedisTemplate` si hace falta configuración custom

**Tests (`RedisRateLimiterTest.java`):**
- `@Testcontainers` con contenedor Redis
- Request permitido/rechazado
- Regeneración de tokens
- Keys independientes
- Atomicidad: múltiples threads no causan race conditions

**Criterio de éxito:** `./gradlew test --tests RedisRateLimiterTest` pasa. Requiere Docker.

---

## SPEC 04: Circuit Breaker + Fallback

**Branch:** `feature/04-circuit-breaker`

**Objetivo:** Si Redis cae, fallback a in-memory (TokenBucket). Nunca queda sin protección.

**Archivos a crear:**

### `ResilientRateLimiter.java`
- Implementa `RateLimiter`
- Recibe `RedisRateLimiter` (primario) y `TokenBucket` (fallback) via constructor
- Usa Resilience4j `CircuitBreaker`
- Flujo:
  1. Intenta `redisRateLimiter.isAllowed()`
  2. Si falla → Circuit Breaker cuenta el fallo
  3. Si CB se abre → ejecuta `localTokenBucket.isAllowed()` como fallback
  4. Cuando CB pasa a half-open → prueba Redis
  5. Si Redis responde → CB se cierra → vuelve a Redis
- Logging: loguear cuando hace fallback y cuando vuelve a Redis (`@Slf4j`)

**Tests (`ResilientRateLimiterTest.java`):**
- Redis vivo: usa RedisRateLimiter normalmente
- Redis caído: después de N fallos, hace fallback a TokenBucket in-memory
- Redis recovery: cuando Redis vuelve, deja de usar fallback
- Fallback mantiene rate limiting (requests siguen limitados, no fail open)

**Criterio de éxito:** `./gradlew test --tests ResilientRateLimiterTest` pasa.

---

## SPEC 05: Broker API (Mock)

**Branch:** `feature/05-broker-api`

**Objetivo:** Endpoint REST simulado del broker. Solo datos mock. El foco NO es el broker, es tener algo para que el rate limiter proteja.

**Archivos a crear:**

### `Quote.java`
Record inmutable:
- `String symbol`
- `double price`
- `Instant timestamp`
- Builder via Lombok `@Builder`

### `QuoteService.java`
- Interfaz con método `Quote getQuote(String symbol)`
- Abstrae la obtención de cotizaciones

### `DefaultQuoteService.java`
- `@Service`, implementa `QuoteService`
- Devuelve Quote con precio mock (random entre 100-500)
- En producción esto llamaría a un repository o API externa de mercado

### `QuoteController.java`
- `@RestController`
- Recibe `QuoteService` via constructor injection
- `GET /api/quotes/{symbol}` → delega a `QuoteService`
- Response: `{"symbol": "AAPL", "price": 187.43, "timestamp": "2024-01-15T10:30:00Z"}`

### `GlobalExceptionHandler.java`
- `@ControllerAdvice`
- Maneja excepciones genéricas, retorna JSON limpio

> **Nota:** No se implementa repository porque no hay persistencia real. El service devuelve datos mock. En producción, el service llamaría a un `QuoteRepository` que consulta una API de mercado o base de datos.

**Tests:** Test del controller (status 200, response shape) + test del service (retorna Quote válido).

**Criterio de éxito:** `curl http://localhost:8080/api/quotes/AAPL` devuelve JSON.

---

## SPEC 06: Filter + Configuration Wiring

**Branch:** `feature/06-filter-and-config`

**Objetivo:** Conectar todo: el filter HTTP que aplica rate limiting y la configuración que ensambla los componentes.

**Archivos a crear:**

### `RateLimiterProperties.java`
- `@ConfigurationProperties(prefix = "rate-limiter")`
- Lista de rules (endpoint + config)
- Default config para endpoints no especificados
- Usa Records o clases con Builder

### `RateLimitFilter.java`
- Extiende `OncePerRequestFilter`
- Recibe `ResilientRateLimiter` y `RateLimiterProperties` via constructor
- `@Slf4j` para logging
- Flujo:
  1. Si path es `/health` o `/actuator/**` → skip, `filterChain.doFilter()`
  2. Extraer IP: primero `X-Forwarded-For`, sino `request.getRemoteAddr()`
  3. Buscar regla que matchea el path (o usar default)
  4. Construir key: `"rl:" + ip + ":" + matchedPath`
  5. `rateLimiter.isAllowed(key, config)`
  6. Siempre setear headers `X-Ratelimit-Limit` y `X-Ratelimit-Remaining`
  7. Si allowed: `filterChain.doFilter()`
  8. Si rejected: status 429, header `X-Ratelimit-Retry-After`, JSON body

### `RateLimiterConfig.java` (Spring @Configuration)
- Bean `TokenBucket` (para fallback)
- Bean `RedisRateLimiter`
- Bean `ResilientRateLimiter` (compone ambos + CB)
- Bean `RateLimitFilter`

### `application.yml` (actualizar completo)
```yaml
server:
  port: 8080

spring:
  data:
    redis:
      host: localhost
      port: 6379
      timeout: 2s
      lettuce:
        pool:
          max-active: 8
          max-idle: 4
          min-idle: 1

rate-limiter:
  rules:
    - endpoint: /api/quotes
      bucket-size: 5
      refill-rate-per-minute: 5
  default-bucket-size: 10
  default-refill-rate-per-minute: 10

resilience4j:
  circuitbreaker:
    instances:
      redisRateLimiter:
        failure-rate-threshold: 50
        minimum-number-of-calls: 3
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 2
        sliding-window-size: 10

management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
```

**Tests (`RateLimitFilterTest.java`):**
- Request permitido: pasa al controller, headers correctos
- Request rechazado: 429, body correcto, headers correctos
- `/health` no se rate-limita
- IP se extrae correctamente de `X-Forwarded-For`
- IP se extrae de `getRemoteAddr()` si no hay header

**Criterio de éxito:** `./gradlew test --tests RateLimitFilterTest` pasa. App levanta con todo conectado.

---

## SPEC 07: Nice to Haves

**Branch:** `feature/07-nice-to-haves`

**Objetivo:** Agregar logging, métricas, timeouts, connection pool config, y comentarios de calidad.

### Logging (`@Slf4j` en las clases clave)
- En `RateLimitFilter`: loguear cuando un request es rechazado (IP, endpoint, tokens restantes). Nivel WARN
- En `ResilientRateLimiter`: loguear cuando hace fallback a in-memory. Nivel WARN
- En `ResilientRateLimiter`: loguear cuando Redis se recupera. Nivel INFO
- NO loguear cada request permitido (sería excesivo)

### Métricas (Micrometer + Actuator)
- Counter `rate_limiter_requests_allowed` con tags: endpoint
- Counter `rate_limiter_requests_rejected` con tags: endpoint
- Counter `rate_limiter_fallback_activations` (cuántas veces se activó el fallback)
- Disponibles en `/actuator/prometheus` y `/actuator/metrics`

### Timeouts y Connection Pool (ya en application.yml del spec 06)
- Redis connection timeout: 2s (no bloquear si Redis está lento)
- Lettuce pool: max-active=8, max-idle=4, min-idle=1
- Estos valores ya están en el YAML, este spec es para verificar que funcionan

### Comentarios
- Agregar comentarios PROPIOS (no AI slop) en:
  - El Lua script (explicar cada paso)
  - `TokenBucket.isAllowed()` (explicar el cálculo de refill)
  - `ResilientRateLimiter` (explicar la estrategia de fallback)
- Los comentarios deben explicar el POR QUÉ, no el QUÉ

**Tests:** Verificar que métricas se incrementan correctamente en el test de integración.

**Criterio de éxito:** `/actuator/prometheus` muestra las métricas. Logs aparecen cuando se rechaza un request.

---

## SPEC 08: Integration Tests E2E

**Branch:** `feature/08-integration-tests`

**Objetivo:** Tests que levantan la app completa y verifican el flujo end-to-end.

### `RateLimitIntegrationTest.java`
- `@SpringBootTest(webEnvironment = RANDOM_PORT)`
- `@Testcontainers` con Redis
- Usa `TestRestTemplate`

**Tests:**
1. **Rate limiting funciona:** 6 GET a `/api/quotes/AAPL` → primeros 5 dan 200, el 6to da 429
2. **Headers correctos:** `X-Ratelimit-Limit: 5`, `X-Ratelimit-Remaining` decrece de 4 a 0
3. **Retry-After presente en 429**
4. **IPs distintas son independientes:** (simular con header `X-Forwarded-For` distinto)
5. **Health no se limita:** muchos requests a `/health` → todos 200
6. **Token refill:** 5 requests, esperar refill time, otro request → 200
7. **Métricas se actualizan:** después de requests, verificar counters en `/actuator/metrics`

### `application-test.yml`
- Config de test con valores bajos para que los tests sean rápidos

**Criterio de éxito:** `./gradlew test` pasa TODOS los tests (unitarios + integración).

---

## SPEC 09: DESIGN.md

**Branch:** `feature/09-design-doc`

**Objetivo:** Documento final para entregar.

**Secciones:**

1. **Overview:** Qué es un rate limiter y qué problema resuelve en el contexto de un broker
2. **Algoritmo elegido:** Token Bucket con tabla comparativa vs los otros 4 (Leaking Bucket, Fixed Window, Sliding Window Log, Sliding Window Counter)
3. **Arquitectura:**
   - Diagrama de flujo del request (ASCII art)
   - Componentes y cómo se conectan
4. **Storage:** Redis + Lua script para atomicidad. Por qué Lua y no INCR/EXPIRE separados
5. **Resiliencia:** Circuit Breaker + fallback in-memory. Por qué no fail open puro (quedás sin protección)
6. **Nice to haves implementados:** Logging, métricas, timeouts, connection pool
7. **Endpoint no implementado:** `POST /api/orders` como extensión con rate limit más restrictivo (3 req/min). No se implementó por tiempos
8. **Testing strategy:** Unitarios del algoritmo puro, integración con Testcontainers, E2E
9. **Mejoras futuras (no implementadas):**
   - Redis Cluster / Sentinel para alta disponibilidad
   - Rate limiting por User ID (requiere autenticación)
   - Reglas por tipo de cliente (retail vs institucional)
   - Dashboard de métricas (Grafana + Prometheus)
   - Sliding Window Counter como algoritmo alternativo
   - Reglas dinámicas via API
10. **Uso de AI:** Qué herramientas se usaron, cómo se usaron, qué se entiende y qué se documentó

**Criterio de éxito:** DESIGN.md existe, es claro, sin slop.

---

## Verificación Final

```bash
# 1. Levantar Redis
docker-compose up -d

# 2. Correr TODOS los tests
./gradlew test

# 3. Levantar la app
./gradlew bootRun

# 4. Testear rate limiting manual
for i in {1..6}; do
  echo "--- Request $i ---"
  curl -s -w "\nHTTP Status: %{http_code}\n" http://localhost:8080/api/quotes/AAPL -D -
done
# Request 6 → 429 con headers X-Ratelimit-*

# 5. Testear circuit breaker fallback
docker-compose stop redis
curl -s -w "\nHTTP Status: %{http_code}\n" http://localhost:8080/api/quotes/AAPL
# Debería seguir limitando (fallback in-memory activo, no fail open)

# 6. Testear recovery
docker-compose start redis
# Esperar ~30s, rate limiting vuelve via Redis

# 7. Ver métricas
curl http://localhost:8080/actuator/prometheus | grep rate_limiter

# 8. Ver health
curl http://localhost:8080/health
```
