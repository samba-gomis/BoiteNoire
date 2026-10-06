# Dossier de mesure : optimisation par index

Ce dossier mesure le coût des quatre analyses, choisit la plus coûteuse et l'optimise par un index. Les mesures « avant » ont été commitées avant la création de l'index, et l'historique Git en fait foi : mesure avant (`7ad6108`), puis création de l'index (`86e6121`), puis mesure après.

## Constat

Sans index, chaque analyse parcourait les 300 000 documents. Avec l'index `{ type, timestamp, userId }`, l'entonnoir sur l'année n'en ouvre plus aucun (requête couverte, 157 295 clés lues) et passe de 359 à 161 ms, soit 2,2 fois plus vite.
Le gain grandit quand la période se resserre : sur mars, l'entonnoir passe de 167 à 15 ms (11 fois plus vite), car son coût dépend désormais des événements de la période, et non plus de la taille de toute la collection.
Le prix est modeste : 7,1 Mo d'index pour 128,6 Mo de données, environ une seconde de construction, et un arbre d'index de plus à mettre à jour à chaque insertion.

## Méthode

- **Données** : le jeu produit par le générateur avec ses réglages par défaut (graine 42), soit 300 000 événements et 3 000 utilisateurs sur l'année 2025. MongoDB 8.3.11, en local.
- **Outil** : le profil Spring `explain` ([ExplainRunner.java](../../src/main/java/com/pigeon/blackbox/performance/ExplainRunner.java)). Il rejoue **exactement** les pipelines de l'API, tirés de [AnalyticsPipelines.java](../../src/main/java/com/pigeon/blackbox/analytics/AnalyticsPipelines.java), avec `explain("executionStats")`.
- **Répétitions** : pour chaque pipeline, une exécution de chauffe (chargement des données en mémoire), puis 5 mesures. On garde la médiane.
- **Deux périodes** par analyse : l'année 2025 entière, et le mois de mars 2025.
- **Indicateurs** :
  - documents examinés (`totalDocsExamined`) et clés d'index examinées (`totalKeysExamined`) ;
  - documents retenus par le `$match`, et documents retournés (les lignes du résultat) ;
  - temps d'exécution côté serveur (`executionTimeMillis` d'explain) et durée de l'agrégation vue par l'application.
- **Rapports bruts** : [explain-before.json](explain-before.json) et [explain-after.json](explain-after.json) contiennent, pour chaque cas, le pipeline mesuré, les indicateurs et la sortie complète d'`explain`.

