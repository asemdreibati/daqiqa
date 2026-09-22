# Catalog

Reference module: the smallest complete example of the module anatomy, used as the pattern for every other module.

- **API**: `ProductCatalog` (`find`, `findAll`) for other modules; `GET /v1/products`, `GET /v1/products/{sku}`.
- **Domain**: `CatalogItem` (invariant: positive price), `Category`, `CatalogItemRepository` port.
- **Application**: `CatalogService` (read-only transactions), keyset pagination with a one-row look-ahead instead of
  a `COUNT(*)`.
- **Persistence**: `catalog.product`, index `(category, sku)` so browsing a category is an index walk with no sort.
  `findAll` binds one array parameter (`sku = ANY(?)`) so the plan is cached whatever the basket size.
- **Errors**: `PRODUCT_NOT_FOUND` (404), `INVALID_REQUEST` (400) as RFC 9457 problems.
- **Tests**: domain invariant, repository against real PostgreSQL (including pagination without gaps or repeats), web
  slice with `MockMvcTester`, architecture rules.
