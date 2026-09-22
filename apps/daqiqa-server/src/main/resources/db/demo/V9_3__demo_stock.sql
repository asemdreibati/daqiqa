-- Demo data: stock for every product at every store. Deterministic but uneven, so promises have something to decide:
-- about one line in thirteen is out of stock, the rest hold between 5 and 60 units.
INSERT INTO inventory.stock_level (store_id, sku, on_hand, reserved, updated_at)
SELECT s.id,
       p.sku,
       CASE WHEN abs(hashtext(s.id || '/' || p.sku)) % 13 = 0 THEN 0
            ELSE 5 + abs(hashtext(p.sku || '/' || s.id)) % 56
       END,
       0,
       now()
FROM network.dark_store s
CROSS JOIN catalog.product p;
