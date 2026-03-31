# Spec 05 - Broker API Mock

## Objective

Create a mock stock-broker API that serves as the protected resource behind the rate limiter.

## Tasks

1. **Quote** (record): Fields `symbol` (String), `price` (BigDecimal), `timestamp` (Instant).

2. **QuoteService** (interface): Method `Quote getQuote(String symbol)`.

3. **DefaultQuoteService** (implements `QuoteService`): Returns mock prices for known symbols (e.g., AAPL, GOOGL, MSFT). Throws `IllegalArgumentException` for unknown symbols.

4. **QuoteController** (`@RestController`): `GET /api/quotes/{symbol}` - returns JSON quote. Delegates to `QuoteService`.

5. **GlobalExceptionHandler** (`@RestControllerAdvice`): Handles `IllegalArgumentException` (400), generic exceptions (500). Returns consistent error response body.

## Tests

- Controller returns 200 with valid quote for known symbol.
- Controller returns 400 for unknown symbol.
- Service returns correct mock data.
- Error handler produces expected response structure.
