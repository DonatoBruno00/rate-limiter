# Rate Limiter — Documento de Diseño

## 1. Descripción General

Este proyecto implementa un rate limiter por IP de cliente usando el algoritmo token bucket,
aplicado sobre una API REST de un broker mock. El estado de cada cliente se almacena en Redis
y se aplica mediante un filtro servlet antes de que el request llegue al controlador. Si Redis no
está disponible, un circuit breaker de Resilience4j activa un fallback en memoria para que el
servicio siga funcionando.

Dediqué aproximadamente algunas horas el lunes y algunas horas el martes al proyecto, distribuidas entre lectura, diseño e implementación.

---

## 2. Por qué Rate Limiter

Elegí este problema porque en cualquier sistema distribuido es crucial no sobrecargar un servicio, y por una cuestión de complejidad-tiempo para poder entregarlo en la fecha pactada.
También se conecta directamente con trabajo que hice en MercadoLibre, configuré múltiples veces pools de conexiones y throttling entre servicios internos.
Construirlo desde cero me pareció una buena oportunidad, conocer los diferentes algoritmos y entenderlo en profundidad.

## 3. Elección de Algoritmo

Elegí **token bucket** sobre las alternativas por tres razones:


Contexto, dado el tiempo a dedicarle sumado a las diferentes opciones y trade offs, sin una necesidad concreta de máxima precisión vs más memoria, decidí token bucket.
- **Tolerante a bursts**: los clientes pueden acumular tokens hasta el tamaño del bucket y
  gastarlos en ráfagas cortas. Fixed window y sliding window penalizan bursts que caen cerca
  del borde de una ventana.
- **Más simple que otras opciones**: el estado por cliente son solo dos números — tokens restantes y timestamp

---

## 4. Arquitectura

```
Request del cliente
     │
     ▼
RateLimitFilter          ← extrae IP del cliente, llama al RateLimiter
     │
     ▼
ResilientRateLimiter     ← envuelve la llamada a Redis en un circuit breaker
     │                ↘ (Redis caído)
     ▼                 ▼
RedisRateLimiter     TokenBucket (fallback en memoria)
(script Lua)
     │
     ▼
QuoteController → QuoteService → respuesta Quote
```

El filtro corre antes de cada request excepto los paths excluidos (`/actuator/**`). Lee la IP
del cliente desde el header `X-Forwarded-For` si está presente (requests que vienen a través de
un posible load balancer), y cae al socket address para conexiones directas.

---

## 5. Por qué Empecé En Memoria y Luego Agregué Redis

Mi primera implementación fue un token bucket puro en memoria con `ConcurrentHashMap`, para no llevarlo a overengineering
por ser un challenge y quizás verlo a más alto nivel, pero para implementar ciertos nice to have o poder aumentar el flujo
implementé Redis. Para no eliminar la implementación in memory lo dejé como fallback por si el CB se abre, pasa el tiempo y Redis sigue sin responder.

Así que el `TokenBucket` en memoria terminó cumpliendo dos roles: el prototipo original y el
fallback del circuit breaker.

---

## 6. Redis + Script Lua

El estado del token bucket vive en un hash de Redis por cliente:
```
rl:192.168.1.1 → { tokens: 9.0, lastRefillMillis: 1711900000000 }
```

El requisito crítico es la **atomicidad**: leer tokens, refill, consumir, escribir de vuelta —
todo como una sola unidad. Si dos requests del mismo cliente llegan simultáneamente y ambos leen
"9 tokens", ambos consumirían uno y escribirían "8", perdiendo un request. Esa es una race
condition.

Redis ejecuta scripts Lua de forma atómica. Ningún otro comando puede correr en el servidor
mientras el script está ejecutando — sin locks, sin transacciones. El lado Java pasa el timestamp
actual como argumento (`ARGV[3]`) para que el script pueda calcular el tiempo transcurrido para
el refill.

Investigué cómo implementar token bucket sobre Redis antes de escribir código. Encontré que
Lua scripts es el approach estándar para operaciones atómicas, y partí de un template básico.
Desde ahí fui iterando: usé Claude para evaluar trade-offs entre diferentes enfoques (Lua vs
transacciones Redis) y fui refinando
hasta llegar a la implementación actual.

Los keys expiran después de 1 hora de inactividad para que Redis no acumule estado de clientes
que dejaron de enviar requests.

---

## 7. Circuit Breaker

Usé el circuit breaker de Resilience4j con tres estados:

