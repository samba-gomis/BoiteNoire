# BoiteNoire

Service d'ingestion et d'analyse des événements de **Pigeon**, une plateforme de messagerie professionnelle. Les connexions, paiements, erreurs, appels d'API et notifications sont stockés dans MongoDB et exploités par des pipelines d'agrégation exposés en API REST.

Projet B2, La Plateforme. Binôme : Samba Diop Gomis, Andoniaina Njarasoa.

## Avancement

| Étape | Livrable | État |
|---|---|---|
| 1 | Note de décision : relationnel ou documentaire | Fait : [docs/decision-note.md](docs/decision-note.md) |
| 2 | Schéma documentaire commenté | Fait : [docs/document-schema.md](docs/document-schema.md), traduit en classes Java (packages `event` et `user`) |
| 3 | Générateur de données en Java | Fait : package `generator`, voir [Générer les données](#générer-les-données) |
| 4 | Quatre analyses exposées et documentées dans Swagger | Fait : package `analytics`, voir [Les analyses](#les-analyses) |
| 5 | Optimisation : explain avant, index, explain après | Fait : [docs/performance](docs/performance/README.md) |

La note de décision a été commitée (`0ba5dcb`) avant tout code applicatif. Les cinq étapes sont terminées : modèle de données (documents `events` et `users`), générateur, quatre analyses et optimisation par index.

## Choix déjà arrêtés

- **Base** : MongoDB, avec une collection unique et polymorphe `events`. La justification face à une table SQL se trouve dans la [note de décision](docs/decision-note.md).
- **Socle commun** à tous les événements : `type`, `timestamp`, `userId`, `sessionId` et `platform`. Les champs propres à chaque type sont dans un sous-document `payload`.
- **Sept types d'événements** : `USER_SIGNED_UP`, `USER_LOGGED_IN`, `MESSAGE_SENT`, `SUBSCRIPTION_PAID`, `APPLICATION_ERROR`, `API_CALLED` et `NOTIFICATION_SENT`.
- **Embedding** du détail de l'événement et de ses listes bornées (`stackTrace`, `attachments`, `attempts`).
- **Referencing** de l'utilisateur : l'événement porte un `userId`, et le profil est stocké dans la collection `users`.

Le détail et les justifications se trouvent dans le [schéma documentaire](docs/document-schema.md).

## Stack technique

- Java 21
- Spring Boot 4.1.1 (Spring Web MVC, Spring Data MongoDB, Validation)
- springdoc-openapi 3.1.0, pour la documentation Swagger
- MongoDB 7.0 ou plus récent
- Maven, via le Maven Wrapper fourni (aucune installation de Maven nécessaire)

## Prérequis

- **JDK 21 ou plus récent.** La variable `JAVA_HOME` doit pointer vers ce JDK, car c'est elle qu'utilise le Maven Wrapper.
- **MongoDB 7.0 ou plus récent**, démarré et accessible sur `mongodb://localhost:27017`. La version 7.0 est le minimum pour l'opérateur `$percentile`.

## Configuration

La connexion à MongoDB se règle dans [src/main/resources/application.yaml](src/main/resources/application.yaml) :

```yaml
spring:
  mongodb:
    uri: mongodb://localhost:27017/blackbox
```

Les données sont stockées dans la base `blackbox`. MongoDB ne la crée qu'à la première écriture.

Avec Spring Boot 4, la propriété est `spring.mongodb.uri`. L'ancienne syntaxe `spring.data.mongodb.uri` (Spring Boot 3) est ignorée sans message d'erreur.

## Générer les données

Avec MongoDB démarré, depuis la racine du dépôt :

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=generator"     # Windows : les guillemets sont nécessaires sous PowerShell
./mvnw spring-boot:run -Dspring-boot.run.profiles=generator           # Linux / macOS
```

La commande vide les collections `events` et `users` de la base `blackbox`, les remplit, puis s'arrête, en une minute environ. On peut donc la relancer sans créer de doublons. Avec les réglages par défaut, elle produit **300 000 événements** pour **3 000 utilisateurs** sur l'année 2025.

Les réglages se trouvent dans [application-generator.yaml](src/main/resources/application-generator.yaml) et peuvent être modifiés en ligne de commande :

| Propriété | Défaut | Rôle |
|---|---|---|
| `generator.seed` | `42` | Graine aléatoire : la même graine donne exactement les mêmes données |
| `generator.year` | `2025` | Année simulée |
| `generator.users` | `3000` | Nombre d'utilisateurs |
| `generator.events` | `300000` | Nombre total d'événements |
| `generator.zipf-exponent` | `1.0` | Concentration de l'activité sur les plus gros utilisateurs |
| `generator.batch-size` | `5000` | Nombre d'événements envoyés à MongoDB par insertion |

Exemple, pour produire 500 000 événements :

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=generator" "-Dspring-boot.run.arguments=--generator.events=500000"
```

Ce que contiennent les données :

- **Une population en loi de Zipf** : quelques gros utilisateurs (le plus actif produit plus de 10 % des événements) et une longue traîne de petits. Les gros utilisateurs sont plutôt les comptes les plus anciens.
- **Des rythmes réalistes**, à l'heure de Paris : creux la nuit, pics vers 10 h et 15 h, week-ends calmes, creux en août et à Noël, et une base d'utilisateurs qui grandit au fil de l'année.
- **Un entonnoir de conversion** : chaque utilisateur s'inscrit, environ 70 % envoient un premier message et environ 17 % finissent par s'abonner, avec des renouvellements mensuels ou annuels. Aucun événement n'est antérieur à l'inscription de son utilisateur.
- **Trois journées d'incident**, pendant lesquelles les erreurs sont environ huit fois plus fréquentes et l'API environ trois fois plus lente.
- **Des temps de réponse** propres à chaque endpoint, avec une longue traîne (loi log-normale).

## Lancement

Toutes les commandes se lancent depuis la racine du dépôt.

**Démarrer l'API :**

```powershell
.\mvnw.cmd spring-boot:run      # Windows
./mvnw spring-boot:run          # Linux / macOS
```

L'API démarre sur le port `8080`. Une fois lancée :

- **Swagger UI** est accessible sur http://localhost:8080/swagger-ui/index.html ;
- **la spécification OpenAPI** est accessible sur http://localhost:8080/v3/api-docs.

**Lancer les tests**, avec MongoDB démarré :

```powershell
.\mvnw.cmd test
```

Les tests du modèle et des analyses écrivent dans une base séparée, `blackbox_test`, qu'ils vident à chaque exécution. Les données générées dans `blackbox` ne sont jamais touchées.

- **Tests du générateur** : ils tournent en mémoire, sans base. Sur 30 000 événements, ils vérifient le volume, la reproductibilité, l'ordre de l'entonnoir et la forme des distributions.
- **Tests des analyses** : chaque pipeline tourne sur un petit jeu de données dont le résultat est connu à l'avance. Les tests vérifient aussi les réponses 400 et la présence des quatre analyses dans la documentation OpenAPI.

## Les analyses

Une fois les données générées et l'API démarrée, les quatre analyses sont disponibles en `GET`. Elles sont documentées dans [Swagger UI](http://localhost:8080/swagger-ui/index.html), où l'on peut aussi les essayer.

| Analyse | Endpoint | Paramètres |
|---|---|---|
| Utilisateurs les plus actifs | `/api/analytics/top-users` | `from` et `to` obligatoires, `limit` (10 par défaut, 100 au maximum) |
| Erreurs par type et par jour | `/api/analytics/errors` | `from` et `to` obligatoires |
| Temps de réponse par endpoint : moyenne et P95 | `/api/analytics/response-times` | `from` et `to` facultatifs |
| Entonnoir de conversion | `/api/analytics/funnel` | `steps` (par défaut `USER_SIGNED_UP,MESSAGE_SENT,SUBSCRIPTION_PAID`), `from` et `to` facultatifs |

Les dates sont au format ISO-8601, par exemple `2025-03-01T00:00:00Z`. `from` est inclus et `to` est exclu. Un paramètre manquant ou invalide donne une réponse **400** au format `ProblemDetail` (RFC 9457), avec un message qui explique le problème.

Exemples, à ouvrir dans un navigateur ou à appeler avec `curl.exe` :

```
http://localhost:8080/api/analytics/top-users?from=2025-01-01T00:00:00Z&to=2026-01-01T00:00:00Z
http://localhost:8080/api/analytics/errors?from=2025-06-01T00:00:00Z&to=2025-06-08T00:00:00Z
http://localhost:8080/api/analytics/response-times
http://localhost:8080/api/analytics/funnel
http://localhost:8080/api/analytics/funnel?steps=USER_SIGNED_UP,MESSAGE_SENT
```

Avec les données générées par défaut, l'entonnoir renvoie par exemple :

```json
[
  { "position": 1, "step": "USER_SIGNED_UP",    "users": 3000, "percentOfPrevious": 100.0, "percentOfFirst": 100.0 },
  { "position": 2, "step": "MESSAGE_SENT",      "users": 2128, "percentOfPrevious": 70.9,  "percentOfFirst": 70.9 },
  { "position": 3, "step": "SUBSCRIPTION_PAID", "users": 509,  "percentOfPrevious": 23.9,  "percentOfFirst": 17.0 }
]
```

**Comment elles sont calculées.** Chaque analyse est un pipeline d'agrégation MongoDB, écrit dans [AnalyticsPipelines.java](src/main/java/com/pigeon/blackbox/analytics/AnalyticsPipelines.java) étape par étape, comme on le taperait dans mongosh. Chaque pipeline commence par un `$match`, le seul étage capable d'utiliser un index. MongoDB fait tous les calculs ; Java ne lit que les quelques documents de résultat pour les transformer en réponse JSON.

- **Utilisateurs actifs** : seuls les événements initiés par l'utilisateur sont comptés (inscription, connexion, message, paiement, appel d'API). Le profil est joint avec `$lookup` **après** le `$limit`, soit une jointure par utilisateur retenu seulement.
- **Erreurs** : les jours sont découpés à minuit, heure de Paris (`$dateTrunc`). Les jours sans erreur n'apparaissent pas.
- **Temps de réponse** : regroupement par méthode HTTP et par route, car un `GET` et un `POST` sur la même route n'ont pas les mêmes temps. Le P95 est estimé par `$percentile` (MongoDB 7.0 ou plus récent).
- **Entonnoir** : pour chaque utilisateur, on garde la première occurrence de chaque étape. Une étape ne compte que si elle arrive après la précédente.

## Mesurer les performances

Le profil `explain` rejoue les pipelines des quatre analyses avec `explain("executionStats")` et écrit un rapport dans `docs/performance/explain-<label>.json` :

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=explain" "-Dspring-boot.run.arguments=--explain.label=before"
```

Le label (`before`, `after`…) donne son nom au rapport ; un rapport existant n'est jamais écrasé. La méthode, les mesures et l'optimisation sont décrites dans le [dossier de mesure](docs/performance/README.md).

**Résultat** : l'analyse la plus coûteuse, l'entonnoir sur l'année, lisait les 300 000 documents en 359 ms. Avec l'index composé `{ type: 1, timestamp: 1, userId: 1 }`, elle n'ouvre plus aucun document (requête couverte) et s'exécute en 161 ms, et en 15 ms sur un mois au lieu de 167 ms. L'index pèse 7,1 Mo.

## Structure du dépôt

```
BoiteNoire/
├── docs/
│   ├── decision-note.md                    note de décision (ADR-001)
│   ├── document-schema.md                  schéma documentaire commenté
│   └── performance/                        dossier de mesure : méthode, rapports explain, index
├── src/
│   ├── main/java/com/pigeon/blackbox/
│   │   ├── event/                          modèle des événements (socle commun et payloads)
│   │   ├── user/                           modèle des utilisateurs
│   │   ├── generator/                      générateur de données (profil generator)
│   │   ├── analytics/                      les quatre analyses : pipelines, service, contrôleur REST
│   │   └── performance/                    mesure des analyses avec explain (profil explain)
│   ├── main/resources/
│   │   ├── application.yaml                configuration (connexion MongoDB)
│   │   ├── application-generator.yaml      réglages du générateur
│   │   └── application-explain.yaml        réglages de la mesure
│   └── test/java/com/pigeon/blackbox/      tests
├── .mvn/, mvnw, mvnw.cmd                   Maven Wrapper
├── pom.xml
└── README.md
```
