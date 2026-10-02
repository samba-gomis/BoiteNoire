# BoiteNoire

Service d'ingestion et d'analyse des événements de **Pigeon**, une plateforme de messagerie professionnelle. Les connexions, paiements, erreurs, appels d'API et notifications sont stockés dans MongoDB et exploités par des pipelines d'agrégation exposés en API REST.

Projet B2, La Plateforme. Binôme : Samba Diop Gomis, Andoniaina Njarasoa.

## Avancement

| Étape | Livrable | État |
|---|---|---|
| 1 | Note de décision : relationnel ou documentaire | Fait : [docs/decision-note.md](docs/decision-note.md) |
| 2 | Schéma documentaire commenté | Fait : [docs/document-schema.md](docs/document-schema.md) |
| 3 | Générateur de données en Java | À faire |
| 4 | Quatre analyses exposées et documentées dans Swagger | À faire |
| 5 | Optimisation : explain avant, index, explain après | À faire |

La note de décision a été commitée (`0ba5dcb`) avant tout code applicatif.

## Choix déjà arrêtés

- **Base** : MongoDB, avec une collection unique et polymorphe `events`. La justification face à une table SQL se trouve dans la [note de décision](docs/decision-note.md).
- **Socle commun** à tous les événements : `type`, `timestamp`, `userId`, `sessionId` et `platform`. Les champs propres à chaque type sont dans un sous-document `payload`.
- **Sept types d'événements** : `USER_SIGNED_UP`, `USER_LOGGED_IN`, `MESSAGE_SENT`, `SUBSCRIPTION_PAID`, `APPLICATION_ERROR`, `API_CALLED` et `NOTIFICATION_SENT`.
- **Embedding** du détail de l'événement et de ses listes bornées (`stackTrace`, `attachments`, `attempts`).
- **Referencing** de l'utilisateur : l'événement porte un `userId`, et le profil est stocké dans la collection `users`.

Le détail et les justifications se trouvent dans le [schéma documentaire](docs/document-schema.md).

## Prérequis

- JDK 21 ou plus récent
- MongoDB 7.0 ou plus récent, accessible sur `mongodb://localhost:27017`. La version 7.0 est le minimum pour l'opérateur `$percentile`.

## Lancement

Le service Spring Boot n'est pas encore initialisé. Cette section sera complétée avec :

- la commande de lancement de l'API ;
- la commande unique du générateur de données ;
- l'URL de Swagger et un exemple d'appel pour chaque analyse.

## Structure du dépôt

```
BoiteNoire/
├── docs/
│   ├── decision-note.md     note de décision (ADR-001)
│   └── document-schema.md   schéma documentaire commenté
├── src/                     service Spring Boot et générateur (à venir)
└── README.md
```
