# ADR-001 : Stocker les événements de Pigeon dans une base documentaire plutôt que dans une table SQL

**Statut :** acceptée · **Décideurs :** Samba Diop Gomis, Andoniaina Njarasoa

## Contexte

Pigeon dispose déjà d'une base relationnelle ; l'option la plus simple serait d'y ajouter une table `events`. Le service ingère et analyse les événements de Pigeon : inscriptions, connexions, messages, paiements d'abonnement, erreurs applicatives, appels à l'API publique, notifications. Ces données ont quatre caractéristiques :

- **Elles sont hétérogènes.** Une connexion porte `ip`, `userAgent` et `authMethod`. Un paiement porte `amount`, `currency` et `plan`. Une erreur porte `errorType` et `stackTrace`. Un appel API porte `endpoint`, `statusCode` et `responseTimeMs`. En dehors de *qui*, *quand* et *quoi*, ces types n'ont presque aucun champ en commun.
- **Elles sont immuables.** Un événement est écrit une fois et n'est jamais modifié.
- **Elles arrivent en volume**, et de nouveaux types d'événements apparaîtront avec le produit.
- **Elles sont lues de deux façons** : par des agrégations sur une période (top utilisateurs, erreurs par jour, p95, entonnoir) et par des recherches transverses (« tout ce qu'a fait l'utilisateur X »).

## Option A : modélisation relationnelle

Trois variantes sont possibles, et aucune n'est satisfaisante sur ce cas :

1. **Une table `events` unique** avec l'union de toutes les colonnes (environ 30). Chaque ligne n'en remplit que 4 à 6, donc la table est majoritairement `NULL`. On ne peut pas poser de contrainte `NOT NULL` par type, et chaque nouveau type impose un `ALTER TABLE`.
2. **Une table par type**, avec éventuellement une table mère commune. Le schéma est propre, mais chaque recherche transverse devient un `UNION` ou une jointure sur N tables. Ajouter un type implique une nouvelle table, une migration, et la réécriture de toutes les requêtes transverses.
3. **Une colonne JSON (`JSONB`)** pour la partie variable. C'est l'alternative sérieuse, car elle règle l'hétérogénéité. Mais seuls les champs du socle restent relationnels : tout ce qu'on analyse (`responseTimeMs`, `errorType`, `plan`) vit dans le JSON, interrogé par conversions (`(payload->>'responseTimeMs')::int`) et indexé champ par champ par des index d'expression. C'est du documentaire *à l'intérieur* d'un SGBD relationnel.

Dans les variantes 1 et 2, les listes (les lignes d'une `stackTrace`, les pièces jointes d'un message) ne tiennent dans aucune colonne et imposent des tables filles supplémentaires.

Ce que le relationnel apporte vraiment, c'est l'intégrité référentielle, les transactions multi-tables et les jointures performantes. Or aucun de ces atouts n'est nécessaire ici : les événements sont isolés, immuables, et ne sont jamais mis à jour ensemble.

## Option B : modélisation documentaire

On utilise une collection unique `events`. Chaque document contient un **socle commun** (`type`, `timestamp`, `userId`, `sessionId`, `platform`) et un sous-document **`payload`** dont la structure dépend du type.

- **Aucune valeur vide.** Chaque document ne contient que les champs qui le concernent.
- **Un nouveau type ne demande aucune migration**, seulement du code applicatif.
- **Les recherches transverses portent sur une seule collection**, et un même index sur le socle commun (par exemple `type` + `timestamp`) sert à tous les types.
- **Ce qui est lu ensemble est stocké ensemble.** Les détails d'une erreur ou d'un appel API sont embarqués dans l'événement.
- **Les analyses s'exécutent côté base** avec le pipeline d'agrégation (`$match`, `$group`, `$dateTrunc`, `$percentile`), sans rapatrier les données en Java.

Cette option a aussi des limites, qui sont assumées :

- **La base n'impose pas de schéma.** La cohérence est garantie côté application par des classes Java typées par type d'événement, et on peut ajouter un validateur `$jsonSchema` si besoin.
- **Il n'y a pas de clé étrangère vers `users`**, seulement une référence par `userId`.
- **Un `$lookup` coûte plus cher qu'un JOIN**, donc on le réserve aux cas utiles.

## Comparaison

| Critère | Relationnel | Documentaire |
|---|---|---|
| Structures hétérogènes | Colonnes `NULL` (A1), N tables (A2) ou JSON hors du schéma (A3) | Natif : un `payload` par type |
| Recherche transverse | Simple en A1 et A3, `UNION` en A2 | Une collection, un index |
| Ajout d'un type d'événement | Migration de schéma | Aucune migration |
| Intégrité / transactions | Fortes, mais inutiles ici | Faibles, compensées côté application |

## Décision et critère qui l'emporte

**Les événements sont stockés dans MongoDB, dans une collection unique polymorphe `events`.**

**Le critère décisif est l'hétérogénéité des structures, combinée au besoin de requêtes transverses.** En relationnel classique, il faut choisir entre deux défauts : une table creuse (A1) ou des requêtes éclatées sur plusieurs tables (A2). L'option JSONB (A3) échappe à ce dilemme, mais en logeant un modèle documentaire dans un SGBD relationnel dont les garanties (intégrité, transactions) ne servent pas à des événements immuables et indépendants. Autant choisir une base conçue pour ce modèle : MongoDB satisfait les deux exigences nativement et exécute les analyses dans son pipeline d'agrégation.

**Conséquences :** l'ajout d'un nouveau type d'événement devient trivial. En contrepartie, la validation des documents repose sur l'application, et l'indexation des champs communs doit être conçue et mesurée (voir le dossier de mesure).
