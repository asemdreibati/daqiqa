# ADR-0006: H3 hexagonal cells for service areas

- Status: Accepted
- Date: 2026-09-22

## Context

Every promise starts with "which stores can serve this point?". This is on the hot path and must be answered in
microseconds, for any point in the city, while stores open, pause and close during the day.

## Decision

Use Uber's H3 grid at resolution 9 (cells of roughly 0.1 km²). Precompute, for every store, the cells within its
service radius, and keep an in-memory index `cell → candidate stores`. A lookup converts the point to its cell and reads
the list; exact distance and travel time are then computed for those few candidates only. The index is an immutable
snapshot replaced atomically when stores change, so readers never lock.

## Consequences

- Constant-time coverage lookups independent of the number of stores.
- Hexagons have uniform neighbour distances, which makes radius-based coverage and future demand heatmaps (orders per
  cell, riders per cell) natural.
- Resolution 9 means a coverage boundary is accurate to about a cell (~175 m edge); the exact distance check on
  candidates removes that error for the final answer.

## Alternatives considered

- **PostGIS radius queries**: accurate, but a database round trip on the hot path of every promise.
- **Geohash**: rectangular cells with uneven neighbour distances and edge effects.
- **Polygons per store**: more expressive (real delivery zones) and a good future extension; H3 cells can be generated
  from polygons, so the index design survives it.
