# Schéma documentaire

Ce document décrit la structure des données stockées dans MongoDB et justifie les choix de modélisation. Il complète la note de décision [ADR-001](decision-note.md), qui explique pourquoi une base documentaire a été retenue.

## Vue d'ensemble

| Collection | Contenu | Volume attendu | Écriture |
|---|---|---|---|
| `events` | Un document par événement, 7 types | Au moins 100 000 sur une année simulée | Insertion seule, jamais modifié |
| `users` | Un document par utilisateur | Quelques milliers | Modifiable (changement de plan) |

```
 users                         events
 ┌───────────────┐            ┌─────────────────────────────┐
 │ _id           │<───────────│ userId        (référence)   │
 │ displayName   │   1 ── N   │ type                        │
 │ company       │            │ timestamp                   │
 │ country       │            │ sessionId                   │
 │ plan          │            │ platform                    │
 │ signedUpAt    │            │ payload { … } (embarqué)    │
 └───────────────┘            └─────────────────────────────┘
```

Chaque événement est composé de deux parties :

- un **socle commun**, identique pour tous les types, à la racine du document ;
- un sous-document **`payload`**, dont la structure dépend du `type`.

Cette séparation rend les champs communs identifiables par la structure elle-même : tout ce qui est à la racine est commun, tout ce qui est dans `payload` est propre au type.

## Le socle commun

| Champ | Type BSON | Obligatoire | Rôle |
|---|---|---|---|
| `_id` | ObjectId | Oui | Identifiant technique, généré par MongoDB |
| `type` | string (enum) | Oui | Type de l'événement ; détermine la structure de `payload` |
| `timestamp` | date (UTC) | Oui | Moment où l'événement s'est produit |
| `userId` | string | Oui | Référence vers `users._id` : l'utilisateur concerné |
| `sessionId` | string | Non | Session interactive d'origine ; `null` pour un appel d'API par clé ou une notification |
| `platform` | string (enum) | Oui | `WEB`, `IOS`, `ANDROID`, `DESKTOP`, `API` ou `SYSTEM` |

Ces champs répondent aux questions *qui* (`userId`), *quand* (`timestamp`), *quoi* (`type`) et *d'où* (`platform`, `sessionId`), quel que soit l'événement. C'est ce qui rend les recherches transverses possibles : chacune des quatre analyses commence par filtrer ou regrouper sur le socle, avant de lire éventuellement un champ de `payload`.

| Analyse | Champs du socle utilisés | Champs de `payload` utilisés |
|---|---|---|
| Top 10 des utilisateurs actifs | `timestamp`, `type`, `userId` | Aucun |
| Erreurs par type et par jour | `type`, `timestamp` | `errorType` |
| Temps de réponse par endpoint | `type`, `timestamp` | `endpoint`, `responseTimeMs` |
| Entonnoir de conversion | `type`, `userId`, `timestamp` | Aucun |

Les dates sont stockées en UTC. Le découpage par jour se fait au moment de la requête, dans le fuseau `Europe/Paris`.

## Les types d'événements

| `type` | Déclenché par | Champs propres (`payload`) | Liste embarquée |
|---|---|---|---|
| `USER_SIGNED_UP` | L'utilisateur | `acquisitionChannel`, `referrerUserId`, `initialPlan` | Aucune |
| `USER_LOGGED_IN` | L'utilisateur | `authMethod`, `success`, `mfaUsed`, `ip`, `userAgent`, `geo` | Aucune |
| `MESSAGE_SENT` | L'utilisateur | `conversationId`, `conversationType`, `recipientCount`, `contentLength` | `attachments` (10 max) |
| `SUBSCRIPTION_PAID` | L'utilisateur | `amount`, `currency`, `plan`, `billingPeriod`, `seats`, `paymentMethod`, `invoiceId` | Aucune |
| `APPLICATION_ERROR` | Le système, pendant une requête de l'utilisateur | `errorType`, `severity`, `service`, `endpoint`, `message` | `stackTrace` (20 lignes max) |
| `API_CALLED` | Une intégration, via la clé d'API de l'utilisateur | `method`, `endpoint`, `statusCode`, `responseTimeMs`, `apiKeyId` | Aucune |
| `NOTIFICATION_SENT` | Le système, à destination de l'utilisateur | `channel`, `template`, `delivered` | `attempts` (3 max) |

