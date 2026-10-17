# MineNorth Stats

Mod Forge 1.20.1 (`minenorthstats`), **serveur uniquement** (les joueurs n'ont rien à installer). Il collecte des statistiques légères sur les joueurs et génère un **site web statique** de tableaux de bord (rétention, joueurs actifs, heures de pointe, économie, objets, carte de chaleur, fiches joueurs). Fonctionne seul ; les données d'argent, de métiers, de services et de permis sont relevées en plus quand l'API MineNorth et les autres mods sont présents (par réflexion : rien n'est obligatoire).

## Installation
Déposer `minenorth-stats-1.0.0.jar` dans `mods/` du serveur. Au premier démarrage : `config/minenorth_stats.toml` et le site dans `config/minenorth_stats/site/`.

## Ce qui est collecté
Léger : un compteur par événement, un relevé des positions toutes les 30 s, un relevé complet toutes les N minutes (5 par défaut), sauvegarde atomique en tâche de fond (jamais d'écriture sur le thread du serveur).
- **Connexions** : sessions (début/fin), premier et dernier passage, temps de jeu, jours actifs → **DAU / WAU / MAU**, **rétention J1 / J7 / J30 par cohorte** (semaine d'arrivée), **heures de pointe** (jour de semaine × heure), durée des sessions.
- **Déplacements** : positions agrégées par zone de 16×16 blocs et par dimension (carte de chaleur).
- **Activités** : blocs cassés / posés, créatures et joueurs tués, morts, objets fabriqués, trajets en véhicule (entrée dans une entité d'un autre mod, p. ex. véhicule MTS ; chevaux, bateaux et wagonnets vanilla ne comptent pas) et temps passé en véhicule, **temps de service police / secours** (prise de service de l'API), **revenus / dépenses / salaires / paiements** (nouvelles lignes de l'historique EuroBank de chaque joueur en ligne).
- **Possessions** : solde bancaire (EuroBank, aussi hors ligne), courbe de l'économie (masse monétaire, médiane, part des 10 % les plus riches, Gini), inventaire + coffre de l'Ender (60 objets les plus nombreux par joueur, dernier état connu ; le contenu des shulkers n'est pas compté), entreprises (CompanyService), policier / secouriste, permis listés dans la config.
- **Non collecté** : achats détaillés en boutique (aucun point d'accès dans l'API : seuls les paiements bancaires sont comptés), véhicules possédés (pas d'accès dans l'API), contenu des coffres du monde.

## Le site
`/stats site` (ou automatiquement toutes les `siteMinutes`, 30 par défaut, et au démarrage) écrit dans `config/minenorth_stats/site/` : `index.html`, `app.js`, `style.css`, `chart.umd.min.js` (Chart.js 4.4.1, licence MIT, **embarqué, aucun CDN**), `data.js` et `data.json`. Il s'ouvre **en double-cliquant sur index.html** (même hors serveur) : récupérez le dossier par FTP / panneau d'hébergement.
Onglets : Vue d'ensemble · Rétention · Activités · Économie · Objets · Carte · Joueurs (tableau avec recherche et tri, fiche joueur : sessions, activités, patrimoine, solde sur 60 jours). Sélecteur de période 14 / 30 / 90 jours / tout.
Un exemple avec de **fausses données** : `exemple/site/` (régénérable avec `py -I exemple/generer_exemple.py`).

### Consulter en direct (option)
Dans `config/minenorth_stats.toml`, section `[http]` : `actif = true`, `port` (8765), `adresse` (127.0.0.1 par défaut = seulement depuis la machine du serveur, à combiner avec un tunnel SSH / VPN / reverse proxy HTTPS), `jeton` (vide = jeton aléatoire écrit dans `<monde>/data/minenorth_stats/jeton.txt`). Ouvrir `http://hôte:port/?jeton=…` ; un cookie garde ensuite la session. Le serveur ne sert que les 6 fichiers du site, refuse tout sans jeton (limite à 10 essais ratés par minute et par IP). **Le trafic n'est pas chiffré** (HTTP) : ne l'exposez pas tel quel sur Internet.

## Commandes (opérateurs, niveau 3)
- `/stats site` : génère le site maintenant.
- `/stats info` : nombre de joueurs suivis, réglages actifs.
- `/stats jeton` : adresse d'accès du serveur web (si activé).
- `/stats oublier <joueur>` : **efface toutes les données d'un joueur** (pseudo, UUID ou nom RP déjà connu). Il sera de nouveau suivi à sa prochaine connexion.

## Vie privée
- Par défaut les joueurs sont identifiés par **UUID abrégé** ; `affichage = "pseudo"` ou `"rp"` (nom RP, mod Identité) dans `[site]`.
- `anonymiser = true` : plus aucun nom ni UUID dans le site (codes irréversibles « Joueur A1B2C3 », sel aléatoire gardé dans les données du monde).
- `conservationJours` (180 par défaut) : au-delà, jours, positions, courbes et joueurs inactifs sont effacés (nettoyage quotidien).
- `ignorerOperateurs` pour ne pas suivre l'équipe.
- Données brutes : `<monde>/data/minenorth_stats/stats.json` (+ `.bak`), à ne pas diffuser. Le site généré contient des données personnelles : ne le laissez pas en accès libre. Pensez à informer vos joueurs (RGPD).

## Configuration `config/minenorth_stats.toml`
`[collecte]` echantillonMinutes, conservationJours, ignorerOperateurs, licences · `[site]` siteMinutes, affichage, anonymiser · `[http]` actif, port, adresse, jeton.

## Limites connues
Les compteurs détaillés par jour du site portent sur les 60 derniers jours (le cumul depuis l'arrivée est conservé). Les jours sont ceux du fuseau du serveur. Rétention : un joueur est « actif » un jour s'il s'est connecté ce jour-là. Un arrêt brutal du serveur peut perdre au plus N minutes de données.

## Licence
**Tous droits réservés - MineNorthRP.** Voir [LICENSE](LICENSE). Chart.js : MIT (en-tête conservé dans `chart.umd.min.js`).