Pour relancer une mesure, avec MongoDB démarré et les données générées :

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=explain" "-Dspring-boot.run.arguments=--explain.label=before"
```

Le rapport est écrit dans `docs/performance/explain-<label>.json`. Un rapport existant n'est jamais écrasé.

## Avant index

Seul l'index par défaut `_id` existe.

| Analyse | Période | Plan d'exécution | Documents examinés | Clés examinées | Retenus par `$match` | Retournés | Serveur (ms) | Application (ms) |
|---|---|---|---:|---:|---:|---:|---:|---:|
| Top utilisateurs | année | `COLLSCAN → GROUP` | 300 000 | 0 | 255 331 | 10 | 301 | 228 |
| Top utilisateurs | mars | `COLLSCAN → GROUP` | 300 000 | 0 | 20 027 | 10 | 171 | 136 |
| Erreurs | année | `COLLSCAN → GROUP → GROUP` | 300 000 | 0 | 9 564 | 364 | 239 | 222 |
| Erreurs | mars | `COLLSCAN → GROUP → GROUP` | 300 000 | 0 | 685 | 31 | 170 | 166 |
| Temps de réponse | année | `COLLSCAN → PROJECTION_DEFAULT` | 300 000 | 0 | 45 710 | 9 | 346 | 287 |
| Temps de réponse | mars | `COLLSCAN → PROJECTION_DEFAULT` | 300 000 | 0 | 4 121 | 9 | 223 | 172 |
| **Entonnoir** | **année** | `COLLSCAN → GROUP` | **300 000** | **0** | **157 295** | **3** | **359** | **288** |
| Entonnoir | mars | `COLLSCAN → GROUP` | 300 000 | 0 | 12 124 | 4 | 167 | 140 |

Le plan complet de chaque cas, y compris les étapes exécutées après le moteur de requête (`$sort`, `$lookup`…), figure dans le rapport brut.

**Ce que montrent ces mesures** : les huit cas parcourent toute la collection (`COLLSCAN`) et examinent les 300 000 documents, même quand le `$match` n'en retient que 685 (les erreurs de mars). Le coût d'une analyse ne dépend donc pas de la question posée, mais de la taille totale de la collection : il grandira avec chaque nouvel événement.

## Requête retenue : l'entonnoir sur l'année

L'entonnoir sur l'année est la requête la plus coûteuse, sur les deux indicateurs de temps : 359 ms côté serveur et 288 ms côté application. Les temps de réponse sur l'année le suivent de très près (346 ms et 287 ms) ; l'entonnoir l'emporte aussi par la quantité de travail : il retient 157 295 événements, les regroupe par utilisateur et par étape, puis trie et parcourt la séquence de chaque utilisateur.

C'est aussi l'appel par défaut de l'endpoint `/api/analytics/funnel`, sans période : celui qu'un tableau de bord lancerait le plus souvent.

## Index créé

```js
db.events.createIndex({ type: 1, timestamp: 1, userId: 1 }, { name: "type_timestamp_userId" })
```

L'index est créé par [EventIndexes.java](../../src/main/java/com/pigeon/blackbox/event/EventIndexes.java) au démarrage de l'application. Le générateur, qui vide la collection, le recrée **après** avoir inséré les données : construire l'index une seule fois sur 300 000 événements prend environ une seconde, ce qui est plus rapide que de le mettre à jour à chacune des 300 000 insertions.

### Pourquoi ces champs, dans cet ordre

Le pipeline de l'entonnoir commence par un `$match` sur `type` (un `$in` sur 3 types) et sur `timestamp` (la période), puis regroupe par `userId` et par `type` en gardant le premier `timestamp`. Il ne lit donc que trois champs : `type`, `timestamp` et `userId`. L'ordre suit la règle **ESR** : l'Égalité, puis le tri (Sort), puis l'intervalle (Range).

1. **`type` en premier, pour l'égalité.** Avec le `$in`, MongoDB parcourt un intervalle de l'index par type demandé et écarte d'emblée les autres types : 157 295 événements sur 300 000 sur l'année.
2. **`timestamp` ensuite, pour l'intervalle.** Dans chaque type, les clés sont rangées par date : l'index saute directement au début de la période et s'arrête à sa fin. Aucun tri n'est à servir par l'index, car le `$sort` du pipeline porte sur les résultats du `$group`.
3. **`userId` en dernier, seulement pour être lu.** Il n'est ni filtré ni trié, mais c'est le dernier champ dont le `$group` a besoin. Avec lui, l'index contient tout ce que la requête lit : MongoDB calcule le résultat **sans ouvrir un seul document**. C'est une requête **couverte** (`PROJECTION_COVERED`, `totalDocsExamined: 0`).

### Les variantes écartées, mesurées

Pour vérifier cet ordre, les quatre variantes ont été créées sur une copie identique des données (même graine), puis l'entonnoir a été mesuré sur chacune, en forçant l'index avec `hint` (médiane de 5 mesures, temps côté serveur) :

```js
db.events.explain("executionStats").aggregate(pipeline, { hint: "timestamp_type_userId" })
```

| Index | Année : clés / documents examinés | Année (ms) | Mars : clés / documents examinés | Mars (ms) |
|---|---:|---:|---:|---:|
| **`{ type, timestamp, userId }`** (retenu) | **157 295 / 0** | **165** | **12 124 / 0** | **16** |
| `{ timestamp, type, userId }` | 300 000 / 0 | 487 | 23 622 / 0 | 42 |
| `{ type, userId, timestamp }` | 157 297 / 0 | 157 | 17 896 / 0 | 25 |
| `{ type, timestamp }` | 157 295 / 157 295 | 301 | 12 124 / 12 124 | 24 |
| Aucun index (rappel) | 0 / 300 000 | 359 | 0 / 300 000 | 167 |

- **`{ timestamp, type, userId }`** met l'intervalle avant l'égalité. L'index lit alors les clés de **tous** les types de la période, puis écarte les mauvais types un par un. Sur l'année, il lit 300 000 clés et devient **plus lent qu'un parcours complet de la collection** (487 ms contre 359 ms).
- **`{ type, userId, timestamp }`** place `userId` entre l'égalité et l'intervalle. La période ne borne plus un intervalle continu : MongoDB doit sauter d'utilisateur en utilisateur. C'est équivalent sur l'année, mais plus coûteux dès que la période se réduit (17 896 clés contre 12 124 sur mars).
- **`{ type, timestamp }`** sert bien le `$match`, mais MongoDB doit ouvrir chaque document retenu pour y lire `userId` (`FETCH`) : 157 295 documents lus sur l'année, et un temps presque doublé.

### Un index qui sert les quatre analyses

- **Le top des utilisateurs** lit les mêmes trois champs : il est couvert lui aussi.
- **Les erreurs et les temps de réponse** filtrent sur `type` et `timestamp`, le début de l'index. Ils l'utilisent pour ne lire que les documents retenus, puis ouvrent ces seuls documents pour y lire le `payload`.

Les tests [IndexCoverageTests.java](../../src/test/java/com/pigeon/blackbox/performance/IndexCoverageTests.java) vérifient l'ordre des champs, et que l'entonnoir et le top des utilisateurs restent couverts : aucun `COLLSCAN`, aucun `FETCH`, 0 document examiné.

## Après index

Mesure relancée avec le même outil, sur les mêmes données, avec l'index en place :

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=explain" "-Dspring-boot.run.arguments=--explain.label=after"
```

