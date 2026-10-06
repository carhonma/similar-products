# Similar Products

Servicio que expone `GET /product/{productId}/similar` en el puerto `5000` según `similarProducts.yaml`.
Por cada producto pide sus ids similares a `:3001` y luego el detalle de cada uno, devolviendo la lista ordenada.

## Decisiones

- Spring Boot 4.1.1 + Java 21 con virtual threads (`spring.threads.virtual.enabled=true`) para aguantar los 200 VUs del test sin complicar el código con programación reactiva.
- Llamadas a detalle en paralelo con `CompletableFuture` sobre un executor de virtual threads. El orden se conserva porque se hace `join` en el orden original de `similarids`.
- Timeouts cortos para no arrastrar los mocks lentos:
  - connect 300ms, read 500ms en el `RestClient` downstream.
  - timeout global por detalle de 1200ms al hacer `get`.
  - Con esto `/2` (~1s/5s) responde en ~550ms y `/3` (hasta 50s) en ~550ms devolviendo lo disponible.
- Resiliencia: los 404 y 500 de detalle se ignoran (se devuelve `Optional.empty`), solo se propaga 404 cuando `similarids` da 404.
- Cliente HTTP con pool (`JdkClientHttpRequestFactory` sobre `java.net.http.HttpClient` HTTP/1.1). Al principio usé `SimpleClientHttpRequestFactory` y bajo k6 petaba con `BindException: Address already in use` por agotamiento de puertos, el pool lo corrige.
- Tomcat: `accept-count=500`, `max-connections=8192` para la ráfaga de k6.

## Razonamiento

Elegí hilos virtuales en vez de WebFlux reactivo porque el flujo es "fan-out" simple (1 + N llamadas) y con bloqueo queda mucho más legible para revisión. Con hilos de plataforma 200 VUs x 3 detalles colapsan el pool de Tomcat; con virtuales cada petición bloqueada cuesta poco y el código sigue siendo secuencial. Por eso `spring.threads.virtual.enabled=true` más un executor de virtuales para los detalles.

Paralelismo con orden: `similarids` ya viene ordenado por similaridad y el contrato pide devolver ordenado. Lanzo un `CompletableFuture` por id y luego hago `join` recorriendo la lista original, filtrando vacíos. Así lo lento no retrasa lo rápido y lo que falla no mueve el orden de lo que sí llega.

Timeouts, mirando `mocks.json`:
- `/product/3` tarda 100ms y tiene que entrar.
- `/product/100` tarda 1000ms y tiene que caer.
- `/product/1000` 5000ms y `/product/10000` 50000ms tienen que caer sí o sí.
- Puse read en 500ms porque cae justo en el hueco 100 -> 1000. Con 150ms el producto de 100ms se volvería flaky bajo 200 VUs; con 900ms se colaría el de 1000ms y el p95 se iría arriba.
- Connect en 300ms porque contra `localhost:3001` conectar son pocos ms; si tarda más es que hay saturación y prefiero fallar rápido a apilar hilos.
- El global por detalle en 1200ms es el techo del `future.get`: 500ms de read más margen para cola del executor y pausas bajo carga. Sigue muy por debajo del `gracefulStop` de 30s del `verySlow` y deja la respuesta típica en ~550ms.

Afinado con pool: la primera versión con `SimpleClientHttpRequestFactory` pasó las pruebas funcionales pero bajo k6 empezó a escupir `BindException: Address already in use: getsockopt`. Cada llamada abría socket nuevo sin reutilizar keep-alive y se agotaban los puertos efímeros. Lo cambié a `JdkClientHttpRequestFactory` sobre `HttpClient` HTTP/1.1, que reutiliza conexiones, y se acabaron los errores de bind. En Tomcat subí `accept-count` y `max-connections` para la ráfaga inicial de 200 VUs.

## Cómo probarlo

Necesitas Docker Desktop.

```bash
# desde la copia de backendDevTest
docker compose up -d simulado influxdb grafana
curl http://localhost:3001/product/1/similarids
# -> [2,3,4]

# app
mvn package
java -jar target/similar-products-0.0.1-SNAPSHOT.jar

curl http://localhost:5000/product/1/similar
curl http://localhost:5000/product/2/similar
curl http://localhost:5000/product/4/similar
```

Test de carga oficial:

```bash
docker compose run --rm k6 run /scripts/test.js
```

Resultados en mi máquina: ~10500 iteraciones, `avg ~420ms`, `p95 ~830ms`. Ver Grafana en `http://localhost:3000/d/Le2Ku9NMk/k6-performance-test`.

## Configuración

En `application.properties`:

```properties
server.port=5000
spring.threads.virtual.enabled=true
app.downstream.base-url=http://localhost:3001
app.downstream.connect-timeout-ms=300
app.downstream.read-timeout-ms=500
app.detail-overall-timeout-ms=1200
```

## Estructura

- `web/SimilarProductsController` - `GET /product/{productId}/similar`
- `web/ApiExceptionHandler` - 404 cuando no existe el producto base
- `service/SimilarProductsService` - orquesta `similarids` + detalles en paralelo
- `config/AppConfig` - `RestClient` con pool y executor virtual
- `model/ProductDetail` - `id, name, price, availability`
