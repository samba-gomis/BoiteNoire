# Dossier de mesure : optimisation par index

Ce dossier mesure le coût des quatre analyses, choisit la plus coûteuse et l'optimise par un index. Les mesures « avant » ont été commitées avant la création de l'index : l'historique Git en fait foi.

## Méthode

- **Données** : le jeu produit par le générateur avec ses réglages par défaut (graine 42), soit 300 000 événements et 3 000 utilisateurs sur l'année 2025. MongoDB 8.3.11, en local.
- **Outil** : le profil Spring `explain` ([ExplainRunner.java](../../src/main/java/com/pigeon/blackbox/performance/ExplainRunner.java)). Il rejoue **exactement** les pipelines de l'API, tirés de [AnalyticsPipelines.java](../../src/main/java/com/pigeon/blackbox/analytics/AnalyticsPipelines.java), avec `explain("executionStats")`.
- **Répétitions** : pour chaque pipeline, une exécution de chauffe (chargement des données en mémoire), puis 5 mesures. On garde la médiane.
- **Deux périodes** par analyse : l'année 2025 entière, et le mois de mars 2025.
- **Indicateurs** :
  - documents examinés (`totalDocsExamined`) et clés d'index examinées (`totalKeysExamined`) ;
  - documents retenus par le `$match`, et documents retournés (les lignes du résultat) ;
  - temps d'exécution côté serveur (`executionTimeMillis` d'explain) et durée de l'agrégation vue par l'application.
- **Rapports bruts** : [explain-before.json](explain-before.json) contient, pour chaque cas, le pipeline mesuré, les indicateurs et la sortie complète d'`explain`.

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

Les sections sur l'index et sur les mesures après index sont ajoutées dans un commit suivant, une fois l'index créé.