- **Cerrado** (normal): cada request va a Redis.
- **Abierto** (Redis caído): después de 5 fallos consecutivos de Redis, el circuito se abre.
  Por los próximos 30 segundos todos los requests saltean Redis y van directo al `TokenBucket`
  en memoria. El cliente no ve errores — sigue siendo rate limitado, solo con menos precisión
  (el fallback es por instancia, no compartido).
- **Half-open**: después de 30 segundos, 2 requests de prueba van a Redis. Si tienen éxito,
  el circuito se cierra y Redis vuelve al camino.

Este patrón lo vi aplicado en producción en MercadoLibre. Cuando una dependencia downstream
está fluctuando, devolvemos un 200 con data parcial; en este caso usé una implementación in memory para responder.

---

## 8. Implementación del TokenBucket — Decisiones Clave

**`ConcurrentHashMap.compute()` en lugar de `AtomicReference`**
Iteré varias implementaciones del algoritmo y la versión final fue la siguiente:
Una alternativa fue guardar un `AtomicReference<BucketState>` por clave y hacer un loop de CAS:
leer el estado actual, computar el nuevo estado, `compareAndSet`, reintentar si otro thread ganó
la carrera. Funciona, pero agrega complejidad — el retry loop es fácil de escribir mal.

`ConcurrentHashMap.compute()` da la misma garantía de forma más simple: el lambda entero corre
de forma atómica para esa clave. No pueden entrar dos threads al `compute()` de la misma clave
simultáneamente. Un lock por bucket, sin retry loops, la misma correctitud.

Evalué estas opciones junto con `synchronized` antes de decidir. Esta es la que mejor equilibra
correctitud, simplicidad y performance.

**Records para `BucketState` y `Evaluation`**

`BucketState` contiene `(tokens, lastRefillNanos)` — es un objeto de valor puro sin comportamiento.
Los records son la herramienta correcta: inmutables, sin boilerplate, con accesores descriptivos.
El record `Evaluation` agrupa el nuevo estado con el resultado del rate limit para que `compute()`
pueda devolver ambos en una sola operación atómica.

**Clock inyectable (`LongSupplier`)**

`System.nanoTime()` hace la clase no testeable sin `Thread.sleep()`. El constructor acepta un
`LongSupplier` para que los tests puedan inyectar un clock falso y avanzar el tiempo.

---

## 9. Estructura de Código y Convenciones

**Estructura de paquetes** sigue responsabilidad, no capa. `ratelimiter` contiene el algoritmo
central y sus wrappers de Redis/circuit-breaker. `filter` lo conecta al pipeline HTTP. `config`
tiene las propiedades externalizadas. Esto facilita la navegación: si querés entender el
algoritmo, vas a `ratelimiter`.

**Record `RateLimitConfig`** agrupa los dos parámetros (`bucketSize`, `refillRate`) que viajan
juntos por cada capa. También tiene la conversión `refillRatePerSecond()` para que la traducción
de unidades de minutos a segundos no esté dispersa entre los callers.

**Feature branches por spec**: cada spec se desarrolló en su propia rama `feature/spec-0X-*` y
se mergeó de forma independiente. Esto mantuvo el historial de git legible y cada pieza del
diseño revisable por separado.

**Comentarios en el código**: no es algo que suela hacer — el código debería entenderse por sí
solo. Pero en los algoritmos y configuraciones de más bajo nivel (el script Lua, el `compute()`
del token bucket, la config del circuit breaker) me interesó dejar comentarios que expliquen el
paso a paso, ya que la lógica no es inmediatamente obvia.

---

## 10. Estrategia de Testing

**Tests unitarios (`TokenBucketTest`)**: testean el algoritmo en aislamiento con un clock falso.
Sin contexto de Spring, sin Redis, sin threads. Acá viven los edge cases: exactamente en el
límite, refill parcial, múltiples clientes. Rápidos y deterministas.

**Tests de integración con Testcontainers (`RedisRateLimiterTest`)**: levantan un container de
Redis real y verifican que el script Lua se comporta correctamente — atomicidad, matemática de
refill, TTL. El punto es que el script Lua corre correctamente sobre un Redis real.

**Tests E2E de integración (`RateLimitIntegrationTest`)**: inician la aplicación Spring Boot
completa contra un container de Redis real y llaman a la HTTP API con `TestRestTemplate`. Verifican
el pipeline completo: filtro → circuit breaker → Redis → headers → códigos de respuesta.


---

