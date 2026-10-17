package fr.minenorth.stats;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Données persistées (JSON Gson) dans &lt;monde&gt;/data/minenorth_stats/stats.json. Les jours sont des chaînes « aaaa-mm-jj ». */
public final class Data {
    public int version = 1;
    /** Sel aléatoire pour l'anonymisation (jamais exporté). */
    public String salt;
    public long created;
    /** uuid -> joueur. */
    public Map<String, PlayerRec> players = new LinkedHashMap<>();
    /** jour -> secondes-joueur passées en ligne, par heure (24 cases). */
    public Map<String, int[]> hours = new TreeMap<>();
    /** jour -> « dimension|cx|cz » -> nombre de passages (zones de 16 blocs). */
    public Map<String, Map<String, Integer>> heat = new TreeMap<>();
    /** Courbe serveur : un point par échantillon. */
    public List<Point> series = new ArrayList<>();
    /** jour -> économie du serveur (dernier relevé du jour). */
    public Map<String, Eco> eco = new TreeMap<>();

    public static final class Point {
        public long t;
        public int online;
        public long money;
        public int accounts;
    }

    public static final class Eco {
        public long total;
        public int accounts;
        public long median;
        /** Part des 10 % les plus riches, en millièmes (650 = 65 %). */
        public int top10;
    }

    public static final class DayRec {
        public int playS;
        /** Dernier solde du jour (centimes), -1 = inconnu. */
        public long bal = -1;
        public Map<String, Long> c = new TreeMap<>();
    }

    public static final class PlayerRec {
        public String name = "";
        public String rp = "";
        public long first, last, playMs;
        /** Début de la session en cours, 0 = hors ligne. */
        public long cur;
        /** [début, fin] des sessions terminées (les plus récentes, plafonnées). */
        public List<long[]> sessions = new ArrayList<>();
        public int nSessions;
        public Map<String, DayRec> days = new TreeMap<>();
        /** Compteurs cumulés depuis le premier passage. */
        public Map<String, Long> tot = new TreeMap<>();
        public long balance = -1;
        public List<String> companies = new ArrayList<>();
        public boolean police, secours;
        public List<String> licences = new ArrayList<>();
        /** Dernier inventaire connu (objet -> quantité), plafonné aux plus nombreux. */
        public Map<String, Integer> items = new LinkedHashMap<>();
        public long lastTx;
    }
}