Les exemples ci-dessous suivent le même utilisateur fictif, `usr_000042`.

### `USER_SIGNED_UP`

```js
{
  _id: ObjectId("66f1c0a2e4b0a1b2c3d4e5f6"),
  type: "USER_SIGNED_UP",
  timestamp: ISODate("2025-03-02T09:41:00Z"),
  userId: "usr_000042",
  sessionId: "ses_8c1f2a",
  platform: "WEB",
  payload: {
    acquisitionChannel: "REFERRAL",   // ORGANIC | ADS | REFERRAL | PARTNER
    referrerUserId: "usr_000007",     // renseigné seulement si REFERRAL ; référence vers users
    initialPlan: "FREE"
  }
}
```

### `USER_LOGGED_IN`

```js
{
  _id: ObjectId("66f1c0a2e4b0a1b2c3d4e5f7"),
  type: "USER_LOGGED_IN",
  timestamp: ISODate("2025-03-14T08:12:44Z"),
  userId: "usr_000042",
  sessionId: "ses_b47e90",
  platform: "WEB",
  payload: {
    authMethod: "PASSWORD",           // PASSWORD | GOOGLE_SSO | MICROSOFT_SSO | MAGIC_LINK
    success: true,
    mfaUsed: false,
    ip: "203.0.113.24",
    userAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) ...",
    geo: { country: "FR", city: "Lyon" }   // sous-document embarqué
  }
}
```

### `MESSAGE_SENT`

```js
{
  _id: ObjectId("66f1c0a2e4b0a1b2c3d4e5f8"),
  type: "MESSAGE_SENT",
  timestamp: ISODate("2025-03-14T08:15:03Z"),
  userId: "usr_000042",
  sessionId: "ses_b47e90",
  platform: "WEB",
  payload: {
    conversationId: "conv_51e9",      // identifiant opaque côté Pigeon
    conversationType: "GROUP",        // DIRECT | GROUP | CHANNEL
    recipientCount: 6,
    contentLength: 284,               // la longueur seulement : le contenu n'est jamais journalisé
    attachments: [                    // embarqué, 10 au maximum, métadonnées seulement
      { mimeType: "application/pdf", sizeBytes: 482113 },
      { mimeType: "image/png", sizeBytes: 90544 }
    ]
  }
}
```

### `SUBSCRIPTION_PAID`

```js
{
  _id: ObjectId("66f1c0a2e4b0a1b2c3d4e5f9"),
  type: "SUBSCRIPTION_PAID",
  timestamp: ISODate("2025-03-20T17:02:51Z"),
  userId: "usr_000042",
  sessionId: "ses_d2093c",
  platform: "WEB",
  payload: {
    amount: NumberDecimal("96.00"),   // Decimal128 : jamais de double pour un montant
    currency: "EUR",
    plan: "BUSINESS",                 // PRO | BUSINESS
    billingPeriod: "MONTHLY",         // MONTHLY | YEARLY
    seats: 8,
    paymentMethod: "CARD",            // CARD | SEPA | PAYPAL
    invoiceId: "inv_2025_003418"
  }
}
```

### `APPLICATION_ERROR`