## 11. Cómo Escalaría Esto

**Precios desde un servicio externo**

Hoy `DefaultQuoteService` tiene los precios hardcodeados. En producción, los precios de CEDEARs
vendrían de una API externa — por ejemplo el feed de un market data provider o un servicio interno
que consume datos de la bolsa en tiempo real. El problema de consultar esa API en cada request es
doble: latencia variable y carga innecesaria sobre el proveedor.

**Arquitectura event-driven para actualización de precios**

La solución es desacoplar la actualización del precio de la consulta del cliente:

```
API externa de precios (market data provider)
       │
       │  webhook / polling / streaming
       ▼
Price Ingestion Service
       │
       │  publica evento PriceUpdated { ticker, price, timestamp }
       ▼
Message Broker (Kafka / SNS+SQS)
       │
       ▼
QuoteService (consumer)
       │  actualiza precio en Redis al recibir el evento
       ▼
Redis (cache de precios por ticker)
       │
       ▼
GET /api/quotes/{ticker}   ← lee de Redis, latencia O(1), sin llamada externa
```

- Cuando el proveedor actualiza el precio de un CEDEAR (por ejemplo AAPL cambia de $189 a $192),
  publica un evento `PriceUpdated`.
- `QuoteService` consume ese evento y actualiza el valor en Redis.
- El `GET /api/quotes/{ticker}` siempre lee de Redis — sin latencia de red externa por request.
- El rate limiter opera igual sobre la capa HTTP, completamente independiente de la fuente de precios.

Este modelo también hace al sistema resiliente: si la API externa cae, los precios en cache
siguen disponibles. Se puede configurar un TTL conservador en Redis para que los precios no queden
desactualizados indefinidamente en caso de falla prolongada del proveedor.

**Cómo saber si se está usando Redis o el fallback in-memory**

El estado del circuit breaker es observable en tiempo real vía:
```
GET /actuator/circuitbreakers
```
- `CLOSED` → todo el tráfico va a Redis (operación normal)
- `OPEN` → Redis no responde, el fallback in-memory está activo
- `HALF_OPEN` → Redis se está recuperando, enviando requests de prueba

---

## 12. Cómo Usé IA


Previo a cualquier tipo de análisis, codificación, diagrama, estuve gran parte del tiempo armando un .md para el init del proyecto
y teniendo mayor contexto de rate limiter, diferentes implementaciones o approachs por personas relevantes en la industria
diagramas, diseños, trade offs entre algoritmos y mas cosas, luego inicie el repositorio en claude code con este .md para que comenzara con el spec 01 y asi fui llegando al final
Usé Claude Code (vía CLI) a lo largo de todo el proyecto. Acá hay un recuento honesto:

**El sistema de specs**: antes de escribir código, creé un conjunto de archivos de spec
(`spec-01` al `spec-09`) que dividieron el problema en piezas incrementales. Trabajé cada spec
en una feature branch separada, usando la IA para implementar cada una. Esto mantuvo el contexto
enfocado y los diffs revisables.

**Lo que generó la IA**: la mayor parte del código boilerplate — wiring de Spring Boot, config de Gradle,
setup de Testcontainers, el esqueleto del filtro, configuración de Resilience4j.

**Lo que investigué por mi cuenta**: cómo implementar el algoritmo en Redis. Encontré que Lua
scripts es el approach estándar para operaciones atómicas y partí de un template básico. Luego
usé Claude para evaluar trade-offs entre distintos enfoques e ir refinando la solución. Lo mismo
con las diferentes implementaciones del token bucket en Java — evalué `AtomicReference`,
`synchronized` y `ConcurrentHashMap.compute()` antes de decidir.

**Lo que yo conduje**: las decisiones de diseño centrales — empezar en memoria y agregar Redis
después, elegir `ConcurrentHashMap.compute()`, el clock inyectable para testabilidad, la
estructura de paquetes, y usar el `TokenBucket` en memoria tanto como prototipo como fallback del
circuit breaker.

**Lo que corregí**: las primeras versiones de la IA tenían varias cosas sobre-engineereadas —
un parser de `X-Forwarded-For` con validación de IP, un body JSON en las respuestas 429,
validaciones en el compact constructor de `RateLimitConfig`, un `slow-call-rate-threshold` en
la config del circuit breaker, y variables intermedias innecesarias en la llamada `execute()` de
Redis. Las fui removiendo al revisar el código, que también fue como aprendí qué hacía cada pieza.