### La requête optimisée : l'entonnoir sur l'année

| | Avant | Après |
|---|---:|---:|
| Plan d'exécution | `COLLSCAN → GROUP` | `IXSCAN → PROJECTION_COVERED → GROUP` |
| Documents examinés | 300 000 | **0** |
| Clés d'index examinées | 0 | 157 295 |
| Documents retournés | 3 | 3 |
| Temps côté serveur (ms) | 359 | **161** |
| Temps côté application (ms) | 288 | **136** |

Sans index, MongoDB lisait les 300 000 documents pour en retenir 157 295. Avec l'index, il ne lit que les clés de ces 157 295 événements, triées par type puis par date, et trouve dans chaque clé les trois champs dont il a besoin. `PROJECTION_COVERED` confirme que la requête est couverte : aucun document n'est ouvert.

### Les huit cas

| Analyse | Période | Plan après | Documents examinés (avant → après) | Clés examinées (après) | Serveur, avant → après (ms) | Gain |
|---|---|---|---:|---:|---:|---:|
| Top utilisateurs | année | `IXSCAN → PROJECTION_COVERED → GROUP` | 300 000 → 0 | 255 331 | 301 → 238 | × 1,3 |
| Top utilisateurs | mars | `IXSCAN → PROJECTION_COVERED → GROUP` | 300 000 → 0 | 20 027 | 171 → 18 | × 9,5 |
| Erreurs | année | `IXSCAN → FETCH → GROUP → GROUP` | 300 000 → 9 564 | 9 564 | 239 → 85 | × 2,8 |
| Erreurs | mars | `IXSCAN → FETCH → GROUP → GROUP` | 300 000 → 685 | 685 | 170 → 6 | × 28 |
| Temps de réponse | année | `IXSCAN → FETCH → PROJECTION_DEFAULT` | 300 000 → 45 710 | 45 710 | 346 → 218 | × 1,6 |
| Temps de réponse | mars | `IXSCAN → FETCH → PROJECTION_DEFAULT` | 300 000 → 4 121 | 4 121 | 223 → 18 | × 12 |
| **Entonnoir** | **année** | `IXSCAN → PROJECTION_COVERED → GROUP` | **300 000 → 0** | **157 295** | **359 → 161** | **× 2,2** |
| Entonnoir | mars | `IXSCAN → PROJECTION_COVERED → GROUP` | 300 000 → 0 | 12 124 | 167 → 15 | × 11 |

**Ce que montrent ces mesures :**

- **Le coût suit maintenant la question posée.** Avant, chaque cas lisait 300 000 documents, quelle que soit la période. Après, le nombre de clés lues est exactement le nombre d'événements retenus par le `$match` : plus la période est courte, plus la requête est rapide.
- **L'index ne fait pas de miracle quand la question porte sur presque toute la collection.** Le top des utilisateurs sur l'année retient 5 types sur 7, soit 255 331 événements sur 300 000 (85 %) : l'index ne peut presque rien écarter, et le gain se limite au fait de ne plus ouvrir les documents (× 1,3).
- **Les analyses non couvertes en profitent aussi.** Les erreurs et les temps de réponse ont besoin du `payload` : ils ouvrent encore les documents (`FETCH`), mais seulement ceux qu'ils retiennent. Les erreurs de mars passent ainsi de 300 000 documents lus à 685 (× 28).

## Coût de l'index

| | Taille |
|---|---:|
| Données de la collection `events` (non compressées) | 128,6 Mo |
| Données sur disque (compressées) | 27,8 Mo |
| Index `_id` | 3,0 Mo |
| Index `type_timestamp_userId` | 7,1 Mo |

- **Place** : 7,1 Mo, soit environ 5,5 % de la taille des données non compressées. Un index n'est utile que s'il tient en mémoire, ce qui est largement le cas ici.
- **Construction** : environ une seconde sur 300 000 événements, une fois, à la fin de la génération.
- **Écritures** : chaque nouvel événement met à jour un arbre d'index de plus. Pour un flux d'événements qui ne sont jamais modifiés, et lus surtout par des analyses, ce surcoût est acceptable au vu des gains de lecture.
