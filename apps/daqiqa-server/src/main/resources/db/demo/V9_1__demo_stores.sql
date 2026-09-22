-- Demo data: twelve dark stores across Dubai. Coordinates are neighbourhood centres, approximate by design.
-- Downtown starts BUSY to show how load lengthens promises; everything else is OPEN.
INSERT INTO network.dark_store (id, name_en, name_ar, latitude, longitude, status, active_pickers, service_radius_m) VALUES
  ('DXB-MARINA',        'Dubai Marina',          'دبي مارينا',          25.0805, 55.1403, 'OPEN', 6, 3000),
  ('DXB-JLT',           'Jumeirah Lakes Towers', 'أبراج بحيرات جميرا',  25.0693, 55.1427, 'OPEN', 5, 3000),
  ('DXB-BARSHA',        'Al Barsha',             'البرشاء',             25.1136, 55.2003, 'OPEN', 6, 3000),
  ('DXB-AL-QUOZ',       'Al Quoz',               'القوز',               25.1400, 55.2330, 'OPEN', 4, 3000),
  ('DXB-JUMEIRAH',      'Jumeirah',              'جميرا',               25.2048, 55.2438, 'OPEN', 5, 3000),
  ('DXB-DOWNTOWN',      'Downtown Dubai',        'وسط مدينة دبي',       25.1972, 55.2744, 'BUSY', 8, 3000),
  ('DXB-BUSINESS-BAY',  'Business Bay',          'الخليج التجاري',      25.1865, 55.2728, 'OPEN', 6, 3000),
  ('DXB-DEIRA',         'Deira',                 'ديرة',                25.2710, 55.3100, 'OPEN', 7, 3000),
  ('DXB-AL-NAHDA',      'Al Nahda',              'النهدة',              25.2890, 55.3720, 'OPEN', 4, 3500),
  ('DXB-MIRDIF',        'Mirdif',                'مردف',                25.2200, 55.4200, 'OPEN', 4, 3500),
  ('DXB-SILICON-OASIS', 'Dubai Silicon Oasis',   'واحة دبي للسيليكون',  25.1181, 55.3890, 'OPEN', 4, 3500),
  ('DXB-MOTOR-CITY',    'Motor City',            'موتور سيتي',          25.0450, 55.2360, 'OPEN', 3, 3500);