```js
{
  _id: ObjectId("66f1c0a2e4b0a1b2c3d4e5fa"),
  type: "APPLICATION_ERROR",
  timestamp: ISODate("2025-04-02T10:27:19Z"),
  userId: "usr_000042",
  sessionId: "ses_0f61aa",
  platform: "IOS",
  payload: {
    errorType: "DATABASE_TIMEOUT",    // DATABASE_TIMEOUT | UPSTREAM_UNAVAILABLE | NULL_REFERENCE
                                      // | VALIDATION_FAILED | RATE_LIMIT_EXCEEDED
    severity: "ERROR",                // WARNING | ERROR | CRITICAL
    service: "messaging-service",
    endpoint: "/api/v1/conversations/{conversationId}/messages",
    message: "Query exceeded 5000 ms",
    stackTrace: [                     // embarqué, tronqué à 20 lignes
      { className: "com.pigeon.messaging.MessageRepository", methodName: "findPage", lineNumber: 88 },
      { className: "com.pigeon.messaging.MessageService", methodName: "listMessages", lineNumber: 142 }
    ]
  }
}
```

### `API_CALLED`

```js
{
  _id: ObjectId("66f1c0a2e4b0a1b2c3d4e5fb"),
  type: "API_CALLED",
  timestamp: ISODate("2025-04-02T10:31:57Z"),
  userId: "usr_000042",
  sessionId: null,                    // appel par clé d'API : pas de session interactive
  platform: "API",
  payload: {
    method: "GET",
    endpoint: "/api/v1/channels/{channelId}/messages",   // modèle de route, pas l'URL réelle
    statusCode: 200,
    responseTimeMs: 143,
    apiKeyId: "key_3f9a"              // identifiant opaque de la clé d'API
  }
}
```

`endpoint` contient le modèle de route (`{channelId}`) et non l'URL appelée (`/channels/8812/messages`). Sinon, chaque identifiant créerait un endpoint distinct, et le regroupement de l'analyse 3 n'aurait plus de sens.

### `NOTIFICATION_SENT`

```js
{
  _id: ObjectId("66f1c0a2e4b0a1b2c3d4e5fc"),
  type: "NOTIFICATION_SENT",
  timestamp: ISODate("2025-04-07T07:00:02Z"),
  userId: "usr_000042",               // le destinataire
  sessionId: null,                    // émis par le système
  platform: "SYSTEM",
  payload: {
    channel: "EMAIL",                 // EMAIL | PUSH | SMS
    template: "weekly_digest",
    delivered: true,
    attempts: [                       // embarqué, 3 tentatives au maximum
      { attemptedAt: ISODate("2025-04-07T07:00:02Z"), outcome: "TIMEOUT" },
      { attemptedAt: ISODate("2025-04-07T07:05:02Z"), outcome: "DELIVERED" }
    ]
  }
}
```

## La collection `users`

```js
{
  _id: "usr_000042",                  // identifiant lisible, produit par le générateur
  displayName: "Léa Martin",
  company: "Atelier Nord",
  country: "FR",
  plan: "BUSINESS",                   // plan actuel : change au fil du temps
  signedUpAt: ISODate("2025-03-02T09:41:00Z")
}
```

L'identifiant est une chaîne lisible plutôt qu'un ObjectId : les résultats des analyses restent compréhensibles, et le générateur peut produire des identifiants reproductibles.

## Embedding et referencing

| Donnée | Choix | Critère décisif |
|---|---|---|
| `payload` dans l'événement | Embedding | Lecture conjointe : le détail n'a pas de sens sans l'événement, et inversement |
| `stackTrace`, `attachments`, `attempts`, `geo` | Embedding | Taille bornée et aucune croissance après l'écriture |
| L'utilisateur d'un événement (`userId`, `referrerUserId`) | Referencing | Croissance dans le temps, lecture conjointe rare, profil modifiable |

### Embedding : le détail de l'événement et ses listes

- **Taille du document.** Elle est bornée. Un événement courant pèse quelques centaines d'octets, et une erreur avec une pile de 20 lignes environ 2 Ko. C'est très loin de la limite de 16 Mo par document. Les listes sont plafonnées à l'écriture : 20 lignes de pile, 10 pièces jointes, 3 tentatives d'envoi.
- **Croissance dans le temps.** Elle est nulle : un événement est immuable. Aucune liste ne s'allonge après l'insertion.
- **Fréquence de lecture conjointe.** Elle est totale : on ne lit jamais une ligne de pile sans l'erreur qui la porte, ni une pièce jointe sans son message.

