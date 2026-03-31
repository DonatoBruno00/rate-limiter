# Rate Limiter

Rate limiter por IP de cliente usando token bucket, Redis y circuit breaker, aplicado sobre una API REST de un broker mock.

Para el detalle de arquitectura, decisiones de diseño y uso de IA: [DESIGN.md](./DESIGN.md)

---

## Requisitos

- Java 17+
- Docker (para Redis y Testcontainers)

## Cómo correrlo

```bash
# Levantar Redis
docker run -d -p 6379:6379 redis:7-alpine

# Correr la aplicación
./gradlew bootRun
```

La API queda disponible en `http://localhost:8080`.
Swagger UI: `http://localhost:8080/swagger-ui.html`

## Endpoints

```
GET /api/quotes/{ticker}        → devuelve el precio de una acción
GET /actuator/health            → health check (excluido del rate limiting)
GET /actuator/metrics           → métricas (allowed_total, rejected_total, fallback_total)
GET /actuator/circuitbreakers   → estado del circuit breaker (CLOSED=Redis, OPEN=in-memory)
```

## Pruebas rápidas

**Request normal:**
```bash
curl -i http://localhost:8080/api/quotes/AAPL
```

**Ticker inválido (404):**
```bash
curl -i http://localhost:8080/api/quotes/INVALID
```

Tickers disponibles: `AAPL`, `GOOGL`, `MSFT`, `AMZN`, `TSLA`

## Probar el circuit breaker (Redis → fallback in-memory)

1. Abrí Swagger en `http://localhost:8080/swagger-ui.html`
2. Parás Redis desde la terminal:
```bash
docker stop $(docker ps -q --filter ancestor=redis:7-alpine)
```
3. Desde Swagger ejecutás `GET /api/quotes/AAPL` 5 veces seguidas
4. Verificás que el circuit breaker se abrió:
```bash
curl -s http://localhost:8080/actuator/circuitbreakers | python3 -m json.tool
# state: "OPEN" → fallback in-memory activo
```
5. Volvés a levantar Redis:
```bash
docker start $(docker ps -aq --filter ancestor=redis:7-alpine)
```
6. Después de 30 segundos el circuit breaker vuelve a `CLOSED` y Redis retoma el tráfico.

## Cómo correr los tests

```bash
./gradlew test
```

Los tests de integración levantan Redis automáticamente vía Testcontainers — no hace falta tener Redis corriendo.
