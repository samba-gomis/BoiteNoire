# BoiteNoire

Service d'ingestion et d'analyse des événements de **Pigeon**, une plateforme de messagerie professionnelle. Les connexions, paiements, erreurs, appels d'API et notifications sont stockés dans MongoDB et exploités par des pipelines d'agrégation exposés en API REST.

Projet B2, La Plateforme. Binôme : Samba Diop Gomis, Andoniaina Njarasoa.

## Avancement

| Étape | Livrable | État |
|---|---|---|
| 1 | Note de décision : relationnel ou documentaire | Fait : [docs/decision-note.md](docs/decision-note.md) |
| 2 | Schéma documentaire commenté | Fait : [docs/document-schema.md](docs/document-schema.md), traduit en classes Java (packages `event` et `user`) |
| 3 | Générateur de données en Java | À faire |
| 4 | Quatre analyses exposées et documentées dans Swagger | À faire |
| 5 | Optimisation : explain avant, index, explain après | À faire |

La note de décision a été commitée (`0ba5dcb`) avant tout code applicatif. Le modèle de données est en place (documents `events` et `users`) ; le générateur et les analyses restent à écrire.

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

Les tests écrivent dans une base séparée, `blackbox_test`, qu'ils vident à chaque exécution. Les données générées dans `blackbox` ne sont jamais touchées.

La commande du générateur de données et un exemple d'appel pour chaque analyse seront ajoutés avec les étapes 3 et 4.

## Structure du dépôt

```
BoiteNoire/
├── docs/
│   ├── decision-note.md                    note de décision (ADR-001)
│   └── document-schema.md                  schéma documentaire commenté
├── src/
│   ├── main/java/com/pigeon/blackbox/      code du service (et, à venir, du générateur)
│   ├── main/resources/application.yaml     configuration (connexion MongoDB)
│   └── test/java/com/pigeon/blackbox/      tests
├── .mvn/, mvnw, mvnw.cmd                   Maven Wrapper
├── pom.xml
└── README.md
```
