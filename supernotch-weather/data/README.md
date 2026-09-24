Local map data
==============

The geographic base is rendered locally. No map tiles or resources from other
weather websites are downloaded.

- `land_50m.geojson`: Natural Earth 1:50m land polygons.
- `coastlines_50m.geojson`: Natural Earth 1:50m coastlines.
- `boundaries.geojson`: Natural Earth 1:110m land boundaries.
- `places.tsv`: longitude, latitude, population and name derived from Natural
  Earth's 1:10m populated places (simple); coordinates are rounded to 5 decimals.

Sources: https://github.com/nvkelso/natural-earth-vector/tree/master/geojson
Natural Earth terms: https://www.naturalearthdata.com/about/terms-of-use/
All Natural Earth vector map data is public domain. The precipitation, cloud,
temperature, wind, and humidity fields are ECMWF IFS forecast data provided by
Open-Meteo under CC-BY-4.0, credited in the weather panel.
