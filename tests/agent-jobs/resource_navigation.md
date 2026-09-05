# Job: navigate the Cameo model via resource URIs only (sealed)

Use ONLY the cameo MCP resource reads (do not call cameo_* tools).

Steps:
1. Read `cameo://project` and from `modelRoots` report the primary root's name.
2. Read `cameo://element/{id}` for:
   - `_18_1_3c00182_1450814089835_833770_50692` (FFDS Context)
   Report name, type, qualifiedName and stereotypes.
3. Read `cameo://element/{id}/relationships` for `_18_1_3c00182_1450814090309_516729_51197`
   (Fire). Report how many relationships it has.
4. Read `cameo://diagram/{id}` for `_2024x_2_1_1749637565192_411443_129`
   (FFDS Context Definition BDD). Report elementCount.

Report which resources you read. Keep the whole reply under 300 words.