Seules les **métadonnées** des pièces jointes sont embarquées (type MIME, taille). Embarquer le contenu des fichiers ferait exploser la taille du document, c'est-à-dire le critère qui justifie l'embedding.

L'alternative écartée consistait à créer des collections `stack_frames` ou `attachments` qui référencent l'événement. Elle imposerait un `$lookup` à chaque lecture, pour des données jamais lues séparément : on reproduirait les tables filles du relationnel.

### Referencing : l'utilisateur

Deux formes d'embedding ont été envisagées puis écartées.

**1. Embarquer les événements dans le document utilisateur** (`users.events: [...]`).
- **Croissance dans le temps** : elle est sans limite. Avec une population en loi de puissance, un gros utilisateur produit plusieurs dizaines de milliers d'événements par an, soit plusieurs mégaoctets par an. La limite de 16 Mo serait atteinte en quelques années.
- **Coût d'écriture** : chaque nouvel événement réécrirait un document de plusieurs mégaoctets.
- **Coût des analyses** : les analyses qui portent sur tous les utilisateurs (top 10, erreurs par jour) devraient dérouler (`$unwind`) le tableau de chaque utilisateur.

**2. Recopier le profil de l'utilisateur dans chaque événement.**
- **Profil modifiable** : un changement de plan obligerait à mettre à jour des milliers d'événements, pourtant censés être immuables.
- **Fréquence de lecture conjointe** : elle est faible. Seul le top 10 affiche le nom et l'entreprise. Il suffit alors d'un `$lookup` vers `users` placé **après** le `$limit`, soit 10 jointures au lieu d'une par événement.

L'événement porte donc seulement `userId`, et le profil reste dans `users`. Quand une information doit refléter l'état **au moment de l'événement**, elle est stockée dans le `payload` comme un fait historique. Par exemple, `SUBSCRIPTION_PAID.payload.plan` est le plan effectivement payé ce jour-là, qui ne doit pas suivre les changements de plan ultérieurs.

### Références externes

`conversationId` et `apiKeyId` sont des identifiants opaques qui proviennent des autres systèmes de Pigeon. Le service ne les résout pas, car aucune collection ne leur correspond : la gestion des conversations et des clés d'API est hors du périmètre du service. Ils sont conservés pour permettre des regroupements.

## Définitions utilisées par les analyses

- **Utilisateur actif** : on compte les événements initiés par l'utilisateur, soit `USER_SIGNED_UP`, `USER_LOGGED_IN`, `MESSAGE_SENT`, `SUBSCRIPTION_PAID` et `API_CALLED`. `NOTIFICATION_SENT` est exclu, car l'utilisateur la reçoit sans agir. `APPLICATION_ERROR` est exclu aussi, car c'est une conséquence et non une action.
- **Erreur** : un événement `APPLICATION_ERROR`, regroupé par `payload.errorType` et par jour (fuseau `Europe/Paris`).
- **Temps de réponse** : les événements `API_CALLED`, regroupés par `payload.endpoint`.
- **Entonnoir de conversion** : par défaut `USER_SIGNED_UP` → `MESSAGE_SENT` → `SUBSCRIPTION_PAID`. Pour chaque utilisateur, on prend la première occurrence de chaque étape et on vérifie qu'elles se suivent dans l'ordre chronologique.

## Cohérence des données

MongoDB n'impose pas de schéma. Les règles suivantes sont donc garanties par l'application :

- Chaque type d'événement correspond à une classe Java. Le champ `type` détermine la classe de `payload`.
- Les valeurs énumérées sont en `UPPER_SNAKE_CASE` et les noms de champs en `camelCase`.
- Les dates sont stockées en UTC, les montants en Decimal128, et les endpoints sous forme de modèles de route.
- Un validateur `$jsonSchema` pourra être ajouté sur les champs du socle si nécessaire.

À ce stade, aucun index n'est créé en dehors de `_id`. Les index seront choisis après la mesure des requêtes, et justifiés dans le dossier de mesure.
